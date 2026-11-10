package track

import (
	"math"
	"sort"
	"time"
)

// Summary is everything the server derives from a GPS track.
type Summary struct {
	Points        []Point
	DistanceM     float64
	ElevationGain int
	Splits        []Split
	Best1k        *int
	Best5k        *int
	Best10k       *int
}

func Summarize(raw []Point) Summary {
	pts := Clean(raw)
	s := Summary{Points: pts}
	if len(pts) < 2 {
		return s
	}
	cum := Cumulative(pts)
	s.DistanceM = cum[len(cum)-1]
	s.ElevationGain = ElevationGain(pts)
	s.Splits = Splits(pts)
	s.Best1k = BestEffort(pts, 1000)
	s.Best5k = BestEffort(pts, 5000)
	s.Best10k = BestEffort(pts, 10000)
	return s
}

// Verification thresholds (plan 2.4).
const (
	MinPoints         = 30
	MaxDistanceDiff   = 0.05  // 5 % between app and server distance
	MinAvgPaceSPerKm  = 170.0 // 2:50 /km
	WindowS           = 30.0
	MaxMedianInterval = 10.0 // seconds between points
	FutureSlack       = 5 * time.Minute
	// Point-to-point jumps over 9 m/s are already dropped by Clean, so the sustained
	// check needs a lower bar: 7 m/s for 30 s is faster than a 5 km world record pace.
	MaxWindowSpeedMps = 7.0
)

// Verify decides whether a GPS run may enter the leaderboard. reason is "" when it passes.
func Verify(s Summary, clientDistanceM float64, movingTimeS int, startedAt, now time.Time) (bool, string) {
	pts := s.Points
	switch {
	case startedAt.After(now.Add(FutureSlack)):
		return false, "future_start"
	case len(pts) < MinPoints:
		return false, "too_few_points"
	case s.DistanceM < 1:
		return false, "no_distance"
	}
	if medianInterval(pts) > MaxMedianInterval {
		return false, "sparse_points"
	}
	if math.Abs(s.DistanceM-clientDistanceM)/s.DistanceM > MaxDistanceDiff {
		return false, "distance_mismatch"
	}
	span := float64(pts[len(pts)-1].T-pts[0].T) / 1000
	if float64(movingTimeS) > span+60 {
		return false, "moving_time_mismatch"
	}
	if movingTimeS <= 0 || float64(movingTimeS)/(s.DistanceM/1000) < MinAvgPaceSPerKm {
		return false, "too_fast"
	}
	if fastestWindow(pts) > MaxWindowSpeedMps {
		return false, "too_fast_segment"
	}
	return true, ""
}

func medianInterval(pts []Point) float64 {
	if len(pts) < 2 {
		return math.Inf(1)
	}
	d := make([]float64, len(pts)-1)
	for i := 1; i < len(pts); i++ {
		d[i-1] = float64(pts[i].T-pts[i-1].T) / 1000
	}
	sort.Float64s(d)
	return d[len(d)/2]
}

// fastestWindow is the top speed (m/s) sustained over any 30 s stretch.
func fastestWindow(pts []Point) float64 {
	cum := Cumulative(pts)
	top := 0.0
	j := 0
	for i := range pts {
		if j < i {
			j = i
		}
		for j < len(pts)-1 && float64(pts[j].T-pts[i].T)/1000 < WindowS {
			j++
		}
		sec := float64(pts[j].T-pts[i].T) / 1000
		if sec < WindowS {
			break
		}
		if v := (cum[j] - cum[i]) / sec; v > top {
			top = v
		}
	}
	return top
}
