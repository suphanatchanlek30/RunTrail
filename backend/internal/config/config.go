// Package config reads all settings from environment variables.
package config

import (
	"errors"
	"time"

	"github.com/caarlos0/env/v11"
)

type Config struct {
	Port        string `env:"PORT" envDefault:"8080"`
	DatabaseURL string `env:"DATABASE_URL,required"`
	LogLevel    string `env:"LOG_LEVEL" envDefault:"info"`

	// Base URL that phones and browsers use to reach this server (share links are built from it).
	PublicBaseURL string `env:"PUBLIC_BASE_URL" envDefault:"http://localhost:8080"`
	// Header holding the real client IP when running behind Caddy/ALB. Empty = use the socket address.
	ProxyHeader string `env:"PROXY_HEADER"`

	JWTSecret    string        `env:"JWT_SECRET,required"`
	AccessTTL    time.Duration `env:"ACCESS_TTL" envDefault:"15m"`
	RefreshTTL   time.Duration `env:"REFRESH_TTL" envDefault:"720h"`
	RefreshGrace time.Duration `env:"REFRESH_GRACE" envDefault:"30s"`

	AuthRateLimit       int           `env:"AUTH_RATE_LIMIT" envDefault:"10"` // requests per minute per IP, 0 = off
	PublicRateLimit     int           `env:"PUBLIC_RATE_LIMIT" envDefault:"60"`
	LeaderboardCacheTTL time.Duration `env:"LEADERBOARD_CACHE_TTL" envDefault:"60s"`

	S3Bucket   string `env:"S3_BUCKET" envDefault:"runtrail-photos"`
	S3Region   string `env:"S3_REGION" envDefault:"ap-southeast-1"`
	S3Endpoint string `env:"S3_ENDPOINT"` // empty = real AWS S3
	// Endpoint used when signing URLs for phones. MinIO in Docker needs the Mac's LAN IP here,
	// because the signature covers the host and the phone cannot resolve "minio".
	S3PublicEndpoint string `env:"S3_PUBLIC_ENDPOINT"`
	S3AccessKey      string `env:"S3_ACCESS_KEY"` // empty = default AWS chain (IAM role on EC2)
	S3SecretKey      string `env:"S3_SECRET_KEY"`
	S3PathStyle      bool   `env:"S3_PATH_STYLE" envDefault:"false"`
	// Create the bucket at startup when S3_ENDPOINT is set (local RustFS/MinIO). Set false for a hosted
	// bucket created in the provider's console (Cloudflare R2), whose token may not manage buckets.
	S3CreateBucket bool `env:"S3_CREATE_BUCKET" envDefault:"true"`
}

func Load() (Config, error) {
	var c Config
	if err := env.Parse(&c); err != nil {
		return c, err
	}
	if len(c.JWTSecret) < 32 {
		return c, errors.New("JWT_SECRET must be at least 32 characters")
	}
	if c.S3PublicEndpoint == "" {
		c.S3PublicEndpoint = c.S3Endpoint
	}
	return c, nil
}
