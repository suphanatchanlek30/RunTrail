package api

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
)

type userJSON struct {
	ID               uuid.UUID `json:"id"`
	Email            string    `json:"email"`
	DisplayName      string    `json:"display_name"`
	FirstName        *string   `json:"first_name"`
	LastName         *string   `json:"last_name"`
	CountryCode      *string   `json:"country_code"`
	AvatarURL        *string   `json:"avatar_url"`
	WeightKg         *int16    `json:"weight_kg"`
	HeightCm         *int16    `json:"height_cm"`
	BirthYear        *int16    `json:"birth_year"`
	UseMiles         bool      `json:"use_miles"`
	WeeklyGoalKm     int16     `json:"weekly_goal_km"`
	TargetPaceS      int16     `json:"target_pace_s"`
	LeaderboardOptIn bool      `json:"leaderboard_opt_in"`
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
	avatarKey        *string
}

func (s *Server) loadUser(ctx context.Context, q querier, uid uuid.UUID) (*userJSON, error) {
	u := &userJSON{}
	err := q.QueryRow(ctx, `SELECT id, email, display_name, first_name, last_name, country_code, avatar_key,
			weight_kg, height_cm, birth_year, use_miles, weekly_goal_km, target_pace_s, leaderboard_opt_in,
			created_at, updated_at
		FROM users WHERE id = $1 AND deleted_at IS NULL`, uid).Scan(
		&u.ID, &u.Email, &u.DisplayName, &u.FirstName, &u.LastName, &u.CountryCode, &u.avatarKey,
		&u.WeightKg, &u.HeightCm, &u.BirthYear, &u.UseMiles, &u.WeeklyGoalKm, &u.TargetPaceS, &u.LeaderboardOptIn,
		&u.CreatedAt, &u.UpdatedAt)
	if err != nil {
		return nil, err
	}
	u.AvatarURL = s.presign(ctx, u.avatarKey)
	return u, nil
}

func (s *Server) getMe(c fiber.Ctx) error {
	u, err := s.loadUser(c.Context(), s.pool, userID(c))
	if err != nil {
		return err
	}
	return c.JSON(u)
}

type patchMeReq struct {
	DisplayName      httpx.Optional[string] `json:"display_name"`
	FirstName        httpx.Optional[string] `json:"first_name"`
	LastName         httpx.Optional[string] `json:"last_name"`
	CountryCode      httpx.Optional[string] `json:"country_code"`
	WeightKg         httpx.Optional[int]    `json:"weight_kg"`
	HeightCm         httpx.Optional[int]    `json:"height_cm"`
	BirthYear        httpx.Optional[int]    `json:"birth_year"`
	UseMiles         httpx.Optional[bool]   `json:"use_miles"`
	WeeklyGoalKm     httpx.Optional[int]    `json:"weekly_goal_km"`
	TargetPaceS      httpx.Optional[int]    `json:"target_pace_s"`
	LeaderboardOptIn httpx.Optional[bool]   `json:"leaderboard_opt_in"`
}

// patchMe updates only the fields that were sent; null clears optional fields.
func (s *Server) patchMe(c fiber.Ctx) error {
	var r patchMeReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	sets := []string{}
	args := []any{userID(c)}
	add := func(col string, v any) {
		args = append(args, v)
		sets = append(sets, fmt.Sprintf("%s = $%d", col, len(args)))
	}
	// text: trimmed, length-limited; required ones may not be null
	text := func(field, col string, o httpx.Optional[string], maxLen int, required bool) error {
		if !o.Set {
			return nil
		}
		if o.Null {
			if required {
				return httpx.Field(field, "required", "ห้ามเว้นว่าง")
			}
			add(col, nil)
			return nil
		}
		v := strings.TrimSpace(o.Value)
		if (required && v == "") || len([]rune(v)) > maxLen {
			return httpx.Field(field, "length", fmt.Sprintf("ต้องยาว 1–%d ตัวอักษร", maxLen))
		}
		if v == "" {
			add(col, nil)
		} else {
			add(col, v)
		}
		return nil
	}
	num := func(field, col string, o httpx.Optional[int], lo, hi int, nullable bool) error {
		if !o.Set {
			return nil
		}
		if o.Null {
			if !nullable {
				return httpx.Field(field, "required", "ห้ามเว้นว่าง")
			}
			add(col, nil)
			return nil
		}
		if o.Value < lo || o.Value > hi {
			return httpx.Field(field, "range", fmt.Sprintf("ต้องอยู่ระหว่าง %d–%d", lo, hi))
		}
		add(col, o.Value)
		return nil
	}
	boolean := func(field, col string, o httpx.Optional[bool]) error {
		if !o.Set {
			return nil
		}
		if o.Null {
			return httpx.Field(field, "required", "ห้ามเว้นว่าง")
		}
		add(col, o.Value)
		return nil
	}
	if r.CountryCode.Set && !r.CountryCode.Null {
		cc := strings.ToUpper(strings.TrimSpace(r.CountryCode.Value))
		if len(cc) != 2 || cc[0] < 'A' || cc[0] > 'Z' || cc[1] < 'A' || cc[1] > 'Z' {
			return httpx.Field("country_code", "iso3166", "รหัสประเทศ 2 ตัวอักษร เช่น TH")
		}
		r.CountryCode.Value = cc
	}
	year := s.now().Year()
	for _, err := range []error{
		text("display_name", "display_name", r.DisplayName, 40, true),
		text("first_name", "first_name", r.FirstName, 60, false),
		text("last_name", "last_name", r.LastName, 60, false),
		text("country_code", "country_code", r.CountryCode, 2, false),
		num("weight_kg", "weight_kg", r.WeightKg, 20, 300, true),
		num("height_cm", "height_cm", r.HeightCm, 80, 250, true),
		num("birth_year", "birth_year", r.BirthYear, 1900, year, true),
		boolean("use_miles", "use_miles", r.UseMiles),
		num("weekly_goal_km", "weekly_goal_km", r.WeeklyGoalKm, 1, 500, false),
		num("target_pace_s", "target_pace_s", r.TargetPaceS, 120, 1800, false),
		boolean("leaderboard_opt_in", "leaderboard_opt_in", r.LeaderboardOptIn),
	} {
		if err != nil {
			return err
		}
	}
	ctx := c.Context()
	if len(sets) > 0 {
		add("updated_at", s.now())
		if _, err := s.pool.Exec(ctx, "UPDATE users SET "+strings.Join(sets, ", ")+" WHERE id = $1", args...); err != nil {
			return err
		}
		s.lb.clear()
	}
	u, err := s.loadUser(ctx, s.pool, userID(c))
	if err != nil {
		return err
	}
	return c.JSON(u)
}

// deleteMe soft-deletes the account, frees the email for re-use and removes photos.
func (s *Server) deleteMe(c fiber.Ctx) error {
	ctx, uid := c.Context(), userID(c)
	var keys []string
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT photo_key FROM runs WHERE user_id = $1 AND photo_key IS NOT NULL
			UNION ALL SELECT avatar_key FROM users WHERE id = $1 AND avatar_key IS NOT NULL
			UNION ALL SELECT key FROM photo_uploads WHERE user_id = $1`, uid)
		if err != nil {
			return err
		}
		if keys, err = pgx.CollectRows(rows, pgx.RowTo[string]); err != nil {
			return err
		}
		now := s.now()
		stmts := []struct {
			sql  string
			args []any
		}{
			{`UPDATE users SET deleted_at = $2, updated_at = $2, email = 'deleted+' || id || '@runtrail.invalid',
				avatar_key = NULL, first_name = NULL, last_name = NULL, display_name = 'บัญชีที่ถูกลบ' WHERE id = $1`, []any{uid, now}},
			{`UPDATE refresh_tokens SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL`, []any{uid, now}},
			{`UPDATE runs SET deleted_at = $2, updated_at = $2, share_code = NULL, photo_key = NULL
				WHERE user_id = $1 AND deleted_at IS NULL`, []any{uid, now}},
			{`DELETE FROM run_tracks WHERE run_id IN (SELECT id FROM runs WHERE user_id = $1)`, []any{uid}},
			{`DELETE FROM photo_uploads WHERE user_id = $1`, []any{uid}},
			{`DELETE FROM notifications WHERE user_id = $1`, []any{uid}},
		}
		for _, st := range stmts {
			if _, err := tx.Exec(ctx, st.sql, st.args...); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	s.lb.clear()
	s.deleteObjects(keys)
	return c.SendStatus(fiber.StatusNoContent)
}

// deleteObjects removes S3 objects in the background; a failure only leaves an orphan file.
func (s *Server) deleteObjects(keys []string) {
	if len(keys) == 0 {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
		defer cancel()
		for _, k := range keys {
			if err := s.store.Delete(ctx, k); err != nil {
				slog.Warn("delete object failed", "key", k, "err", err)
			}
		}
	}()
}

type monthKm struct {
	Month string  `json:"month"` // YYYY-MM
	Km    float64 `json:"km"`
}

// myStats mirrors the Records screen: totals, PRs, longest run and the last 6 months.
func (s *Server) myStats(c fiber.Ctx) error {
	ctx, uid := c.Context(), userID(c)
	tz := c.Query("tz", "UTC")
	if _, err := time.LoadLocation(tz); err != nil {
		return httpx.Field("tz", "timezone", "timezone ไม่ถูกต้อง เช่น Asia/Bangkok")
	}
	var out struct {
		RunCount      int       `json:"run_count"`
		TotalDistance float64   `json:"total_distance_m"`
		TotalTimeS    int64     `json:"total_time_s"`
		TotalCalories float64   `json:"total_calories"`
		Best1kS       *int      `json:"best_1k_s"`
		Best5kS       *int      `json:"best_5k_s"`
		Best10kS      *int      `json:"best_10k_s"`
		LongestM      *float64  `json:"longest_m"`
		Monthly       []monthKm `json:"monthly"`
	}
	err := s.pool.QueryRow(ctx, `SELECT count(*), coalesce(sum(coalesce(distance_m, client_distance_m)),0),
			coalesce(sum(moving_time_s),0), coalesce(sum(calories),0),
			min(best_1k_s), min(best_5k_s), min(best_10k_s), max(coalesce(distance_m, client_distance_m))
		FROM runs WHERE user_id = $1 AND deleted_at IS NULL`, uid).Scan(
		&out.RunCount, &out.TotalDistance, &out.TotalTimeS, &out.TotalCalories,
		&out.Best1kS, &out.Best5kS, &out.Best10kS, &out.LongestM)
	if err != nil {
		return err
	}
	rows, err := s.pool.Query(ctx, `WITH months AS (
			SELECT to_char(date_trunc('month', (now() AT TIME ZONE $2)) - make_interval(months => g), 'YYYY-MM') AS m, g
			FROM generate_series(0, 5) g)
		SELECT months.m, coalesce(sum(coalesce(r.distance_m, r.client_distance_m)), 0) / 1000
		FROM months LEFT JOIN runs r ON r.user_id = $1 AND r.deleted_at IS NULL
			AND to_char(r.started_at AT TIME ZONE $2, 'YYYY-MM') = months.m
		GROUP BY months.m, months.g ORDER BY months.g DESC`, uid, tz)
	if err != nil {
		return err
	}
	out.Monthly, err = pgx.CollectRows(rows, func(r pgx.CollectableRow) (monthKm, error) {
		var m monthKm
		return m, r.Scan(&m.Month, &m.Km)
	})
	if err != nil {
		return err
	}
	return c.JSON(out)
}
