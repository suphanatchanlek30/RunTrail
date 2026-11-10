package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/suphanatchanlek30/runtrail/backend/internal/config"
	"github.com/suphanatchanlek30/runtrail/backend/internal/db"
	"github.com/suphanatchanlek30/runtrail/backend/internal/storage"
)

var testPool *pgxpool.Pool

// TestMain starts one real Postgres for the whole package (needs Docker running).
func TestMain(m *testing.M) {
	ctx := context.Background()
	ctr, err := tcpg.Run(ctx, "postgres:17-alpine",
		tcpg.WithDatabase("runtrail"), tcpg.WithUsername("runtrail"), tcpg.WithPassword("runtrail"),
		tcpg.BasicWaitStrategies())
	if err != nil {
		fmt.Fprintln(os.Stderr, "start postgres (is Docker running?):", err)
		os.Exit(1)
	}
	url, err := ctr.ConnectionString(ctx, "sslmode=disable")
	if err == nil {
		testPool, err = db.Open(ctx, url)
	}
	if err == nil {
		err = db.Migrate(testPool)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "setup db:", err)
		_ = testcontainers.TerminateContainer(ctr)
		os.Exit(1)
	}
	code := m.Run()
	testPool.Close()
	_ = testcontainers.TerminateContainer(ctr)
	os.Exit(code)
}

// clock lets tests move time forward (refresh grace window, token expiry).
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

// Wednesday 11 Nov 2026 10:00 UTC — the ISO week started Monday 9 Nov.
var testNow = time.Date(2026, 11, 11, 10, 0, 0, 0, time.UTC)

type env struct {
	t     *testing.T
	app   *fiber.App
	srv   *Server
	store *storage.Memory
	clock *clock
}

func newEnv(t *testing.T, tweak ...func(*config.Config)) *env {
	t.Helper()
	_, err := testPool.Exec(context.Background(),
		`TRUNCATE users, refresh_tokens, runs, run_tracks, run_splits, photo_uploads CASCADE`)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{
		JWTSecret: strings.Repeat("s", 40), AccessTTL: 15 * time.Minute, RefreshTTL: 720 * time.Hour,
		RefreshGrace: 30 * time.Second, PublicBaseURL: "https://runtrail.test",
		AuthRateLimit: 0, PublicRateLimit: 0, LeaderboardCacheTTL: 0,
	}
	for _, f := range tweak {
		f(&cfg)
	}
	clk := &clock{t: testNow}
	store := storage.NewMemory()
	app, srv := New(Deps{Pool: testPool, Store: store, Config: cfg, Now: clk.Now, Quiet: true})
	return &env{t: t, app: app, srv: srv, store: store, clock: clk}
}

type resp struct {
	Status int
	Body   []byte
	Header http.Header
}

func (r resp) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.Body, &m); err != nil {
		t.Fatalf("not JSON (%d): %s", r.Status, r.Body)
	}
	return m
}

func (r resp) errCode(t *testing.T) string {
	t.Helper()
	e, _ := r.json(t)["error"].(map[string]any)
	code, _ := e["code"].(string)
	return code
}

// do sends a request. body may be nil, []byte (sent as-is) or anything JSON-encodable.
func (e *env) do(method, path, token string, body any, headers ...string) resp {
	e.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		buf, err := json.Marshal(b)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(buf)
	}
	req := httptest.NewRequest(method, path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := e.app.Test(req, fiber.TestConfig{Timeout: 30 * time.Second})
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return resp{Status: res.StatusCode, Body: b, Header: res.Header}
}

func (e *env) expect(r resp, status int) map[string]any {
	e.t.Helper()
	if r.Status != status {
		e.t.Fatalf("status %d, want %d: %s", r.Status, status, r.Body)
	}
	if len(r.Body) == 0 || r.Body[0] != '{' {
		return nil
	}
	return r.json(e.t)
}

type user struct {
	ID      string
	Email   string
	Access  string
	Refresh string
}

func (e *env) register(name, country string) user {
	e.t.Helper()
	email := strings.ToLower(name) + "@example.com"
	body := map[string]any{"email": email, "password": "password123", "display_name": name}
	if country != "" {
		body["country_code"] = country
	}
	m := e.expect(e.do("POST", "/api/v1/auth/register", "", body), 201)
	return user{
		ID: m["user"].(map[string]any)["id"].(string), Email: email,
		Access: m["access_token"].(string), Refresh: m["refresh_token"].(string),
	}
}

// Lumphini Park loop (≈2.38 km per lap) walked one point per second at the given speed.
var loop = [][2]float64{{13.7280, 100.5385}, {13.7280, 100.5445}, {13.7330, 100.5445}, {13.7330, 100.5385}, {13.7280, 100.5385}}

func lumphini(laps float64, speed float64, start time.Time) [][]float64 {
	const mLat = 6_371_000.0 * math.Pi / 180
	mLng := mLat * math.Cos(13.73*math.Pi/180)
	var pts [][]float64
	t := start.UnixMilli()
	total, lapLen := 0.0, 0.0
	for i := 1; i < len(loop); i++ {
		lapLen += math.Hypot((loop[i][0]-loop[i-1][0])*mLat, (loop[i][1]-loop[i-1][1])*mLng)
	}
	goal := laps * lapLen
	for d := 0.0; d <= goal; d += speed {
		into := math.Mod(d, lapLen)
		for i := 1; i < len(loop); i++ {
			a, b := loop[i-1], loop[i]
			seg := math.Hypot((b[0]-a[0])*mLat, (b[1]-a[1])*mLng)
			if into <= seg {
				f := into / seg
				pts = append(pts, []float64{a[0] + (b[0]-a[0])*f, a[1] + (b[1]-a[1])*f, float64(t), 5, 5})
				break
			}
			into -= seg
		}
		t += 1000
		total = d
	}
	_ = total
	return pts
}

func pathDistance(pts [][]float64) float64 {
	const r = 6_371_000.0
	d := 0.0
	for i := 1; i < len(pts); i++ {
		la1, la2 := pts[i-1][0]*math.Pi/180, pts[i][0]*math.Pi/180
		dLa, dLo := la2-la1, (pts[i][1]-pts[i-1][1])*math.Pi/180
		h := math.Sin(dLa/2)*math.Sin(dLa/2) + math.Cos(la1)*math.Cos(la2)*math.Sin(dLo/2)*math.Sin(dLo/2)
		d += 2 * r * math.Asin(math.Sqrt(h))
	}
	return d
}

// gpsRun builds a PUT body for an honest GPS run.
func gpsRun(laps, speed float64, start time.Time) map[string]any {
	pts := lumphini(laps, speed, start)
	dur := int((int64(pts[len(pts)-1][2]) - int64(pts[0][2])) / 1000)
	return map[string]any{
		"title": "วิ่งเย็นริมสวน", "source": "gps",
		"started_at": start.Format(time.RFC3339), "ended_at": start.Add(time.Duration(dur) * time.Second).Format(time.RFC3339),
		"timezone": "Asia/Bangkok", "distance_m": pathDistance(pts), "moving_time_s": dur,
		"calories": 300, "elevation_gain_m": 4, "cadence_spm": 168, "temp_c": nil,
		"points": pts, "client_updated_at": start.Add(time.Hour).Format(time.RFC3339Nano),
	}
}

func manualRun(km float64, minutes int, start time.Time) map[string]any {
	return map[string]any{
		"title": "เพิ่มเอง", "source": "manual", "started_at": start.Format(time.RFC3339),
		"distance_m": km * 1000, "moving_time_s": minutes * 60, "client_updated_at": start.Format(time.RFC3339Nano),
	}
}

func (e *env) putRun(u user, body map[string]any) (string, map[string]any) {
	e.t.Helper()
	id := uuid.NewString()
	r := e.do("PUT", "/api/v1/runs/"+id, u.Access, body)
	if r.Status != 201 {
		e.t.Fatalf("put run: %d %s", r.Status, r.Body)
	}
	return id, r.json(e.t)
}

func (e *env) count(sql string, args ...any) int {
	e.t.Helper()
	var n int
	if err := testPool.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}
