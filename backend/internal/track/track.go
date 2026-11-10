// Package track recomputes run numbers from raw GPS points.
// The formulas are ported line-for-line from the app's domain/TrackMath.kt so both sides agree.
package track

import (
	"math"
	"sort"
)

const earthR = 6_371_000.0

// Point mirrors the app's TrackPoint. JSON form: [lat, lng, t_ms, alt, acc].
type Point struct {
	Lat, Lng float64
	T        int64 // epoch millis
	Alt      float64
	Acc      float64
}

type Split struct {
	Km         int `json:"km"`
	PaceS      int `json:"pace_s"`
	ElevDeltaM int `json:"elev_delta_m"`
}

// roundToInt matches Kotlin's roundToInt (half rounds up).
func roundToInt(x float64) int { return int(math.Floor(x + 0.5)) }

func rad(d float64) float64 { return d * math.Pi / 180 }

// Distance is the haversine distance in metres.
func Distance(lat1, lng1, lat2, lng2 float64) float64 {
	dLat := rad(lat2 - lat1)
	dLng := rad(lng2 - lng1)
	h := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(rad(lat1))*math.Cos(rad(lat2))*math.Sin(dLng/2)*math.Sin(dLng/2)
	h = math.Min(math.Max(h, 0), 1)
	return 2 * earthR * math.Asin(math.Sqrt(h))
}

func dist(a, b Point) float64 { return Distance(a.Lat, a.Lng, b.Lat, b.Lng) }

// Cumulative distance at each point.
func Cumulative(t []Point) []float64 {
	out := make([]float64, len(t))
	for i := 1; i < len(t); i++ {
		out[i] = out[i-1] + dist(t[i-1], t[i])
	}
	return out
}

// Same thresholds as the app's GpsFilter.
const (
	MaxAccuracyM = 25.0
	MaxSpeedMps  = 9.0
)

// Clean sorts by time and drops points the app's GpsFilter would never have kept:
// poor accuracy, duplicate/backwards timestamps and teleport jumps.
// The app's "moved less than accuracy" rule is skipped on purpose: it only removes
// standing-still noise and the app already applied it.
func Clean(in []Point) []Point {
	pts := append([]Point(nil), in...)
	sort.SliceStable(pts, func(i, j int) bool { return pts[i].T < pts[j].T })
	out := make([]Point, 0, len(pts))
	for _, p := range pts {
		if p.Acc > MaxAccuracyM {
			continue
		}
		if n := len(out); n > 0 {
			prev := out[n-1]
			dt := float64(p.T-prev.T) / 1000
			if dt <= 0 || dist(prev, p)/dt > MaxSpeedMps {
				continue
			}
		}
		out = append(out, p)
	}
	return out
}

// ElevationGain counts climbs only, ignoring wobble under 2 m.
func ElevationGain(t []Point) int {
	if len(t) == 0 {
		return 0
	}
	gain := 0.0
	ref := t[0].Alt
	for _, p := range t {
		d := p.Alt - ref
		if d >= 2 {
			gain += d
			ref = p.Alt
		} else if d <= -2 {
			ref = p.Alt
		}
	}
	return roundToInt(gain)
}

// Paces per point (s/km) averaged over ~60 m, clamped like the app.
func Paces(t []Point, fallback int) []int {
	cum := Cumulative(t)
	out := make([]int, len(t))
	for i := range t {
		lo, hi := i, i
		for lo > 0 && cum[i]-cum[lo] < 30 {
			lo--
		}
		for hi < len(t)-1 && cum[hi]-cum[i] < 30 {
			hi++
		}
		d := cum[hi] - cum[lo]
		sec := float64(t[hi].T-t[lo].T) / 1000
		if d < 5 || sec <= 0 {
			out[i] = fallback
		} else {
			out[i] = min(max(roundToInt(sec/(d/1000)), 150), 1200)
		}
	}
	return out
}

// Splits per kilometre, interpolating the time each km mark was passed.
func Splits(t []Point) []Split {
	if len(t) < 2 {
		return nil
	}
	cum := Cumulative(t)
	total := cum[len(cum)-1]
	if total < 50 {
		return nil
	}
	indexAt := func(d float64) int {
		for i, c := range cum {
			if c >= d {
				return i
			}
		}
		return len(cum) - 1
	}
	timeAt := func(d float64) float64 {
		i := indexAt(d)
		if i == 0 {
			return float64(t[0].T)
		}
		seg := cum[i] - cum[i-1]
		f := 1.0
		if seg > 0 {
			f = math.Min(math.Max((d-cum[i-1])/seg, 0), 1)
		}
		return float64(t[i-1].T) + float64(t[i].T-t[i-1].T)*f
	}
	var out []Split
	n := int(math.Ceil(total / 1000))
	for km := 1; km <= n; km++ {
		startD := float64(km-1) * 1000
		endD := math.Min(float64(km)*1000, total)
		distKm := (endD - startD) / 1000
		if distKm < 0.05 {
			continue
		}
		pace := roundToInt((timeAt(endD) - timeAt(startD)) / 1000 / distKm)
		elev := ElevationGain(t[indexAt(startD) : indexAt(endD)+1])
		out = append(out, Split{Km: km, PaceS: pace, ElevDeltaM: elev})
	}
	return out
}

// BestEffort is the fastest time (s) to cover `meters` anywhere in the run, or nil.
// The app's PR screen prorates average pace; the server uses a real sliding window
// so the leaderboard cannot be gamed by a fast run that is shorter than the distance.
func BestEffort(t []Point, meters float64) *int {
	cum := Cumulative(t)
	if len(t) < 2 || cum[len(cum)-1] < meters {
		return nil
	}
	best := math.Inf(1)
	j := 1
	for i := 0; i < len(t); i++ {
		target := cum[i] + meters
		if target > cum[len(cum)-1] {
			break
		}
		if j < i+1 {
			j = i + 1
		}
		for j < len(t)-1 && cum[j] < target {
			j++
		}
		seg := cum[j] - cum[j-1]
		f := 1.0
		if seg > 0 {
			f = (target - cum[j-1]) / seg
		}
		end := float64(t[j-1].T) + float64(t[j].T-t[j-1].T)*f
		if sec := (end - float64(t[i].T)) / 1000; sec < best {
			best = sec
		}
	}
	if math.IsInf(best, 1) {
		return nil
	}
	v := roundToInt(best)
	return &v
}

// BBox returns [minLat, minLng, maxLat, maxLng].
func BBox(t []Point) []float64 {
	if len(t) == 0 {
		return nil
	}
	b := []float64{t[0].Lat, t[0].Lng, t[0].Lat, t[0].Lng}
	for _, p := range t[1:] {
		b[0], b[1] = math.Min(b[0], p.Lat), math.Min(b[1], p.Lng)
		b[2], b[3] = math.Max(b[2], p.Lat), math.Max(b[3], p.Lng)
	}
	return b
}

// TrimEnds hides every point within radius metres of the start or the finish,
// so a public route never reveals where the runner lives. Gaps split the route into segments.
func TrimEnds(t []Point, radius float64) [][]Point {
	if len(t) == 0 {
		return [][]Point{}
	}
	start, end := t[0], t[len(t)-1]
	segs := [][]Point{}
	var cur []Point
	for _, p := range t {
		if dist(p, start) < radius || dist(p, end) < radius {
			if len(cur) >= 2 {
				segs = append(segs, cur)
			}
			cur = nil
			continue
		}
		cur = append(cur, p)
	}
	if len(cur) >= 2 {
		segs = append(segs, cur)
	}
	return segs
}
