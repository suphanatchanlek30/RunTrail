package track

import (
	"math"
	"testing"
	"time"
)

// metres per degree of latitude for R = 6,371 km (same constant as the app).
const mPerDegLat = earthR * math.Pi / 180

// northLine walks due north from Lumphini Park at a fixed speed, one point every dt seconds.
func northLine(n int, speed, dt float64) []Point {
	t0 := int64(1_762_772_530_000)
	out := make([]Point, n)
	for i := range out {
		out[i] = Point{
			Lat: 13.7290 + float64(i)*speed*dt/mPerDegLat, Lng: 100.5385,
			T: t0 + int64(float64(i)*dt*1000), Alt: 5, Acc: 5,
		}
	}
	return out
}

func near(t *testing.T, name string, got, want, tol float64) {
	t.Helper()
	if math.Abs(got-want) > tol {
		t.Fatalf("%s = %.4f, want %.4f ± %.4f", name, got, want, tol)
	}
}

func TestDistanceHaversine(t *testing.T) {
	near(t, "1° latitude", Distance(0, 0, 1, 0), 111_194.93, 0.01)
	near(t, "same point", Distance(13.7, 100.5, 13.7, 100.5), 0, 1e-9)
	// Lumphini Park west → east edge, checked against the app's formula by hand.
	near(t, "lumphini", Distance(13.7300, 100.5385, 13.7300, 100.5445), 648.3, 0.5)
}

func TestElevationGainIgnoresWobble(t *testing.T) {
	alts := []float64{10, 11, 10.5, 12, 15, 14, 13, 16, 15.5}
	pts := make([]Point, len(alts))
	for i, a := range alts {
		pts[i] = Point{Alt: a}
	}
	// 10→12 (+2), 12→15 (+3), drop to 13 (ref), 13→16 (+3) = 8
	if g := ElevationGain(pts); g != 8 {
		t.Fatalf("gain = %d, want 8", g)
	}
	if ElevationGain(nil) != 0 {
		t.Fatal("empty track should be 0")
	}
}

func TestSplitsSteadyPace(t *testing.T) {
	// 3 m/s for 2.5 km → pace 333 s/km in every split, last split 0.5 km.
	pts := northLine(834, 3, 1)
	s := Splits(pts)
	if len(s) != 3 {
		t.Fatalf("got %d splits, want 3", len(s))
	}
	for _, sp := range s {
		near(t, "pace", float64(sp.PaceS), 333, 1)
	}
	if s[2].Km != 3 {
		t.Fatalf("last split km = %d", s[2].Km)
	}
}

func TestSplitsTooShort(t *testing.T) {
	if Splits(northLine(10, 3, 1)) != nil {
		t.Fatal("< 50 m should give no splits")
	}
}

func TestPacesClampAndFallback(t *testing.T) {
	p := Paces(northLine(100, 3, 1), 360)
	near(t, "mid pace", float64(p[50]), 333, 1)
	// standing still → fallback
	still := northLine(20, 0, 1)
	if Paces(still, 360)[10] != 360 {
		t.Fatal("still runner should use fallback pace")
	}
	// walking 0.5 m/s = 2000 s/km → clamped to 1200
	if Paces(northLine(200, 0.5, 1), 360)[100] != 1200 {
		t.Fatal("slow pace should clamp to 1200")
	}
}

func TestCleanDropsBadPoints(t *testing.T) {
	pts := northLine(10, 3, 1)
	bad := append([]Point(nil), pts...)
	bad[3].Acc = 40                 // poor accuracy
	bad = append(bad, bad[5])       // duplicate timestamp
	bad[7].Lat += 0.01              // 1 km teleport
	bad[0], bad[1] = bad[1], bad[0] // out of order
	got := Clean(bad)
	if len(got) != 8 {
		t.Fatalf("kept %d points, want 8", len(got))
	}
	for i := 1; i < len(got); i++ {
		if got[i].T <= got[i-1].T {
			t.Fatal("not sorted by time")
		}
	}
}

func TestBestEffort(t *testing.T) {
	// 2 km at 4 m/s then 2 km at 2 m/s: best 1 km = 250 s, no 5 km.
	fast := northLine(501, 4, 1)
	last := fast[len(fast)-1]
	slow := northLine(1001, 2, 1)
	dLat, dT := last.Lat-slow[0].Lat, last.T-slow[0].T
	for i := range slow {
		slow[i].Lat += dLat
		slow[i].T += dT
	}
	pts := append(fast, slow[1:]...)
	b := BestEffort(pts, 1000)
	if b == nil || math.Abs(float64(*b)-250) > 1 {
		t.Fatalf("best 1k = %v, want 250", deref(b))
	}
	if BestEffort(pts, 5000) != nil {
		t.Fatal("4 km run has no 5 km effort")
	}
}

func TestTrimEndsHidesHome(t *testing.T) {
	pts := northLine(600, 3, 1) // 1.8 km
	segs := TrimEnds(pts, 200)
	if len(segs) != 1 {
		t.Fatalf("got %d segments", len(segs))
	}
	start, end := pts[0], pts[len(pts)-1]
	for _, p := range segs[0] {
		if Distance(p.Lat, p.Lng, start.Lat, start.Lng) < 200 || Distance(p.Lat, p.Lng, end.Lat, end.Lng) < 200 {
			t.Fatal("point inside the 200 m privacy zone")
		}
	}
	if len(TrimEnds(northLine(100, 3, 1), 200)) != 0 {
		t.Fatal("a 300 m run should be fully hidden")
	}
}

func TestVerify(t *testing.T) {
	now := time.UnixMilli(1_762_772_530_000).Add(time.Hour)
	start := time.UnixMilli(1_762_772_530_000)
	good := Summarize(northLine(1000, 3, 1), nil) // ~3 km in 999 s
	cases := []struct {
		name   string
		s      Summary
		client float64
		moving int
		start  time.Time
		reason string
	}{
		{"honest run", good, good.DistanceM, 999, start, ""},
		{"client inflates distance", good, good.DistanceM * 1.2, 999, start, "distance_mismatch"},
		{"too few points", Summarize(northLine(10, 3, 1), nil), 27, 9, start, "too_few_points"},
		{"car speed", Summarize(northLine(300, 8.5, 1), nil), 2541, 299, start, "too_fast"},
		{"future", good, good.DistanceM, 999, now.Add(time.Hour), "future_start"},
		{"sparse", Summarize(northLine(100, 3, 30), nil), 8910, 2970, start, "sparse_points"},
		{"moving time > track span", good, good.DistanceM, 5000, start, "moving_time_mismatch"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ok, reason := Verify(c.s, c.client, c.moving, c.start, now)
			if reason != c.reason || ok != (c.reason == "") {
				t.Fatalf("got (%v, %q), want %q", ok, reason, c.reason)
			}
		})
	}
}

func TestVerifyFastSegment(t *testing.T) {
	// Average pace looks fine (walk), but there is a 60 s stretch at 8 m/s (bike).
	walk := northLine(1500, 2.5, 1)
	bike := northLine(61, 8, 1)
	last := walk[len(walk)-1]
	dLat, dT := last.Lat-bike[0].Lat, last.T-bike[0].T
	pts := append([]Point(nil), walk...)
	for _, p := range bike[1:] {
		p.Lat += dLat
		p.T += dT
		pts = append(pts, p)
	}
	s := Summarize(pts, nil)
	start := time.UnixMilli(pts[0].T)
	ok, reason := Verify(s, s.DistanceM, int((pts[len(pts)-1].T-pts[0].T)/1000), start, start.Add(time.Hour))
	if ok || reason != "too_fast_segment" {
		t.Fatalf("got (%v, %q), want too_fast_segment", ok, reason)
	}
}

func deref(p *int) any {
	if p == nil {
		return nil
	}
	return *p
}

func TestPausedDistanceNotCounted(t *testing.T) {
	// 1 km at 3 m/s, pause 120 s while walking 300 m (no points recorded), then 1 km more.
	a := northLine(334, 3, 1)
	b := northLine(334, 3, 1)
	last := a[len(a)-1]
	dLat := last.Lat - b[0].Lat + 300/mPerDegLat
	dT := last.T - b[0].T + 120_000
	for i := range b {
		b[i].Lat += dLat
		b[i].T += dT
	}
	pts := append(append([]Point(nil), a...), b...)
	appDistance := 2 * 333 * 3.0 // the app stops counting while paused
	start := time.UnixMilli(pts[0].T)
	now := start.Add(time.Hour)
	moving := 666

	ok, reason := Verify(Summarize(pts, nil), appDistance, moving, start, now)
	if ok || reason != "distance_mismatch" {
		t.Fatalf("without pauses: (%v, %q), want distance_mismatch", ok, reason)
	}
	s := Summarize(pts, []Pause{{Start: last.T + 500, End: b[0].T - 500}})
	near(t, "distance", s.DistanceM, appDistance, 5)
	if ok, reason := Verify(s, appDistance, moving, start, now); !ok {
		t.Fatalf("honest paused run rejected: %s", reason)
	}
}
