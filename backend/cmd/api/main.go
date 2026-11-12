// Command api runs the RunTrail HTTP server.
//
//	api              start the server (migrates the database first)
//	api healthcheck  exit 0 when /healthz answers (used by Docker; the image has no curl)
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
	_ "time/tzdata" // the distroless image has no zoneinfo; timezones like Asia/Bangkok must still parse

	"github.com/gofiber/fiber/v3"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/suphanatchanlek30/runtrail/backend/internal/api"
	"github.com/suphanatchanlek30/runtrail/backend/internal/config"
	"github.com/suphanatchanlek30/runtrail/backend/internal/db"
	"github.com/suphanatchanlek30/runtrail/backend/internal/storage"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		os.Exit(healthcheck())
	}
	if err := run(); err != nil {
		slog.Error("server stopped", "err", err)
		os.Exit(1)
	}
}

func healthcheck() int {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	c := http.Client{Timeout: 3 * time.Second}
	res, err := c.Get("http://127.0.0.1:" + port + "/healthz")
	if err != nil || res.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	level := slog.LevelInfo
	if strings.EqualFold(cfg.LogLevel, "debug") {
		level = slog.LevelDebug
	}
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})))

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := connectWithRetry(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	if err := db.Migrate(pool); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	slog.Info("database ready")

	store, err := storage.New(ctx, storage.Options{
		Bucket: cfg.S3Bucket, Region: cfg.S3Region, Endpoint: cfg.S3Endpoint, PublicEndpoint: cfg.S3PublicEndpoint,
		AccessKey: cfg.S3AccessKey, SecretKey: cfg.S3SecretKey, PathStyle: cfg.S3PathStyle,
	})
	if err != nil {
		return err
	}
	if cfg.S3Endpoint != "" && cfg.S3CreateBucket { // local S3-compatible server: create the bucket on first start
		if err := retry(ctx, 20, func() error { return store.EnsureBucket(ctx) }); err != nil {
			return fmt.Errorf("bucket: %w", err)
		}
	}

	app, srv := api.New(api.Deps{Pool: pool, Store: store, Config: cfg})
	go srv.RunJobs(ctx)

	slog.Info("listening", "port", cfg.Port, "public_base_url", cfg.PublicBaseURL)
	return app.Listen(":"+cfg.Port, fiber.ListenConfig{
		GracefulContext:       ctx,
		ShutdownTimeout:       10 * time.Second,
		DisableStartupMessage: true,
	})
}

func connectWithRetry(ctx context.Context, url string) (pool *poolT, err error) {
	err = retry(ctx, 30, func() error {
		pool, err = db.Open(ctx, url)
		return err
	})
	return pool, err
}

func retry(ctx context.Context, attempts int, fn func() error) error {
	var err error
	for i := 0; i < attempts; i++ {
		if err = fn(); err == nil {
			return nil
		}
		slog.Warn("waiting for dependency", "attempt", i+1, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return err
}

type poolT = pgxpool.Pool
