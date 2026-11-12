package api

import (
	"testing"
	"time"
)

// Monthly buckets in /me/stats must follow the server clock (Deps.Now), not the database's now().
// Audit B2: with the clock in March 2025 the newest bucket used to be the real current month.
func TestStatsMonthlyFollowsServerClock(t *testing.T) {
	e := newEnv(t)
	u := e.register("Clock", "")
	march := time.Date(2025, 3, 15, 10, 0, 0, 0, time.UTC)
	e.clock.Add(march.Sub(e.clock.Now()))

	e.putRun(u, manualRun(5, 30, march.Add(-5*24*time.Hour)))
	s := e.expect(e.do("GET", "/api/v1/me/stats?tz=UTC", u.Access, nil), 200)
	monthly := s["monthly"].([]any)
	last := monthly[len(monthly)-1].(map[string]any)
	if last["month"] != "2025-03" || last["km"].(float64) != 5 {
		t.Fatalf("newest bucket should be 2025-03 with 5 km, got %v", monthly)
	}
	if first := monthly[0].(map[string]any); first["month"] != "2024-10" {
		t.Fatalf("oldest bucket should be 2024-10, got %v", first)
	}
}
