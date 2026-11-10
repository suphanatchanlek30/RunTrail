package api

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"

	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
)

type lbRow struct {
	Rank        int       `json:"rank"`
	UserID      uuid.UUID `json:"user_id"`
	DisplayName string    `json:"display_name"`
	AvatarURL   *string   `json:"avatar_url"`
	CountryCode *string   `json:"country_code"`
	Value       float64   `json:"value"` // metres for distance, seconds for best_5k / best_10k
	IsMe        bool      `json:"is_me"`
	avatarKey   *string
}

type lbQuery struct {
	Metric, Period, Scope, Country string
	From, To                       time.Time
}

func (q lbQuery) key() string {
	return strings.Join([]string{q.Metric, q.Period, q.Scope, q.Country, q.From.Format(time.RFC3339)}, "|")
}

// lbCache keeps whole ranked lists for a short time; any write clears it.
type lbCache struct {
	ttl  time.Duration
	mu   sync.Mutex
	data map[string]lbEntry
}

type lbEntry struct {
	at   time.Time
	rows []lbRow
}

func newLBCache(ttl time.Duration) *lbCache { return &lbCache{ttl: ttl, data: map[string]lbEntry{}} }

func (c *lbCache) clear() {
	c.mu.Lock()
	c.data = map[string]lbEntry{}
	c.mu.Unlock()
}

// periodRange uses ISO weeks and calendar months in UTC so everyone in the world shares one boundary.
func periodRange(period string, now time.Time) (time.Time, time.Time) {
	now = now.UTC()
	day := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
	switch period {
	case "week":
		offset := (int(day.Weekday()) + 6) % 7 // Monday = 0
		start := day.AddDate(0, 0, -offset)
		return start, start.AddDate(0, 0, 7)
	case "month":
		start := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
		return start, start.AddDate(0, 1, 0)
	default:
		return time.Time{}, time.Time{}
	}
}

func (s *Server) parseLB(c fiber.Ctx) (lbQuery, error) {
	q := lbQuery{Metric: c.Query("metric", "distance"), Period: c.Query("period", "week"), Scope: c.Query("scope", "global")}
	switch q.Metric {
	case "distance", "best_5k", "best_10k":
	default:
		return q, httpx.Field("metric", "oneof", "metric ต้องเป็น distance, best_5k หรือ best_10k")
	}
	switch q.Period {
	case "week", "month", "all":
	default:
		return q, httpx.Field("period", "oneof", "period ต้องเป็น week, month หรือ all")
	}
	switch q.Scope {
	case "global":
	case "country":
		q.Country = strings.ToUpper(c.Query("country"))
		if q.Country == "" {
			var cc *string
			if err := s.pool.QueryRow(c.Context(), `SELECT country_code FROM users WHERE id = $1`, userID(c)).Scan(&cc); err != nil {
				return q, err
			}
			if cc == nil {
				return q, httpx.Field("country", "required", "ตั้งประเทศในโปรไฟล์ก่อน หรือส่ง country=TH")
			}
			q.Country = *cc
		}
		if len(q.Country) != 2 {
			return q, httpx.Field("country", "iso3166", "รหัสประเทศ 2 ตัวอักษร")
		}
	default:
		return q, httpx.Field("scope", "oneof", "scope ต้องเป็น global หรือ country")
	}
	q.From, q.To = periodRange(q.Period, s.now())
	return q, nil
}

func (s *Server) ranked(ctx context.Context, q lbQuery) ([]lbRow, error) {
	k := q.key()
	if s.lb.ttl > 0 {
		s.lb.mu.Lock()
		e, ok := s.lb.data[k]
		s.lb.mu.Unlock()
		if ok && s.now().Sub(e.at) < s.lb.ttl {
			return e.rows, nil
		}
	}
	agg, order, extra := "sum(r.distance_m)", "DESC", ""
	switch q.Metric {
	case "best_5k":
		agg, order, extra = "min(r.best_5k_s)", "ASC", "AND r.best_5k_s IS NOT NULL"
	case "best_10k":
		agg, order, extra = "min(r.best_10k_s)", "ASC", "AND r.best_10k_s IS NOT NULL"
	}
	args := []any{}
	where := ""
	if !q.From.IsZero() {
		args = append(args, q.From, q.To)
		where += " AND r.started_at >= $1 AND r.started_at < $2"
	}
	if q.Country != "" {
		args = append(args, q.Country)
		where += fmt.Sprintf(" AND u.country_code = $%d", len(args))
	}
	// Only GPS runs that passed the server's checks, from live accounts that opted in.
	sql := fmt.Sprintf(`WITH agg AS (
			SELECT r.user_id, %s::double precision AS value
			FROM runs r JOIN users u ON u.id = r.user_id
			WHERE r.verified AND r.source = 'gps' AND r.deleted_at IS NULL
				AND u.deleted_at IS NULL AND u.leaderboard_opt_in %s %s
			GROUP BY r.user_id)
		SELECT RANK() OVER (ORDER BY agg.value %s), agg.user_id, u.display_name, u.country_code, u.avatar_key, agg.value
		FROM agg JOIN users u ON u.id = agg.user_id
		ORDER BY agg.value %s, u.created_at, u.id`, agg, extra, where, order, order)
	rows, err := s.pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []lbRow
	for rows.Next() {
		var r lbRow
		if err := rows.Scan(&r.Rank, &r.UserID, &r.DisplayName, &r.CountryCode, &r.avatarKey, &r.Value); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if s.lb.ttl > 0 {
		s.lb.mu.Lock()
		s.lb.data[k] = lbEntry{at: s.now(), rows: out}
		s.lb.mu.Unlock()
	}
	return out, nil
}

// decorate copies rows for one response, adding avatar URLs and the "me" flag.
func (s *Server) decorate(ctx context.Context, rows []lbRow, me uuid.UUID) []lbRow {
	out := make([]lbRow, len(rows))
	for i, r := range rows {
		r.AvatarURL = s.presign(ctx, r.avatarKey)
		r.IsMe = r.UserID == me
		out[i] = r
	}
	return out
}

func timeOrNil(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

func (s *Server) leaderboard(c fiber.Ctx) error {
	q, err := s.parseLB(c)
	if err != nil {
		return err
	}
	limit := 50
	if v := c.Query("limit"); v != "" {
		if limit, err = strconv.Atoi(v); err != nil || limit < 1 || limit > 100 {
			return httpx.Field("limit", "range", "limit ต้องอยู่ระหว่าง 1–100")
		}
	}
	ctx, uid := c.Context(), userID(c)
	rows, err := s.ranked(ctx, q)
	if err != nil {
		return err
	}
	var me *lbRow
	for i := range rows {
		if rows[i].UserID == uid {
			m := s.decorate(ctx, rows[i:i+1], uid)[0]
			me = &m
			break
		}
	}
	top := s.decorate(ctx, rows[:min(limit, len(rows))], uid)
	return c.JSON(fiber.Map{
		"metric": q.Metric, "period": q.Period, "scope": q.Scope, "country": nilIfEmpty(q.Country),
		"period_start": timeOrNil(q.From), "period_end": timeOrNil(q.To),
		"total": len(rows), "entries": top, "me": me,
	})
}

// leaderboardMe returns my position plus the runner just ahead and just behind.
func (s *Server) leaderboardMe(c fiber.Ctx) error {
	q, err := s.parseLB(c)
	if err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	rows, err := s.ranked(ctx, q)
	if err != nil {
		return err
	}
	resp := fiber.Map{"metric": q.Metric, "period": q.Period, "scope": q.Scope, "total": len(rows),
		"period_start": timeOrNil(q.From), "period_end": timeOrNil(q.To), "me": nil, "ahead": nil, "behind": nil}
	for i := range rows {
		if rows[i].UserID != uid {
			continue
		}
		resp["me"] = s.decorate(ctx, rows[i:i+1], uid)[0]
		if i > 0 {
			resp["ahead"] = s.decorate(ctx, rows[i-1:i], uid)[0]
		}
		if i+1 < len(rows) {
			resp["behind"] = s.decorate(ctx, rows[i+1:i+2], uid)[0]
		}
		break
	}
	return c.JSON(resp)
}

func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
