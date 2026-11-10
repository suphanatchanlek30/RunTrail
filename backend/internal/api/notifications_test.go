package api

import (
	"testing"
	"time"
)

func (e *env) notifications(u user) []map[string]any {
	e.t.Helper()
	m := e.expect(e.do("GET", "/api/v1/notifications", u.Access, nil), 200)
	out := []map[string]any{}
	for _, it := range m["items"].([]any) {
		out = append(out, it.(map[string]any))
	}
	return out
}

func types(ns []map[string]any) map[string]int {
	t := map[string]int{}
	for _, n := range ns {
		t[n["type"].(string)]++
	}
	return t
}

func TestNotificationsFromRuns(t *testing.T) {
	e := newEnv(t)
	u := e.register("Noti", "TH")
	e.expect(e.do("PATCH", "/api/v1/me", u.Access, map[string]any{"weekly_goal_km": 5}), 200)

	// 1st run: verified, ~2.4 km — no PR yet (first run), goal not reached
	id1, _ := e.putRun(u, gpsRun(1, 3, runStart))
	got := types(e.notifications(u))
	if got[NotifRunVerified] != 1 || got[NotifPersonalBest] != 0 || got[NotifWeeklyGoal] != 0 {
		t.Fatalf("after first run: %v", got)
	}

	// 2nd run: faster 1 km → new PR · pushes the week over 5 km → goal reached
	e.putRun(u, gpsRun(1.2, 3.5, runStart.Add(3*time.Hour)))
	got = types(e.notifications(u))
	if got[NotifPersonalBest] != 1 || got[NotifWeeklyGoal] != 1 || got[NotifRunVerified] != 2 {
		t.Fatalf("after second run: %v", got)
	}

	// re-uploading the same run (sync retry) must not notify again
	e.do("PUT", "/api/v1/runs/"+id1, u.Access, gpsRun(1, 3, runStart))
	if n := len(e.notifications(u)); n != 4 {
		t.Fatalf("retry created notifications: %d", n)
	}

	// a run that fails verification says why
	e.clock.Add(time.Minute)
	e.putRun(u, gpsRun(2, 6.5, runStart.Add(5*time.Hour)))
	ns := e.notifications(u)
	if ns[0]["type"] != NotifRunNotCounted || ns[0]["body"] == nil {
		t.Fatalf("newest should be not-counted: %v", ns[0])
	}
}

func TestRankPassedNotification(t *testing.T) {
	e := newEnv(t)
	a, b := e.register("Ahead", "TH"), e.register("Chaser", "TH")
	e.putRun(a, gpsRun(1, 3, runStart))   // A: ~2.4 km
	e.putRun(b, gpsRun(0.5, 3, runStart)) // B: ~1.2 km, behind A
	if types(e.notifications(a))[NotifRankPassed] != 0 {
		t.Fatal("nobody passed A yet")
	}
	e.putRun(b, gpsRun(1, 3, runStart.Add(2*time.Hour))) // B: ~3.6 km, passes A
	ns := e.notifications(a)
	if types(ns)[NotifRankPassed] != 1 {
		t.Fatalf("A should be told: %v", types(ns))
	}
	if types(e.notifications(b))[NotifRankPassed] != 0 {
		t.Fatal("the chaser is not notified about itself")
	}
}

func TestShareViewNotificationAndReadFlow(t *testing.T) {
	e := newEnv(t)
	u := e.register("Viewed", "")
	id, _ := e.putRun(u, gpsRun(1, 3, runStart))
	code := e.expect(e.do("POST", "/api/v1/runs/"+id+"/share", u.Access, nil), 200)["code"].(string)
	for range 10 {
		if r := e.do("GET", "/s/"+code, "", nil); r.Status != 200 {
			t.Fatal(r.Status)
		}
	}
	if n := types(e.notifications(u))[NotifShareViewed]; n != 2 { // 1st and 10th view
		t.Fatalf("share viewed notifications = %d", n)
	}

	m := e.expect(e.do("GET", "/api/v1/notifications?limit=2", u.Access, nil), 200)
	if m["unread_count"].(float64) != 3 || m["next_cursor"] == nil || len(m["items"].([]any)) != 2 {
		t.Fatalf("page 1: %v", m)
	}
	page2 := e.expect(e.do("GET", "/api/v1/notifications?limit=2&cursor="+m["next_cursor"].(string), u.Access, nil), 200)
	if len(page2["items"].([]any)) != 1 {
		t.Fatal("cursor paging")
	}

	first := m["items"].([]any)[0].(map[string]any)["id"].(string)
	e.expect(e.do("POST", "/api/v1/notifications/read", u.Access, map[string]any{"ids": []string{first}}), 204)
	if c := e.expect(e.do("GET", "/api/v1/notifications", u.Access, nil), 200)["unread_count"].(float64); c != 2 {
		t.Fatalf("after reading one: %v", c)
	}
	// someone else cannot mark my notifications
	other := e.register("Other", "")
	e.expect(e.do("POST", "/api/v1/notifications/read", other.Access, map[string]any{"all": true}), 204)
	if c := e.expect(e.do("GET", "/api/v1/notifications", u.Access, nil), 200)["unread_count"].(float64); c != 2 {
		t.Fatal("another user changed my notifications")
	}
	e.expect(e.do("POST", "/api/v1/notifications/read", u.Access, map[string]any{"all": true}), 204)
	if c := e.expect(e.do("GET", "/api/v1/notifications", u.Access, nil), 200)["unread_count"].(float64); c != 0 {
		t.Fatal("read all")
	}
	if r := e.do("POST", "/api/v1/notifications/read", u.Access, map[string]any{}); r.Status != 400 {
		t.Fatal("empty read request accepted")
	}
}

func TestUserSummary(t *testing.T) {
	e := newEnv(t)
	a, b := e.register("Runner", "JP"), e.register("Viewer", "TH")
	e.putRun(a, gpsRun(2, 3, runStart))
	e.putRun(a, manualRun(10, 60, runStart)) // manual runs are not part of the public summary

	s := e.expect(e.do("GET", "/api/v1/users/"+a.ID+"/summary", b.Access, nil), 200)
	if s["display_name"] != "Runner" || s["run_count"].(float64) != 1 || s["week_distance_m"].(float64) < 4000 ||
		s["total_distance_m"].(float64) > 6000 || s["is_me"] != false {
		t.Fatalf("summary: %v", s)
	}
	e.expect(e.do("PATCH", "/api/v1/me", a.Access, map[string]any{"leaderboard_opt_in": false}), 200)
	if r := e.do("GET", "/api/v1/users/"+a.ID+"/summary", b.Access, nil); r.Status != 404 {
		t.Fatal("opted-out runner is visible")
	}
	e.expect(e.do("GET", "/api/v1/users/"+a.ID+"/summary", a.Access, nil), 200) // but I can see my own
}
