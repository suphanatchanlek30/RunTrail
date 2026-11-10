package api

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"html/template"
	"math"
	"strings"
	"time"

	"github.com/gofiber/fiber/v3"
	"github.com/jackc/pgx/v5"

	"github.com/suphanatchanlek30/runtrail/backend/internal/auth"
	"github.com/suphanatchanlek30/runtrail/backend/internal/httpx"
	"github.com/suphanatchanlek30/runtrail/backend/internal/track"
)

// PrivacyRadiusM hides the area around start and finish (usually home) on public pages.
const PrivacyRadiusM = 200.0

type shareReq struct {
	HideEnds  httpx.Optional[bool] `json:"hide_ends"`
	ShowPhoto httpx.Optional[bool] `json:"show_photo"`
	ShowKcal  httpx.Optional[bool] `json:"show_kcal"`
}

// createShare makes (or updates the options of) the public link. Needs a connection, by nature.
func (s *Server) createShare(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	var r shareReq
	if len(c.BodyRaw()) > 0 {
		if err := httpx.Decode(c, &r); err != nil {
			return err
		}
	}
	ctx, uid := c.Context(), userID(c)
	var out *runJSON
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT 1 FROM runs WHERE id = $1 FOR UPDATE`, id); err != nil {
			return err
		}
		cur, err := s.ownRun(ctx, tx, uid, id, false)
		if err != nil {
			return err
		}
		hide, photo, kcal := true, true, false
		if cur.Share != nil {
			hide, photo, kcal = cur.Share.HideEnds, cur.Share.ShowPhoto, cur.Share.ShowKcal
		}
		for _, o := range []struct {
			opt *httpx.Optional[bool]
			dst *bool
		}{{&r.HideEnds, &hide}, {&r.ShowPhoto, &photo}, {&r.ShowKcal, &kcal}} {
			if o.opt.Set && !o.opt.Null {
				*o.dst = o.opt.Value
			}
		}
		code := ""
		if cur.Share != nil {
			code = cur.Share.Code
		}
		for attempt := 0; code == "" && attempt < 5; attempt++ {
			cand, err := auth.ShareCode()
			if err != nil {
				return err
			}
			var taken bool
			if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM runs WHERE share_code = $1)`, cand).Scan(&taken); err != nil {
				return err
			}
			if !taken {
				code = cand
			}
		}
		if code == "" {
			return fmt.Errorf("could not generate a unique share code")
		}
		if _, err := tx.Exec(ctx, `UPDATE runs SET share_code=$2, share_hide_ends=$3, share_show_photo=$4,
			share_show_kcal=$5, updated_at=$6 WHERE id=$1`, id, code, hide, photo, kcal, s.now()); err != nil {
			return err
		}
		out, err = s.ownRun(ctx, tx, uid, id, false)
		return err
	})
	if err != nil {
		return err
	}
	return c.JSON(out.Share)
}

func (s *Server) deleteShare(c fiber.Ctx) error {
	id, err := pathUUID(c, "id")
	if err != nil {
		return err
	}
	ctx, uid := c.Context(), userID(c)
	if _, err := s.ownRun(ctx, s.pool, uid, id, false); err != nil {
		return err
	}
	if _, err := s.pool.Exec(ctx, `UPDATE runs SET share_code = NULL, updated_at = $2 WHERE id = $1 AND share_code IS NOT NULL`,
		id, s.now()); err != nil {
		return err
	}
	return c.SendStatus(fiber.StatusNoContent)
}

type publicRunJSON struct {
	Code           string         `json:"code"`
	Title          string         `json:"title"`
	RunnerName     string         `json:"runner_name"`
	RunnerAvatar   *string        `json:"runner_avatar_url"`
	Source         string         `json:"source"`
	StartedAt      time.Time      `json:"started_at"`
	Timezone       *string        `json:"timezone"`
	DistanceM      float64        `json:"distance_m"`
	MovingTimeS    int            `json:"moving_time_s"`
	PaceSPerKm     *int           `json:"pace_s_per_km"`
	ElevationGainM *int           `json:"elevation_gain_m"`
	Calories       *float64       `json:"calories,omitempty"`
	PhotoURL       *string        `json:"photo_url,omitempty"`
	Verified       bool           `json:"verified"`
	HideEnds       bool           `json:"hide_ends"`
	Splits         []track.Split  `json:"splits"`
	Segments       [][][3]float64 `json:"segments"` // [[lat, lng, pace_s_per_km], ...] per visible piece
}

// loadPublic builds what strangers may see. Privacy is applied here, on the server,
// so hidden points never leave the database.
func (s *Server) loadPublic(ctx context.Context, code string) (*publicRunJSON, error) {
	p := &publicRunJSON{Code: code}
	var (
		clientDist float64
		serverDist *float64
		calories   *float64
		photoKey   *string
		avatarKey  *string
		showPhoto  bool
		showKcal   bool
		raw        []byte
		runID      string
	)
	err := s.pool.QueryRow(ctx, `SELECT r.id::text, r.title, u.display_name, u.avatar_key, r.source, r.started_at, r.timezone,
			r.client_distance_m, r.distance_m, r.moving_time_s, r.elevation_gain_m, r.calories, r.photo_key,
			r.verified, r.share_hide_ends, r.share_show_photo, r.share_show_kcal, t.points
		FROM runs r JOIN users u ON u.id = r.user_id LEFT JOIN run_tracks t ON t.run_id = r.id
		WHERE r.share_code = $1 AND r.deleted_at IS NULL AND u.deleted_at IS NULL`, code).Scan(
		&runID, &p.Title, &p.RunnerName, &avatarKey, &p.Source, &p.StartedAt, &p.Timezone,
		&clientDist, &serverDist, &p.MovingTimeS, &p.ElevationGainM, &calories, &photoKey,
		&p.Verified, &p.HideEnds, &showPhoto, &showKcal, &raw)
	if noRows(err) {
		return nil, httpx.NotFound("ไม่พบลิงก์นี้ หรือเจ้าของยกเลิกการแชร์แล้ว")
	}
	if err != nil {
		return nil, err
	}
	p.DistanceM = clientDist
	if serverDist != nil {
		p.DistanceM = *serverDist
	}
	if p.DistanceM > 0 {
		pace := int(math.Round(float64(p.MovingTimeS) / (p.DistanceM / 1000)))
		p.PaceSPerKm = &pace
	}
	if showKcal {
		p.Calories = calories
	}
	if showPhoto {
		p.PhotoURL = s.presign(ctx, photoKey)
	}
	p.RunnerAvatar = s.presign(ctx, avatarKey)

	rows, err := s.pool.Query(ctx, `SELECT km, pace_s, elev_delta_m FROM run_splits WHERE run_id = $1::uuid ORDER BY km`, runID)
	if err != nil {
		return nil, err
	}
	p.Splits, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (track.Split, error) {
		var sp track.Split
		return sp, row.Scan(&sp.Km, &sp.PaceS, &sp.ElevDeltaM)
	})
	if err != nil {
		return nil, err
	}
	if p.Splits == nil {
		p.Splits = []track.Split{}
	}

	p.Segments = [][][3]float64{}
	var points [][]float64
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &points); err != nil {
			return nil, err
		}
	}
	pts := make([]track.Point, 0, len(points))
	for _, v := range points {
		if len(v) == 5 {
			pts = append(pts, track.Point{Lat: v[0], Lng: v[1], T: int64(v[2]), Alt: v[3], Acc: v[4]})
		}
	}
	pts = track.Clean(pts)
	if len(pts) < 2 {
		return p, nil
	}
	fallback := 360
	if p.PaceSPerKm != nil {
		fallback = *p.PaceSPerKm
	}
	paces := track.Paces(pts, fallback)
	paceAt := make(map[int64]int, len(pts))
	for i, pt := range pts {
		paceAt[pt.T] = paces[i]
	}
	segs := [][]track.Point{pts}
	if p.HideEnds {
		segs = track.TrimEnds(pts, PrivacyRadiusM)
	}
	round := func(v float64) float64 { return math.Round(v*1e5) / 1e5 } // ~1 m, keeps the page small
	for _, seg := range segs {
		out := make([][3]float64, len(seg))
		for i, pt := range seg {
			out[i] = [3]float64{round(pt.Lat), round(pt.Lng), float64(paceAt[pt.T])}
		}
		p.Segments = append(p.Segments, out)
	}
	return p, nil
}

func (s *Server) publicRun(c fiber.Ctx) error {
	p, err := s.loadPublic(c.Context(), strings.ToUpper(c.Params("code")))
	if err != nil {
		return err
	}
	c.Set(fiber.HeaderCacheControl, "public, max-age=60")
	return c.JSON(p)
}

//go:embed templates/*.html
var templateFS embed.FS

var shareTmpl = template.Must(template.New("").Funcs(template.FuncMap{
	"km":       func(m float64) string { return fmt.Sprintf("%.2f", m/1000) },
	"pace":     fmtPace,
	"duration": fmtDuration,
	"kcal":     func(v float64) string { return fmt.Sprintf("%.0f", v) },
	"deref":    func(p *int) int { return *p },
	"derefF":   func(p *float64) float64 { return *p },
}).ParseFS(templateFS, "templates/*.html"))

func fmtPace(sec int) string {
	if sec <= 0 {
		return "--:--"
	}
	return fmt.Sprintf("%d:%02d", sec/60, sec%60)
}

func fmtDuration(sec int) string {
	if sec >= 3600 {
		return fmt.Sprintf("%d:%02d:%02d", sec/3600, sec%3600/60, sec%60)
	}
	return fmt.Sprintf("%d:%02d", sec/60, sec%60)
}

var thaiMonths = []string{"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."}

func thaiDate(t time.Time, tz *string) string {
	loc := time.UTC
	if tz != nil {
		if l, err := time.LoadLocation(*tz); err == nil {
			loc = l
		}
	}
	t = t.In(loc)
	return fmt.Sprintf("%d %s %d · %02d:%02d", t.Day(), thaiMonths[t.Month()-1], t.Year()+543, t.Hour(), t.Minute())
}

// sharePage renders the public web page with Leaflet. OG tags make LINE/Facebook previews look right.
func (s *Server) sharePage(c fiber.Ctx) error {
	code := strings.ToUpper(c.Params("code"))
	p, err := s.loadPublic(c.Context(), code)
	c.Type("html", "utf-8")
	if err != nil {
		if he, ok := err.(*httpx.Error); ok && he.Status == fiber.StatusNotFound {
			c.Status(fiber.StatusNotFound)
			return shareTmpl.ExecuteTemplate(c.Response().BodyWriter(), "notfound.html", nil)
		}
		return err
	}
	s.countShareView(c.Context(), code)
	// no-store so every open is counted (the owner gets "someone viewed your run")
	c.Set(fiber.HeaderCacheControl, "no-store")
	return shareTmpl.ExecuteTemplate(c.Response().BodyWriter(), "share.html", map[string]any{
		"Run": p, "Date": thaiDate(p.StartedAt, p.Timezone), "URL": s.shareURL(code),
	})
}
