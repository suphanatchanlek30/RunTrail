package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
	"github.com/suphanatchanlek30/runtrail/backend/internal/track"
)

const MaxPoints = 50_000

// SyncOverlap is how far server_time is moved back so incremental sync never misses a late commit.
const SyncOverlap = time.Minute

type shareJSON struct {
	Code      string `json:"code"`
	URL       string `json:"url"`
	HideEnds  bool   `json:"hide_ends"`
	ShowPhoto bool   `json:"show_photo"`
	ShowKcal  bool   `json:"show_kcal"`
}

type runJSON struct {
	ID              uuid.UUID     `json:"id"`
	Title           string        `json:"title"`
	Note            *string       `json:"note"`
	Source          string        `json:"source"`
	StartedAt       time.Time     `json:"started_at"`
	EndedAt         *time.Time    `json:"ended_at"`
	Timezone        *string       `json:"timezone"`
	DistanceM       float64       `json:"distance_m"`        // what the app measured
	ServerDistanceM *float64      `json:"server_distance_m"` // recomputed from points (gps only)
	MovingTimeS     int           `json:"moving_time_s"`
	Calories        *float64      `json:"calories"`
	ElevationGainM  *int          `json:"elevation_gain_m"`
	CadenceSpm      *int          `json:"cadence_spm"`
	TempC           *int16        `json:"temp_c"`
	Verified        bool          `json:"verified"`
	VerifyReason    *string       `json:"verify_reason"`
	Best1kS         *int          `json:"best_1k_s"`
	Best5kS         *int          `json:"best_5k_s"`
	Best10kS        *int          `json:"best_10k_s"`
	PhotoURL        *string       `json:"photo_url"`
	Share           *shareJSON    `json:"share"`
	ClientUpdatedAt time.Time     `json:"client_updated_at"`
	UpdatedAt       time.Time     `json:"updated_at"`
	Splits          []track.Split `json:"splits,omitempty"`

	userID   uuid.UUID
	photoKey *string
	deleted  bool
}

const runCols = `r.id, r.title, r.note, r.source, r.started_at, r.ended_at, r.timezone, r.client_distance_m,
	r.distance_m, r.moving_time_s, r.calories, r.elevation_gain_m, r.cadence_spm, r.temp_c, r.verified,
	r.verify_reason, r.best_1k_s, r.best_5k_s, r.best_10k_s, r.photo_key, r.share_code, r.share_hide_ends,
	r.share_show_photo, r.share_show_kcal, r.client_updated_at, r.updated_at, r.deleted_at IS NOT NULL, r.user_id`

func (s *Server) scanRun(ctx context.Context, row pgx.Row) (*runJSON, error) {
	r := &runJSON{}
	var code *string
	var hide, photo, kcal bool
	err := row.Scan(&r.ID, &r.Title, &r.Note, &r.Source, &r.StartedAt, &r.EndedAt, &r.Timezone, &r.DistanceM,
		&r.ServerDistanceM, &r.MovingTimeS, &r.Calories, &r.ElevationGainM, &r.CadenceSpm, &r.TempC, &r.Verified,
		&r.VerifyReason, &r.Best1kS, &r.Best5kS, &r.Best10kS, &r.photoKey, &code, &hide,
		&photo, &kcal, &r.ClientUpdatedAt, &r.UpdatedAt, &r.deleted, &r.userID)
	if err != nil {
		return nil, err
	}
	if code != nil {
		r.Share = &shareJSON{Code: *code, URL: s.shareURL(*code), HideEnds: hide, ShowPhoto: photo, ShowKcal: kcal}
	}
	r.PhotoURL = s.presign(ctx, r.photoKey)
	return r, nil
}

func (s *Server) shareURL(code string) string {
	return strings.TrimRight(s.cfg.PublicBaseURL, "/") + "/s/" + code
}

// ownRun loads a run that belongs to uid and is not deleted, else 404 (never reveals other users' runs).
func (s *Server) ownRun(ctx context.Context, q querier, uid, id uuid.UUID, withSplits bool) (*runJSON, error) {
	r, err := s.scanRun(ctx, q.QueryRow(ctx, `SELECT `+runCols+` FROM runs r WHERE r.id = $1`, id))
	if noRows(err) || (err == nil && (r.userID != uid || r.deleted)) {
		return nil, httpx.NotFound("ไม่พบการวิ่งนี้")
	}
	if err != nil {
		return nil, err
	}
	if withSplits {
		rows, err := q.Query(ctx, `SELECT km, pace_s, elev_delta_m FROM run_splits WHERE run_id = $1 ORDER BY km`, id)
		if err != nil {
			return nil, err
		}
		r.Splits, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (track.Split, error) {
			var sp track.Split
			return sp, row.Scan(&sp.Km, &sp.PaceS, &sp.ElevDeltaM)
		})
		if err != nil {
			return nil, err
		}
		if r.Splits == nil {
			r.Splits = []track.Split{}
		}
	}
	return r, nil
}

type putRunReq struct {
	Title           string      `json:"title" validate:"required,max=80"`
	Note            *string     `json:"note" validate:"omitempty,max=2000"`
	Source          string      `json:"source" validate:"required,oneof=gps manual"`
	StartedAt       time.Time   `json:"started_at" validate:"required"`
	EndedAt         *time.Time  `json:"ended_at"`
	Timezone        *string     `json:"timezone" validate:"omitempty,max=64"`
	DistanceM       float64     `json:"distance_m" validate:"gte=0,lte=1000000"`
	MovingTimeS     int         `json:"moving_time_s" validate:"gte=0,lte=604800"`
	Calories        *float64    `json:"calories" validate:"omitempty,gte=0,lte=100000"`
	ElevationGainM  *int        `json:"elevation_gain_m" validate:"omitempty,gte=0,lte=20000"`
	CadenceSpm      *int        `json:"cadence_spm" validate:"omitempty,gte=0,lte=300"`
	TempC           *int        `json:"temp_c" validate:"omitempty,gte=-60,lte=70"`
	Points          [][]float64 `json:"points" validate:"max=50000,dive,len=5"`
	Pauses          [][]int64   `json:"pauses" validate:"max=2000,dive,len=2"` // [[start_ms, end_ms], ...]
	ClientUpdatedAt time.Time   `json:"client_updated_at" validate:"required"`
}

func (r *putRunReq) check() ([]track.Point, error) {
	r.Title = strings.TrimSpace(r.Title)
	if r.Title == "" {
		return nil, httpx.Field("title", "required", "กรุณาใส่ชื่อการวิ่ง")
	}
	if r.EndedAt != nil && r.EndedAt.Before(r.StartedAt) {
		return nil, httpx.Field("ended_at", "gtefield", "เวลาจบต้องไม่ก่อนเวลาเริ่ม")
	}
	if r.Timezone != nil {
		if _, err := time.LoadLocation(*r.Timezone); err != nil || *r.Timezone == "" {
			return nil, httpx.Field("timezone", "timezone", "timezone ไม่ถูกต้อง เช่น Asia/Bangkok")
		}
	}
	if r.Source == "manual" && len(r.Points) > 0 {
		return nil, httpx.Field("points", "empty", "การวิ่งที่เพิ่มเองต้องไม่มีจุด GPS")
	}
	// Postgres keeps microseconds; compare at that precision so a retry counts as "same version".
	r.ClientUpdatedAt = r.ClientUpdatedAt.Truncate(time.Microsecond)
	pts := make([]track.Point, len(r.Points))
	for i, p := range r.Points {
		lat, lng, t := p[0], p[1], p[2]
		if lat < -90 || lat > 90 || lng < -180 || lng > 180 || t <= 0 || math.IsNaN(lat+lng+t+p[3]+p[4]) {
			return nil, httpx.Field(fmt.Sprintf("points[%d]", i), "range", "พิกัดหรือเวลาไม่ถูกต้อง")
		}
		pts[i] = track.Point{Lat: lat, Lng: lng, T: int64(t), Alt: p[3], Acc: p[4]}
	}
	for i, p := range r.Pauses {
		if p[0] <= 0 || p[1] < p[0] {
			return nil, httpx.Field(fmt.Sprintf("pauses[%d]", i), "range", "ช่วงหยุดไม่ถูกต้อง")
		}
	}
	return pts, nil
}

func (r *putRunReq) pauses() []track.Pause {
	out := make([]track.Pause, len(r.Pauses))
	for i, p := range r.Pauses {
		out[i] = track.Pause{Start: p[0], End: p[1]}
	}
	return out
}

// putRun is an idempotent upsert keyed by the UUID the app generated offline.
// Re-sending the same run never creates a second row; an older client_updated_at never wins.
func (s *Server) putRun(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	var r putRunReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	pts, err := r.check()
	if err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)

	var sum track.Summary
	verified, reason := false, "manual"
	if r.Source == "gps" {
		sum = track.Summarize(pts, r.pauses())
		verified, reason = track.Verify(sum, r.DistanceM, r.MovingTimeS, r.StartedAt, s.now())
	}
	rawPoints, err := json.Marshal(nonNil(r.Points))
	if err != nil {
		return err
	}
	rawPauses, err := json.Marshal(nonNilPauses(r.Pauses))
	if err != nil {
		return err
	}

	created := false
	var out *runJSON
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var owner uuid.UUID
		var serverVer time.Time
		var deleted bool
		err := tx.QueryRow(ctx, `SELECT user_id, client_updated_at, deleted_at IS NOT NULL FROM runs WHERE id = $1 FOR UPDATE`, id).
			Scan(&owner, &serverVer, &deleted)
		exists := err == nil
		if err != nil && !noRows(err) {
			return err
		}
		if exists {
			switch {
			case owner != uid:
				return httpx.E(fiber.StatusConflict, "run_id_taken", "รหัสการวิ่งนี้ถูกใช้แล้ว")
			case deleted:
				return httpx.E(fiber.StatusGone, "run_deleted", "การวิ่งนี้ถูกลบแล้ว")
			case !r.ClientUpdatedAt.After(serverVer):
				out, err = s.ownRun(ctx, tx, uid, id, true) // server copy is same or newer
				return err
			}
		} else {
			// ON CONFLICT covers two first uploads racing; the loser is told to retry.
			tag, err := tx.Exec(ctx, `INSERT INTO runs (id, user_id, title, source, started_at, client_distance_m,
				moving_time_s, client_updated_at, created_at, updated_at)
				VALUES ($1,$2,'',$3,$4,0,0,$5,$6,$6) ON CONFLICT (id) DO NOTHING`,
				id, uid, r.Source, r.StartedAt, r.ClientUpdatedAt, s.now())
			if err != nil {
				return err
			}
			if tag.RowsAffected() == 0 {
				return httpx.E(fiber.StatusConflict, "concurrent_upload", "กำลังบันทึกการวิ่งนี้อยู่ ลองใหม่อีกครั้ง")
			}
			created = true
		}

		var dist *float64
		if r.Source == "gps" {
			dist = &sum.DistanceM
		}
		var reasonPtr *string
		if reason != "" {
			reasonPtr = &reason
		}
		if _, err := tx.Exec(ctx, `UPDATE runs SET title=$3, note=$4, source=$5, started_at=$6, ended_at=$7, timezone=$8,
				client_distance_m=$9, moving_time_s=$10, calories=$11, elevation_gain_m=$12, cadence_spm=$13, temp_c=$14,
				distance_m=$15, verified=$16, verify_reason=$17, best_1k_s=$18, best_5k_s=$19, best_10k_s=$20,
				client_updated_at=$21, updated_at=$22
			WHERE id=$1 AND user_id=$2`,
			id, uid, r.Title, r.Note, r.Source, r.StartedAt, r.EndedAt, r.Timezone,
			r.DistanceM, r.MovingTimeS, r.Calories, r.ElevationGainM, r.CadenceSpm, r.TempC,
			dist, verified, reasonPtr, sum.Best1k, sum.Best5k, sum.Best10k,
			r.ClientUpdatedAt, s.now()); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM run_splits WHERE run_id = $1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO run_tracks (run_id, points, point_count, bbox, pauses) VALUES ($1,$2,$3,$4,$5)
			ON CONFLICT (run_id) DO UPDATE SET points=EXCLUDED.points, point_count=EXCLUDED.point_count,
				bbox=EXCLUDED.bbox, pauses=EXCLUDED.pauses`,
			id, rawPoints, len(r.Points), track.BBox(sum.Points), rawPauses); err != nil {
			return err
		}
		for _, sp := range sum.Splits {
			if _, err := tx.Exec(ctx, `INSERT INTO run_splits (run_id, km, pace_s, elev_delta_m) VALUES ($1,$2,$3,$4)`,
				id, sp.Km, sp.PaceS, sp.ElevDeltaM); err != nil {
				return err
			}
		}
		out, err = s.ownRun(ctx, tx, uid, id, true)
		return err
	})
	if err != nil {
		return err
	}
	s.lb.clear()
	if created {
		s.afterRunCreated(ctx, uid, out)
		return c.Status(fiber.StatusCreated).JSON(out)
	}
	return c.JSON(out)
}

func nonNilPauses(p [][]int64) [][]int64 {
	if p == nil {
		return [][]int64{}
	}
	return p
}

func nonNil(p [][]float64) [][]float64 {
	if p == nil {
		return [][]float64{}
	}
	return p
}

type patchRunReq struct {
	Title           httpx.Optional[string]    `json:"title"`
	Note            httpx.Optional[string]    `json:"note"`
	StartedAt       httpx.Optional[time.Time] `json:"started_at"`
	Timezone        httpx.Optional[string]    `json:"timezone"`
	ClientUpdatedAt time.Time                 `json:"client_updated_at" validate:"required"`
}

// patchRun edits what the Edit screen can change, without re-sending GPS points.
func (s *Server) patchRun(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	var r patchRunReq
	if err := httpx.Decode(c, &r); err != nil {
		return err
	}
	r.ClientUpdatedAt = r.ClientUpdatedAt.Truncate(time.Microsecond)
	ctx, uid := c.Context(), userID(c)
	var out *runJSON
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		// lock first so the last-write-wins comparison sees the latest version
		if _, err := tx.Exec(ctx, `SELECT 1 FROM runs WHERE id = $1 FOR UPDATE`, id); err != nil {
			return err
		}
		cur, err := s.ownRun(ctx, tx, uid, id, false)
		if err != nil {
			return err
		}
		if !r.ClientUpdatedAt.After(cur.ClientUpdatedAt) {
			out, err = s.ownRun(ctx, tx, uid, id, true)
			return err
		}
		title, note, started, tz := cur.Title, cur.Note, cur.StartedAt, cur.Timezone
		if r.Title.Set {
			t := ""
			if !r.Title.Null {
				t = strings.TrimSpace(r.Title.Value)
			}
			if t == "" || len([]rune(t)) > 80 {
				return httpx.Field("title", "length", "ชื่อต้องยาว 1–80 ตัวอักษร")
			}
			title = t
		}
		if r.Note.Set {
			if r.Note.Null || strings.TrimSpace(r.Note.Value) == "" {
				note = nil
			} else if v := strings.TrimSpace(r.Note.Value); len([]rune(v)) <= 2000 {
				note = &v
			} else {
				return httpx.Field("note", "max", "โน้ตยาวเกิน 2000 ตัวอักษร")
			}
		}
		if r.StartedAt.Set {
			// GPS runs carry their own timestamps; moving them would let a run jump into another leaderboard week.
			if cur.Source == "gps" {
				return httpx.Field("started_at", "gps_locked", "แก้เวลาเริ่มของการวิ่งที่บันทึกด้วย GPS ไม่ได้")
			}
			if r.StartedAt.Null || r.StartedAt.Value.IsZero() {
				return httpx.Field("started_at", "required", "ต้องระบุเวลาเริ่ม")
			}
			started = r.StartedAt.Value
		}
		if r.Timezone.Set {
			if r.Timezone.Null {
				tz = nil
			} else if _, err := time.LoadLocation(r.Timezone.Value); err == nil && r.Timezone.Value != "" {
				tz = &r.Timezone.Value
			} else {
				return httpx.Field("timezone", "timezone", "timezone ไม่ถูกต้อง")
			}
		}
		var ended *time.Time
		if cur.EndedAt != nil {
			e := started.Add(cur.EndedAt.Sub(cur.StartedAt))
			ended = &e
		}
		if _, err := tx.Exec(ctx, `UPDATE runs SET title=$2, note=$3, started_at=$4, ended_at=$5, timezone=$6,
				client_updated_at=$7, updated_at=$8 WHERE id=$1`,
			id, title, note, started, ended, tz, r.ClientUpdatedAt, s.now()); err != nil {
			return err
		}
		out, err = s.ownRun(ctx, tx, uid, id, true)
		return err
	})
	if err != nil {
		return err
	}
	s.lb.clear()
	return c.JSON(out)
}

// deleteRun soft-deletes so other devices learn about it through GET /runs?updated_since.
// Deleting twice, or a run the server never saw, is still 204.
func (s *Server) deleteRun(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	var owner uuid.UUID
	var photo *string
	err = s.pool.QueryRow(ctx, `SELECT user_id, photo_key FROM runs WHERE id = $1`, id).Scan(&owner, &photo)
	if noRows(err) {
		return c.SendStatus(fiber.StatusNoContent)
	}
	if err != nil {
		return err
	}
	if owner != uid {
		return httpx.NotFound("ไม่พบการวิ่งนี้")
	}
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE runs SET deleted_at=$2, updated_at=$2, share_code=NULL, photo_key=NULL
			WHERE id=$1 AND deleted_at IS NULL`, id, s.now())
		if err != nil || tag.RowsAffected() == 0 {
			return err
		}
		_, err = tx.Exec(ctx, `DELETE FROM run_tracks WHERE run_id = $1`, id)
		return err
	})
	if err != nil {
		return err
	}
	if photo != nil {
		s.deleteObjects([]string{*photo})
	}
	s.lb.clear()
	return c.SendStatus(fiber.StatusNoContent)
}

func (s *Server) getRun(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	r, err := s.ownRun(c.Context(), s.pool, userID(c), id, true)
	if err != nil {
		return err
	}
	return c.JSON(r)
}

func (s *Server) getTrack(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	ctx := c.Context()
	if _, err := s.ownRun(ctx, s.pool, userID(c), id, false); err != nil {
		return err
	}
	var pts, pauses json.RawMessage
	var count int
	var bbox []float64
	err = s.pool.QueryRow(ctx, `SELECT points, point_count, bbox, pauses FROM run_tracks WHERE run_id = $1`, id).
		Scan(&pts, &count, &bbox, &pauses)
	if noRows(err) {
		pts, pauses, count = json.RawMessage("[]"), json.RawMessage("[]"), 0
	} else if err != nil {
		return err
	}
	return c.JSON(fiber.Map{"run_id": id, "point_count": count, "bbox": bbox, "points": pts, "pauses": pauses})
}

type deletedJSON struct {
	ID        uuid.UUID `json:"id"`
	Deleted   bool      `json:"deleted"`
	UpdatedAt time.Time `json:"updated_at"`
}

// listRuns pages through runs ordered by (updated_at, id) so sync never skips or repeats a row.
// With updated_since it also returns tombstones of deleted runs.
func (s *Server) listRuns(c fiber.Ctx) error {
	ctx, uid := c.Context(), userID(c)
	limit := 50
	if v := c.Query("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 100 {
			return httpx.Field("limit", "range", "limit ต้องอยู่ระหว่าง 1–100")
		}
		limit = n
	}
	includeDeleted := false
	after, afterID := time.Time{}, uuid.Nil
	if v := c.Query("updated_since"); v != "" {
		t, err := time.Parse(time.RFC3339Nano, v)
		if err != nil {
			return httpx.Field("updated_since", "rfc3339", "รูปแบบเวลาไม่ถูกต้อง (RFC 3339)")
		}
		after, includeDeleted = t, true
	}
	if v := c.Query("cursor"); v != "" {
		t, id, err := decodeCursor(v)
		if err != nil {
			return httpx.Field("cursor", "invalid", "cursor ไม่ถูกต้อง")
		}
		// keep sending the same updated_since together with the cursor
		after, afterID = t, id
	}
	// A write stamped just before this query may commit after it, so the next updated_since
	// starts a minute earlier. Clients may see a run twice and merge by UUID (last-write-wins).
	serverTime := s.now().Add(-SyncOverlap)
	rows, err := s.pool.Query(ctx, `SELECT `+runCols+` FROM runs r
		WHERE r.user_id = $1 AND (r.updated_at, r.id) > ($2, $3) AND ($4 OR r.deleted_at IS NULL)
		ORDER BY r.updated_at, r.id LIMIT $5`, uid, after, afterID, includeDeleted, limit+1)
	if err != nil {
		return err
	}
	var runs []*runJSON
	for rows.Next() {
		r, err := s.scanRun(ctx, rows)
		if err != nil {
			rows.Close()
			return err
		}
		runs = append(runs, r)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	var next *string
	if len(runs) > limit {
		runs = runs[:limit]
		last := runs[len(runs)-1]
		cur := encodeCursor(last.UpdatedAt, last.ID)
		next = &cur
	}
	items := make([]any, 0, len(runs))
	for _, r := range runs {
		if r.deleted {
			items = append(items, deletedJSON{ID: r.ID, Deleted: true, UpdatedAt: r.UpdatedAt})
		} else {
			items = append(items, r)
		}
	}
	return c.JSON(fiber.Map{"items": items, "next_cursor": next, "server_time": serverTime})
}

func encodeCursor(t time.Time, id uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString([]byte(t.UTC().Format(time.RFC3339Nano) + "|" + id.String()))
}

func decodeCursor(v string) (time.Time, uuid.UUID, error) {
	b, err := base64.RawURLEncoding.DecodeString(v)
	if err != nil {
		return time.Time{}, uuid.Nil, err
	}
	ts, idStr, ok := strings.Cut(string(b), "|")
	if !ok {
		return time.Time{}, uuid.Nil, fmt.Errorf("bad cursor")
	}
	t, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		return time.Time{}, uuid.Nil, err
	}
	id, err := uuid.Parse(idStr)
	return t, id, err
}
