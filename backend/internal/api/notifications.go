package api

import (
	"context"
	"encoding/base64"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
)

// Notification types (the app picks an icon per type).
const (
	NotifRunVerified   = "run_verified"
	NotifRunNotCounted = "run_not_counted"
	NotifPersonalBest  = "personal_record"
	NotifWeeklyGoal    = "weekly_goal"
	NotifRankPassed    = "rank_passed"
	NotifShareViewed   = "share_viewed"
)

// shareViewMilestones: notify the owner only at these view counts, not on every view.
var shareViewMilestones = map[int]bool{1: true, 10: true, 50: true, 100: true, 500: true, 1000: true}

type notificationJSON struct {
	ID        uuid.UUID  `json:"id"`
	Type      string     `json:"type"`
	Title     string     `json:"title"`
	Body      string     `json:"body"`
	RunID     *uuid.UUID `json:"run_id"`
	CreatedAt time.Time  `json:"created_at"`
	Read      bool       `json:"read"`
}

func (s *Server) notify(ctx context.Context, q querier, uid uuid.UUID, typ, title, body string, runID *uuid.UUID) {
	if _, err := q.Exec(ctx, `INSERT INTO notifications (user_id, type, title, body, run_id, created_at) VALUES ($1,$2,$3,$4,$5,$6)`,
		uid, typ, title, body, runID, s.now()); err != nil {
		slog.Warn("notify failed", "type", typ, "err", err)
	}
}

func km(m float64) string { return fmt.Sprintf("%.2f กม.", m/1000) }

// verifyReasonThai mirrors RunJson.verifyReasonText in the app.
func verifyReasonThai(r string) string {
	switch r {
	case "too_few_points":
		return "จุด GPS น้อยเกินไป"
	case "sparse_points":
		return "สัญญาณ GPS ขาดเป็นช่วง ๆ"
	case "distance_mismatch":
		return "ระยะจาก GPS ไม่ตรงกับที่บันทึก"
	case "moving_time_mismatch":
		return "เวลาไม่ตรงกับเส้นทาง"
	case "too_fast":
		return "เพซเร็วผิดปกติ"
	case "too_fast_segment":
		return "มีช่วงที่เร็วผิดปกติ (เช่น ขึ้นรถ)"
	case "future_start":
		return "เวลาเริ่มอยู่ในอนาคต"
	case "no_distance":
		return "ไม่มีระยะทาง"
	default:
		return r
	}
}

// afterRunCreated turns a newly uploaded run into notifications. Best effort: a failure here
// never fails the upload (the run is already committed).
func (s *Server) afterRunCreated(ctx context.Context, uid uuid.UUID, r *runJSON) {
	id := r.ID
	title := r.Title
	dist := r.DistanceM
	if r.ServerDistanceM != nil {
		dist = *r.ServerDistanceM
	}

	if r.Source == "gps" {
		if r.Verified {
			s.notify(ctx, s.pool, uid, NotifRunVerified, "นับในอันดับโลกแล้ว", fmt.Sprintf("%s · %s", title, km(dist)), &id)
		} else if r.VerifyReason != nil {
			s.notify(ctx, s.pool, uid, NotifRunNotCounted, "การวิ่งนี้ไม่นับในอันดับ",
				fmt.Sprintf("%s · %s", title, verifyReasonThai(*r.VerifyReason)), &id)
		}
	}

	if r.Verified {
		s.personalBests(ctx, uid, r)
	}
	s.weeklyGoal(ctx, uid, r, dist)
	if r.Verified && r.Source == "gps" {
		s.rankPassed(ctx, uid, r, dist)
	}
}

func (s *Server) personalBests(ctx context.Context, uid uuid.UUID, r *runJSON) {
	checks := []struct {
		col, label string
		val        *int
	}{{"best_1k_s", "1 กม.", r.Best1kS}, {"best_5k_s", "5 กม.", r.Best5kS}, {"best_10k_s", "10 กม.", r.Best10kS}}
	for _, c := range checks {
		if c.val == nil {
			continue
		}
		var prev *int
		// column name comes from the fixed list above, never from input
		if err := s.pool.QueryRow(ctx, `SELECT min(`+c.col+`) FROM runs WHERE user_id = $1 AND id <> $2
			AND deleted_at IS NULL AND verified`, uid, r.ID).Scan(&prev); err != nil {
			slog.Warn("personal best query", "err", err)
			continue
		}
		// the first run is not a "new record" yet — avoids three notifications on day one
		if prev != nil && *c.val < *prev {
			id := r.ID
			s.notify(ctx, s.pool, uid, NotifPersonalBest, "สถิติใหม่ "+c.label+"!",
				fmt.Sprintf("%s เร็วขึ้น %s จากสถิติเดิม %s", fmtDuration(*c.val), fmtDuration(*prev-*c.val), fmtDuration(*prev)), &id)
		}
	}
}

func (s *Server) weeklyGoal(ctx context.Context, uid uuid.UUID, r *runJSON, dist float64) {
	from, to := periodRange("week", s.now())
	if r.StartedAt.Before(from) || !r.StartedAt.Before(to) {
		return
	}
	var goalKm int
	var total float64
	err := s.pool.QueryRow(ctx, `SELECT u.weekly_goal_km, coalesce((SELECT sum(coalesce(distance_m, client_distance_m))
			FROM runs WHERE user_id = u.id AND deleted_at IS NULL AND started_at >= $2 AND started_at < $3), 0)
		FROM users u WHERE u.id = $1`, uid, from, to).Scan(&goalKm, &total)
	if err != nil {
		slog.Warn("weekly goal query", "err", err)
		return
	}
	goal := float64(goalKm) * 1000
	if goal > 0 && total-dist < goal && total >= goal {
		id := r.ID
		s.notify(ctx, s.pool, uid, NotifWeeklyGoal, "ถึงเป้าหมายสัปดาห์นี้แล้ว 🎉",
			fmt.Sprintf("วิ่งรวม %s จากเป้า %d กม.", km(total), goalKm), &id)
	}
}

// rankPassed tells runners this upload just overtook in this week's distance ranking.
func (s *Server) rankPassed(ctx context.Context, uid uuid.UUID, r *runJSON, dist float64) {
	from, to := periodRange("week", s.now())
	if r.StartedAt.Before(from) || !r.StartedAt.Before(to) {
		return
	}
	var name string
	var optIn bool
	if err := s.pool.QueryRow(ctx, `SELECT display_name, leaderboard_opt_in FROM users WHERE id = $1`, uid).Scan(&name, &optIn); err != nil || !optIn {
		return
	}
	rows, err := s.pool.Query(ctx, `WITH totals AS (
			SELECT r.user_id, sum(r.distance_m) AS total FROM runs r JOIN users u ON u.id = r.user_id
			WHERE r.verified AND r.source = 'gps' AND r.deleted_at IS NULL AND u.deleted_at IS NULL AND u.leaderboard_opt_in
				AND r.started_at >= $2 AND r.started_at < $3
			GROUP BY r.user_id)
		SELECT o.user_id, o.total, me.total FROM totals o, totals me
		WHERE me.user_id = $1 AND o.user_id <> $1 AND o.total > me.total - $4 AND o.total < me.total
		ORDER BY o.total DESC LIMIT 20`, uid, from, to, dist)
	if err != nil {
		slog.Warn("rank passed query", "err", err)
		return
	}
	type passed struct {
		user         uuid.UUID
		theirs, mine float64
	}
	var list []passed
	for rows.Next() {
		var p passed
		if err := rows.Scan(&p.user, &p.theirs, &p.mine); err == nil {
			list = append(list, p)
		}
	}
	rows.Close()
	for _, p := range list {
		s.notify(ctx, s.pool, p.user, NotifRankPassed, "มีคนแซงคุณในอันดับสัปดาห์นี้",
			fmt.Sprintf("%s วิ่งรวม %s แซงคุณ (%s)", name, km(p.mine), km(p.theirs)), nil)
	}
}

// countShareView is called when the public page is opened.
func (s *Server) countShareView(ctx context.Context, code string) {
	var views int
	var owner, runID uuid.UUID
	var title string
	err := s.pool.QueryRow(ctx, `UPDATE runs SET share_views = share_views + 1 WHERE share_code = $1 AND deleted_at IS NULL
		RETURNING share_views, user_id, id, title`, code).Scan(&views, &owner, &runID, &title)
	if err != nil {
		if !noRows(err) {
			slog.Warn("count share view", "err", err)
		}
		return
	}
	if shareViewMilestones[views] {
		body := fmt.Sprintf("มีคนเปิดดู \"%s\" แล้ว %d ครั้ง", title, views)
		if views == 1 {
			body = fmt.Sprintf("มีคนเปิดดูลิงก์ \"%s\" เป็นครั้งแรก", title)
		}
		s.notify(ctx, s.pool, owner, NotifShareViewed, "มีคนดูการวิ่งของคุณ", body, &runID)
	}
}

// ---------------------------------------------------------------- API

// listNotifications: newest first, paged with an opaque cursor.
func (s *Server) listNotifications(c fiber.Ctx) error {
	ctx, uid := c.Context(), userID(c)
	limit := 50
	if v := c.Query("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 100 {
			return httpx.Field("limit", "range", "limit ต้องอยู่ระหว่าง 1–100")
		}
		limit = n
	}
	before, beforeID := s.now().Add(time.Hour), uuid.Max
	if v := c.Query("cursor"); v != "" {
		b, err := base64.RawURLEncoding.DecodeString(v)
		ts, idStr, ok := strings.Cut(string(b), "|")
		t, err2 := time.Parse(time.RFC3339Nano, ts)
		id, err3 := uuid.Parse(idStr)
		if err != nil || !ok || err2 != nil || err3 != nil {
			return httpx.Field("cursor", "invalid", "cursor ไม่ถูกต้อง")
		}
		before, beforeID = t, id
	}
	rows, err := s.pool.Query(ctx, `SELECT id, type, title, body, run_id, created_at, read_at IS NOT NULL
		FROM notifications WHERE user_id = $1 AND (created_at, id) < ($2, $3)
		ORDER BY created_at DESC, id DESC LIMIT $4`, uid, before, beforeID, limit+1)
	if err != nil {
		return err
	}
	items, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (notificationJSON, error) {
		var n notificationJSON
		return n, r.Scan(&n.ID, &n.Type, &n.Title, &n.Body, &n.RunID, &n.CreatedAt, &n.Read)
	})
	if err != nil {
		return err
	}
	var next *string
	if len(items) > limit {
		items = items[:limit]
		last := items[len(items)-1]
		cur := base64.RawURLEncoding.EncodeToString([]byte(last.CreatedAt.UTC().Format(time.RFC3339Nano) + "|" + last.ID.String()))
		next = &cur
	}
	if items == nil {
		items = []notificationJSON{}
	}
	var unread int
	if err := s.pool.QueryRow(ctx, `SELECT count(*) FROM notifications WHERE user_id = $1 AND read_at IS NULL`, uid).Scan(&unread); err != nil {
		return err
	}
	return c.JSON(fiber.Map{"items": items, "unread_count": unread, "next_cursor": next})
}

type readReq struct {
	IDs []uuid.UUID `json:"ids" validate:"max=200"`
	All bool        `json:"all"`
}

// markRead marks the given notifications (or all) as read. Only the caller's own rows change.
func (s *Server) markRead(c fiber.Ctx) error {
	var r readReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	var err error
	if r.All {
		_, err = s.pool.Exec(ctx, `UPDATE notifications SET read_at = $2 WHERE user_id = $1 AND read_at IS NULL`, uid, s.now())
	} else if len(r.IDs) > 0 {
		_, err = s.pool.Exec(ctx, `UPDATE notifications SET read_at = $3 WHERE user_id = $1 AND id = ANY($2) AND read_at IS NULL`,
			uid, r.IDs, s.now())
	} else {
		return httpx.BadRequest("validation_failed", "ส่ง ids หรือ all: true")
	}
	if err != nil {
		return err
	}
	return c.SendStatus(fiber.StatusNoContent)
}

// userSummary is what you see when tapping a runner on the leaderboard (only for opted-in runners).
func (s *Server) userSummary(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	ctx := c.Context()
	week0, week1 := periodRange("week", s.now())
	month0, month1 := periodRange("month", s.now())
	var out struct {
		ID          uuid.UUID `json:"id"`
		DisplayName string    `json:"display_name"`
		CountryCode *string   `json:"country_code"`
		AvatarURL   *string   `json:"avatar_url"`
		MemberSince time.Time `json:"member_since"`
		RunCount    int       `json:"run_count"`
		WeekM       float64   `json:"week_distance_m"`
		MonthM      float64   `json:"month_distance_m"`
		TotalM      float64   `json:"total_distance_m"`
		Best5k      *int      `json:"best_5k_s"`
		Best10k     *int      `json:"best_10k_s"`
		IsMe        bool      `json:"is_me"`
	}
	var avatar *string
	var optIn bool
	err = s.pool.QueryRow(ctx, `SELECT u.id, u.display_name, u.country_code, u.avatar_key, u.created_at, u.leaderboard_opt_in,
			count(r.id),
			coalesce(sum(r.distance_m) FILTER (WHERE r.started_at >= $2 AND r.started_at < $3), 0),
			coalesce(sum(r.distance_m) FILTER (WHERE r.started_at >= $4 AND r.started_at < $5), 0),
			coalesce(sum(r.distance_m), 0), min(r.best_5k_s), min(r.best_10k_s)
		FROM users u LEFT JOIN runs r ON r.user_id = u.id AND r.verified AND r.source = 'gps' AND r.deleted_at IS NULL
		WHERE u.id = $1 AND u.deleted_at IS NULL
		GROUP BY u.id`, id, week0, week1, month0, month1).Scan(
		&out.ID, &out.DisplayName, &out.CountryCode, &avatar, &out.MemberSince, &optIn,
		&out.RunCount, &out.WeekM, &out.MonthM, &out.TotalM, &out.Best5k, &out.Best10k)
	out.IsMe = id == userID(c)
	if noRows(err) || (err == nil && !optIn && !out.IsMe) {
		return httpx.NotFound("ไม่พบนักวิ่งคนนี้ หรือเขาไม่ได้เปิดเผยในอันดับ")
	}
	if err != nil {
		return err
	}
	out.AvatarURL = s.presign(ctx, avatar)
	return c.JSON(out)
}
