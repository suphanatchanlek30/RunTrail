// Package api wires every HTTP route of the RunTrail backend.
package api

import (
	"context"
	"errors"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/gofiber/fiber/v3/middleware/limiter"
	"github.com/gofiber/fiber/v3/middleware/logger"
	"github.com/gofiber/fiber/v3/middleware/recover"
	"github.com/gofiber/fiber/v3/middleware/requestid"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/suphanatchanlek30/runtrail/backend/internal/auth"
	"github.com/suphanatchanlek30/runtrail/backend/internal/config"
	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
	"github.com/suphanatchanlek30/runtrail/backend/internal/storage"
)

type Deps struct {
	Pool   *pgxpool.Pool
	Store  storage.Storage
	Config config.Config
	Now    func() time.Time // injectable so tests can move time forward
	Quiet  bool             // skip request logging (tests)
}

type Server struct {
	pool  *pgxpool.Pool
	store storage.Storage
	cfg   config.Config
	jwt   auth.JWT
	now   func() time.Time
	lb    *lbCache
}

// Body limit for the raw (possibly gzipped) request. The decompressed cap lives in httpx.Decode.
const rawBodyLimit = 6 << 20

func New(d Deps) (*fiber.App, *Server) {
	if d.Now == nil {
		d.Now = time.Now
	}
	s := &Server{
		pool: d.Pool, store: d.Store, cfg: d.Config, now: d.Now,
		jwt: auth.JWT{Secret: []byte(d.Config.JWTSecret), TTL: d.Config.AccessTTL},
		lb:  newLBCache(d.Config.LeaderboardCacheTTL),
	}
	app := fiber.New(fiber.Config{
		AppName:      "RunTrail API",
		BodyLimit:    rawBodyLimit,
		ErrorHandler: httpx.ErrorHandler,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		ProxyHeader:  d.Config.ProxyHeader,
		// Do not let Fiber decode gzip for us; httpx.Decode does it with its own size cap.
	})
	app.Use(recover.New())
	app.Use(requestid.New())
	if !d.Quiet {
		app.Use(logger.New(logger.Config{Format: "${time} ${status} ${method} ${path} ${latency} ${reqHeader:X-Request-ID}\n"}))
	}

	app.Get("/healthz", func(c fiber.Ctx) error { return c.JSON(fiber.Map{"status": "ok"}) })
	app.Get("/readyz", s.ready)
	app.Get("/s/:code", rateLimit(d.Config.PublicRateLimit), s.sharePage)

	v1 := app.Group("/api/v1")
	authLimit := rateLimit(d.Config.AuthRateLimit)
	v1.Post("/auth/register", authLimit, s.register)
	v1.Post("/auth/login", authLimit, s.login)
	v1.Post("/auth/refresh", authLimit, s.refresh)
	v1.Post("/auth/logout", s.logout)
	v1.Post("/auth/change-password", authLimit, s.requireAuth, s.changePassword)

	v1.Get("/me", s.requireAuth, s.getMe)
	v1.Patch("/me", s.requireAuth, s.patchMe)
	v1.Delete("/me", s.requireAuth, s.deleteMe)
	v1.Get("/me/stats", s.requireAuth, s.myStats)
	v1.Post("/me/avatar/upload-url", s.requireAuth, s.avatarUploadURL)
	v1.Post("/me/avatar/confirm", s.requireAuth, s.avatarConfirm)
	v1.Delete("/me/avatar", s.requireAuth, s.avatarDelete)

	v1.Get("/runs", s.requireAuth, s.listRuns)
	v1.Put("/runs/:id", s.requireAuth, s.putRun)
	v1.Patch("/runs/:id", s.requireAuth, s.patchRun)
	v1.Delete("/runs/:id", s.requireAuth, s.deleteRun)
	v1.Get("/runs/:id", s.requireAuth, s.getRun)
	v1.Get("/runs/:id/track", s.requireAuth, s.getTrack)
	v1.Post("/runs/:id/photo/upload-url", s.requireAuth, s.photoUploadURL)
	v1.Post("/runs/:id/photo/confirm", s.requireAuth, s.photoConfirm)
	v1.Delete("/runs/:id/photo", s.requireAuth, s.photoDelete)
	v1.Post("/runs/:id/share", s.requireAuth, s.createShare)
	v1.Delete("/runs/:id/share", s.requireAuth, s.deleteShare)

	v1.Get("/leaderboard", s.requireAuth, s.leaderboard)
	v1.Get("/leaderboard/me", s.requireAuth, s.leaderboardMe)
	v1.Get("/users/:id/summary", s.requireAuth, s.userSummary)

	v1.Get("/notifications", s.requireAuth, s.listNotifications)
	v1.Post("/notifications/read", s.requireAuth, s.markRead)

	v1.Get("/public/runs/:code", rateLimit(d.Config.PublicRateLimit), s.publicRun)

	app.Use(func(c fiber.Ctx) error { return fiber.ErrNotFound })
	return app, s
}

func rateLimit(perMinute int) fiber.Handler {
	return limiter.New(limiter.Config{
		Max:        max(perMinute, 1),
		Expiration: time.Minute,
		Next:       func(fiber.Ctx) bool { return perMinute <= 0 },
		LimitReached: func(fiber.Ctx) error {
			return httpx.E(fiber.StatusTooManyRequests, "rate_limited", "เรียกถี่เกินไป ลองใหม่อีกครั้งในอีกสักครู่")
		},
	})
}

func (s *Server) ready(c fiber.Ctx) error {
	ctx, cancel := context.WithTimeout(c.Context(), 3*time.Second)
	defer cancel()
	status, code := fiber.Map{"db": "ok", "storage": "ok"}, fiber.StatusOK
	if err := s.pool.Ping(ctx); err != nil {
		status["db"], code = err.Error(), fiber.StatusServiceUnavailable
	}
	if err := s.store.Ping(ctx); err != nil {
		status["storage"], code = err.Error(), fiber.StatusServiceUnavailable
	}
	return c.Status(code).JSON(status)
}

// ---------- small helpers shared by handlers ----------

const ctxUserID = "uid"

func userID(c fiber.Ctx) uuid.UUID { return c.Locals(ctxUserID).(uuid.UUID) }

func pathUUID(c fiber.Ctx, name string) (uuid.UUID, error) {
	id, err := uuid.Parse(c.Params(name))
	if err != nil {
		return uuid.Nil, httpx.Field(name, "uuid", "รหัสไม่ถูกต้อง")
	}
	return id, nil
}

func isUniqueViolation(err error, constraint string) bool {
	var pe *pgconn.PgError
	return errors.As(err, &pe) && pe.Code == "23505" && (constraint == "" || pe.ConstraintName == constraint)
}

// querier is satisfied by both the pool and a transaction.
type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

func noRows(err error) bool { return errors.Is(err, pgx.ErrNoRows) }

// presign returns a short-lived download URL, or nil when there is no object.
func (s *Server) presign(ctx context.Context, key *string) *string {
	if key == nil || *key == "" {
		return nil
	}
	u, err := s.store.PresignGet(ctx, *key, time.Hour)
	if err != nil {
		return nil
	}
	return &u
}
