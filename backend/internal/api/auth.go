package api

import (
	"context"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/auth"
	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
)

type tokenResponse struct {
	User         *userJSON `json:"user,omitempty"`
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	TokenType    string    `json:"token_type"`
	ExpiresIn    int       `json:"expires_in"` // seconds until access_token expires
}

func (s *Server) issueTokens(ctx context.Context, q pgx.Tx, uid, family uuid.UUID, device *string) (tokenResponse, error) {
	plain, hash, err := auth.NewRefreshToken()
	if err != nil {
		return tokenResponse{}, err
	}
	now := s.now()
	if _, err := q.Exec(ctx, `INSERT INTO refresh_tokens (user_id, token_hash, family_id, device_name, expires_at, created_at)
		VALUES ($1,$2,$3,$4,$5,$6)`, uid, hash, family, device, now.Add(s.cfg.RefreshTTL), now); err != nil {
		return tokenResponse{}, err
	}
	access, _, err := s.jwt.Sign(uid, now)
	if err != nil {
		return tokenResponse{}, err
	}
	return tokenResponse{AccessToken: access, RefreshToken: plain, TokenType: "Bearer",
		ExpiresIn: int(s.cfg.AccessTTL / time.Second)}, nil
}

func (s *Server) inTx(ctx context.Context, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.pool, fn)
}

type registerReq struct {
	Email       string  `json:"email" validate:"required,email,max=254"`
	Password    string  `json:"password" validate:"required,min=8,max=128"`
	DisplayName string  `json:"display_name" validate:"required,min=1,max=40"`
	CountryCode *string `json:"country_code" validate:"omitempty,len=2,alpha"`
	DeviceName  *string `json:"device_name" validate:"omitempty,max=100"`
}

func (s *Server) register(c fiber.Ctx) error {
	var r registerReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	r.Email = strings.ToLower(strings.TrimSpace(r.Email))
	r.DisplayName = strings.TrimSpace(r.DisplayName)
	if r.DisplayName == "" {
		return httpx.Field("display_name", "required", "กรุณาใส่ชื่อที่แสดง")
	}
	if r.CountryCode != nil {
		cc := strings.ToUpper(*r.CountryCode)
		r.CountryCode = &cc
	}
	hash, err := auth.HashPassword(r.Password)
	if err != nil {
		return err
	}
	var out tokenResponse
	ctx := c.Context()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var uid uuid.UUID
		now := s.now()
		if err := tx.QueryRow(ctx, `INSERT INTO users (email, password_hash, display_name, country_code, created_at, updated_at)
			VALUES ($1,$2,$3,$4,$5,$5) RETURNING id`, r.Email, hash, r.DisplayName, r.CountryCode, now).Scan(&uid); err != nil {
			return err
		}
		var err error
		if out, err = s.issueTokens(ctx, tx, uid, uuid.New(), r.DeviceName); err != nil {
			return err
		}
		u, err := s.loadUser(ctx, tx, uid)
		out.User = u
		return err
	})
	if isUniqueViolation(err, "users_email_key") {
		return &httpx.Error{Status: fiber.StatusConflict, Code: "email_taken", Message: "อีเมลนี้มีบัญชีอยู่แล้ว",
			Fields: map[string]string{"email": "taken"}}
	}
	if err != nil {
		return err
	}
	return c.Status(fiber.StatusCreated).JSON(out)
}

type loginReq struct {
	Email      string  `json:"email" validate:"required,max=254"`
	Password   string  `json:"password" validate:"required,max=128"`
	DeviceName *string `json:"device_name" validate:"omitempty,max=100"`
}

func (s *Server) login(c fiber.Ctx) error {
	var r loginReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx := c.Context()
	var uid uuid.UUID
	var hash string
	err := s.pool.QueryRow(ctx, `SELECT id, password_hash FROM users WHERE email = $1 AND deleted_at IS NULL`,
		strings.ToLower(strings.TrimSpace(r.Email))).Scan(&uid, &hash)
	if noRows(err) {
		_, _ = auth.VerifyPassword(r.Password, auth.DummyHash) // same timing as a real check
		return badCredentials()
	}
	if err != nil {
		return err
	}
	if ok, err := auth.VerifyPassword(r.Password, hash); err != nil || !ok {
		return badCredentials()
	}
	var out tokenResponse
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var err error
		if out, err = s.issueTokens(ctx, tx, uid, uuid.New(), r.DeviceName); err != nil {
			return err
		}
		out.User, err = s.loadUser(ctx, tx, uid)
		return err
	})
	if err != nil {
		return err
	}
	return c.JSON(out)
}

func badCredentials() error {
	return httpx.Unauthorized("invalid_credentials", "อีเมลหรือรหัสผ่านไม่ถูกต้อง")
}

type refreshReq struct {
	RefreshToken string `json:"refresh_token" validate:"required,max=200"`
}

// refresh rotates the refresh token. Re-using an already rotated token outside the grace
// window means it was probably stolen, so the whole family (device session) is revoked.
// Inside the grace window (app UI and sync worker refreshing at the same moment) the old
// token is still exchanged for a fresh one instead of logging the user out.
func (s *Server) refresh(c fiber.Ctx) error {
	var r refreshReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx := c.Context()
	var out tokenResponse
	var reused bool
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		var (
			uid, family      uuid.UUID
			device           *string
			expires          time.Time
			rotated, revoked *time.Time
			userDeleted      bool
		)
		err := tx.QueryRow(ctx, `SELECT t.user_id, t.family_id, t.device_name, t.expires_at, t.rotated_at, t.revoked_at,
				u.deleted_at IS NOT NULL
			FROM refresh_tokens t JOIN users u ON u.id = t.user_id
			WHERE t.token_hash = $1 FOR UPDATE OF t`, auth.HashToken(r.RefreshToken)).
			Scan(&uid, &family, &device, &expires, &rotated, &revoked, &userDeleted)
		if noRows(err) {
			return httpx.Unauthorized("invalid_refresh_token", "กรุณาเข้าสู่ระบบใหม่")
		}
		if err != nil {
			return err
		}
		now := s.now()
		switch {
		case revoked != nil || userDeleted:
			return httpx.Unauthorized("refresh_token_revoked", "กรุณาเข้าสู่ระบบใหม่")
		case !now.Before(expires):
			return httpx.Unauthorized("refresh_token_expired", "เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่")
		case rotated != nil && now.Sub(*rotated) > s.cfg.RefreshGrace:
			reused = true
			_, err := tx.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = $2 WHERE family_id = $1 AND revoked_at IS NULL`, family, now)
			return err // commit the revocation, then answer 401 below
		case rotated == nil:
			if _, err := tx.Exec(ctx, `UPDATE refresh_tokens SET rotated_at = $2 WHERE token_hash = $1`,
				auth.HashToken(r.RefreshToken), now); err != nil {
				return err
			}
		}
		out, err = s.issueTokens(ctx, tx, uid, family, device)
		return err
	})
	if err != nil {
		return err
	}
	if reused {
		return httpx.Unauthorized("refresh_token_reused", "ตรวจพบการใช้ token ซ้ำ กรุณาเข้าสู่ระบบใหม่")
	}
	return c.JSON(out)
}

func (s *Server) logout(c fiber.Ctx) error {
	var r refreshReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	_, err := s.pool.Exec(c.Context(), `UPDATE refresh_tokens SET revoked_at = $2
		WHERE revoked_at IS NULL AND family_id = (SELECT family_id FROM refresh_tokens WHERE token_hash = $1)`,
		auth.HashToken(r.RefreshToken), s.now())
	if err != nil {
		return err
	}
	return c.SendStatus(fiber.StatusNoContent)
}

type changePasswordReq struct {
	OldPassword string  `json:"old_password" validate:"required,max=128"`
	NewPassword string  `json:"new_password" validate:"required,min=8,max=128"`
	DeviceName  *string `json:"device_name" validate:"omitempty,max=100"`
}

// changePassword signs out every other device and returns a fresh pair for this one.
func (s *Server) changePassword(c fiber.Ctx) error {
	var r changePasswordReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	var hash string
	if err := s.pool.QueryRow(ctx, `SELECT password_hash FROM users WHERE id = $1`, uid).Scan(&hash); err != nil {
		return err
	}
	if ok, _ := auth.VerifyPassword(r.OldPassword, hash); !ok {
		return httpx.Field("old_password", "mismatch", "รหัสผ่านเดิมไม่ถูกต้อง")
	}
	newHash, err := auth.HashPassword(r.NewPassword)
	if err != nil {
		return err
	}
	var out tokenResponse
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		now := s.now()
		if _, err := tx.Exec(ctx, `UPDATE users SET password_hash = $2, updated_at = $3 WHERE id = $1`, uid, newHash, now); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE refresh_tokens SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL`, uid, now); err != nil {
			return err
		}
		out, err = s.issueTokens(ctx, tx, uid, uuid.New(), r.DeviceName)
		return err
	})
	if err != nil {
		return err
	}
	return c.JSON(out)
}

// requireAuth checks the Bearer token and that the account still exists.
func (s *Server) requireAuth(c fiber.Ctx) error {
	h := c.Get(fiber.HeaderAuthorization)
	token, ok := strings.CutPrefix(h, "Bearer ")
	if !ok || token == "" {
		return httpx.Unauthorized("missing_token", "กรุณาเข้าสู่ระบบ")
	}
	uid, err := s.jwt.Parse(token, s.now())
	if err != nil {
		return httpx.Unauthorized("invalid_token", "token ไม่ถูกต้องหรือหมดอายุ")
	}
	var alive bool
	if err := s.pool.QueryRow(c.Context(), `SELECT deleted_at IS NULL FROM users WHERE id = $1`, uid).Scan(&alive); err != nil || !alive {
		if err != nil && !noRows(err) {
			return err
		}
		return httpx.Unauthorized("account_gone", "ไม่พบบัญชีนี้")
	}
	c.Locals(ctxUserID, uid)
	return c.Next()
}
