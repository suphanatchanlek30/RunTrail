package api

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/suphanatchanlek30/runtrail/backend/internal/config"
)

var runStart = testNow.Add(-24 * time.Hour) // Tuesday, inside this ISO week

func TestHealth(t *testing.T) {
	e := newEnv(t)
	e.expect(e.do("GET", "/healthz", "", nil), 200)
	m := e.expect(e.do("GET", "/readyz", "", nil), 200)
	if m["db"] != "ok" || m["storage"] != "ok" {
		t.Fatal(m)
	}
	r := e.do("GET", "/api/v1/nope", "", nil)
	if r.Status != 404 || r.errCode(t) != "not_found" {
		t.Fatalf("unknown route: %d %s", r.Status, r.Body)
	}
}

func TestRegisterLogin(t *testing.T) {
	e := newEnv(t)
	u := e.register("Ploy", "th")

	me := e.expect(e.do("GET", "/api/v1/me", u.Access, nil), 200)
	if me["country_code"] != "TH" || me["display_name"] != "Ploy" {
		t.Fatalf("me = %v", me)
	}

	dup := e.do("POST", "/api/v1/auth/register", "", map[string]any{
		"email": "PLOY@example.com", "password": "password123", "display_name": "x"})
	if dup.Status != 409 || dup.errCode(t) != "email_taken" {
		t.Fatalf("duplicate email: %d %s", dup.Status, dup.Body)
	}

	bad := e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": u.Email, "password": "wrong-pass"})
	if bad.Status != 401 || bad.errCode(t) != "invalid_credentials" {
		t.Fatalf("wrong password: %d %s", bad.Status, bad.Body)
	}
	ghost := e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": "ghost@example.com", "password": "password123"})
	if ghost.Status != 401 || ghost.errCode(t) != "invalid_credentials" {
		t.Fatalf("unknown email should look the same: %d %s", ghost.Status, ghost.Body)
	}
	ok := e.expect(e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": " Ploy@Example.com ", "password": "password123"}), 200)
	if ok["access_token"] == "" || ok["user"] == nil {
		t.Fatal(ok)
	}

	v := e.do("POST", "/api/v1/auth/register", "", map[string]any{"email": "not-an-email", "password": "short", "display_name": ""})
	f := v.json(t)["error"].(map[string]any)["fields"].(map[string]any)
	if v.Status != 400 || f["email"] != "email" || f["password"] != "min" || f["display_name"] != "required" {
		t.Fatalf("validation: %d %s", v.Status, v.Body)
	}

	if r := e.do("GET", "/api/v1/me", "", nil); r.Status != 401 {
		t.Fatal("no token should be 401")
	}
	if r := e.do("GET", "/api/v1/me", "garbage", nil); r.Status != 401 {
		t.Fatal("bad token should be 401")
	}
	e.clock.Add(16 * time.Minute)
	if r := e.do("GET", "/api/v1/me", u.Access, nil); r.Status != 401 {
		t.Fatal("expired access token should be 401")
	}
}

func TestRefreshRotationAndReuse(t *testing.T) {
	e := newEnv(t)
	u := e.register("Mint", "")
	refresh := func(tok string) resp {
		return e.do("POST", "/api/v1/auth/refresh", "", map[string]any{"refresh_token": tok})
	}

	first := e.expect(refresh(u.Refresh), 200)
	r1 := first["refresh_token"].(string)
	if r1 == u.Refresh {
		t.Fatal("refresh token was not rotated")
	}

	// UI and sync worker refreshed at the same moment: old token again inside 30 s still works.
	e.clock.Add(10 * time.Second)
	second := e.expect(refresh(u.Refresh), 200)
	r2 := second["refresh_token"].(string)

	// After the grace window the old token is a replay → whole family revoked.
	e.clock.Add(25 * time.Second)
	if r := refresh(u.Refresh); r.Status != 401 || r.errCode(t) != "refresh_token_reused" {
		t.Fatalf("reuse after grace: %d %s", r.Status, r.Body)
	}
	for _, tok := range []string{r1, r2} {
		if r := refresh(tok); r.Status != 401 || r.errCode(t) != "refresh_token_revoked" {
			t.Fatalf("family token still valid: %d %s", r.Status, r.Body)
		}
	}

	// A new login is a new family and is not affected.
	l := e.expect(e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": u.Email, "password": "password123"}), 200)
	e.expect(refresh(l["refresh_token"].(string)), 200)

	if r := refresh("does-not-exist"); r.Status != 401 || r.errCode(t) != "invalid_refresh_token" {
		t.Fatal(r.Status, string(r.Body))
	}
	e.clock.Add(31 * 24 * time.Hour)
	if r := refresh(l["refresh_token"].(string)); r.Status != 401 {
		t.Fatal("expired refresh token accepted")
	}
}

func TestLogoutAndChangePassword(t *testing.T) {
	e := newEnv(t)
	u := e.register("Beam", "")
	other := e.expect(e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": u.Email, "password": "password123"}), 200)

	e.expect(e.do("POST", "/api/v1/auth/logout", "", map[string]any{"refresh_token": u.Refresh}), 204)
	if r := e.do("POST", "/api/v1/auth/refresh", "", map[string]any{"refresh_token": u.Refresh}); r.Status != 401 {
		t.Fatal("logged-out token still works")
	}
	e.expect(e.do("POST", "/api/v1/auth/logout", "", map[string]any{"refresh_token": "unknown"}), 204)

	if r := e.do("POST", "/api/v1/auth/change-password", u.Access, map[string]any{"old_password": "nope-nope", "new_password": "newpassword1"}); r.Status != 400 {
		t.Fatalf("wrong old password: %d", r.Status)
	}
	cp := e.expect(e.do("POST", "/api/v1/auth/change-password", u.Access, map[string]any{"old_password": "password123", "new_password": "newpassword1"}), 200)
	if r := e.do("POST", "/api/v1/auth/refresh", "", map[string]any{"refresh_token": other["refresh_token"]}); r.Status != 401 {
		t.Fatal("other device should be signed out after password change")
	}
	e.expect(e.do("POST", "/api/v1/auth/refresh", "", map[string]any{"refresh_token": cp["refresh_token"]}), 200)
	e.expect(e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": u.Email, "password": "newpassword1"}), 200)
}

func TestRateLimit(t *testing.T) {
	e := newEnv(t, func(c *config.Config) { c.AuthRateLimit = 3 })
	var last resp
	for range 4 {
		last = e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": "a@b.co", "password": "x"})
	}
	if last.Status != 429 || last.errCode(t) != "rate_limited" {
		t.Fatalf("4th login: %d %s", last.Status, last.Body)
	}
}

func TestPatchMeAndStats(t *testing.T) {
	e := newEnv(t)
	u := e.register("Fah", "")
	m := e.expect(e.do("PATCH", "/api/v1/me", u.Access, map[string]any{
		"first_name": "ฟ้า", "weight_kg": 55, "country_code": "jp", "use_miles": true, "weekly_goal_km": 30}), 200)
	if m["first_name"] != "ฟ้า" || m["weight_kg"].(float64) != 55 || m["country_code"] != "JP" || m["use_miles"] != true {
		t.Fatal(m)
	}
	m = e.expect(e.do("PATCH", "/api/v1/me", u.Access, map[string]any{"first_name": nil}), 200)
	if m["first_name"] != nil || m["weight_kg"].(float64) != 55 {
		t.Fatalf("null should clear only first_name: %v", m)
	}
	for _, bad := range []map[string]any{{"weight_kg": 5}, {"display_name": ""}, {"display_name": nil}, {"country_code": "THA"}, {"use_miles": nil}} {
		if r := e.do("PATCH", "/api/v1/me", u.Access, bad); r.Status != 400 {
			t.Fatalf("%v accepted: %s", bad, r.Body)
		}
	}

	e.putRun(u, gpsRun(1, 3, runStart))
	e.putRun(u, manualRun(5, 30, runStart.Add(-40*24*time.Hour)))
	s := e.expect(e.do("GET", "/api/v1/me/stats?tz=Asia/Bangkok", u.Access, nil), 200)
	if s["run_count"].(float64) != 2 || s["best_1k_s"] == nil || len(s["monthly"].([]any)) != 6 {
		t.Fatal(s)
	}
	if r := e.do("GET", "/api/v1/me/stats?tz=Mars/Base", u.Access, nil); r.Status != 400 {
		t.Fatal("bad timezone accepted")
	}
}

func TestPutRunIdempotent(t *testing.T) {
	e := newEnv(t)
	u := e.register("Run", "")
	id := uuid.NewString()
	body := gpsRun(1, 3, runStart)
	statuses := []int{}
	for range 3 {
		statuses = append(statuses, e.do("PUT", "/api/v1/runs/"+id, u.Access, body).Status)
	}
	if statuses[0] != 201 || statuses[1] != 200 || statuses[2] != 200 {
		t.Fatalf("statuses %v", statuses)
	}
	if n := e.count(`SELECT count(*) FROM runs WHERE id = $1`, id); n != 1 {
		t.Fatalf("%d rows after 3 uploads", n)
	}
	r := e.expect(e.do("GET", "/api/v1/runs/"+id, u.Access, nil), 200)
	if r["verified"] != true || r["verify_reason"] != nil {
		t.Fatalf("honest run not verified: %v", r["verify_reason"])
	}
	sd, cd := r["server_distance_m"].(float64), r["distance_m"].(float64)
	if math.Abs(sd-cd)/cd > 0.01 || len(r["splits"].([]any)) != 3 {
		t.Fatalf("server %.1f vs client %.1f, splits %d", sd, cd, len(r["splits"].([]any)))
	}
	tr := e.expect(e.do("GET", "/api/v1/runs/"+id+"/track", u.Access, nil), 200)
	if int(tr["point_count"].(float64)) != len(body["points"].([][]float64)) {
		t.Fatal("track not stored")
	}
}

func TestPutRunLastWriteWins(t *testing.T) {
	e := newEnv(t)
	u := e.register("Lww", "")
	id := uuid.NewString()
	body := gpsRun(1, 3, runStart)
	e.expect(e.do("PUT", "/api/v1/runs/"+id, u.Access, body), 201)

	newer := gpsRun(1, 3, runStart)
	newer["title"] = "ชื่อใหม่"
	newer["client_updated_at"] = runStart.Add(2 * time.Hour).Format(time.RFC3339Nano)
	if m := e.expect(e.do("PUT", "/api/v1/runs/"+id, u.Access, newer), 200); m["title"] != "ชื่อใหม่" {
		t.Fatal("newer version not applied")
	}
	older := gpsRun(1, 3, runStart)
	older["title"] = "ชื่อเก่า"
	if m := e.expect(e.do("PUT", "/api/v1/runs/"+id, u.Access, older), 200); m["title"] != "ชื่อใหม่" {
		t.Fatalf("older client_updated_at overwrote the server: %v", m["title"])
	}

	// PATCH follows the same rule.
	p := e.expect(e.do("PATCH", "/api/v1/runs/"+id, u.Access, map[string]any{
		"title": "จาก patch", "note": "สบาย ๆ", "client_updated_at": runStart.Add(3 * time.Hour)}), 200)
	if p["title"] != "จาก patch" || p["note"] != "สบาย ๆ" {
		t.Fatal(p)
	}
	p = e.expect(e.do("PATCH", "/api/v1/runs/"+id, u.Access, map[string]any{
		"title": "เก่ากว่า", "client_updated_at": runStart.Add(time.Hour)}), 200)
	if p["title"] != "จาก patch" {
		t.Fatal("stale PATCH applied")
	}
	r := e.do("PATCH", "/api/v1/runs/"+id, u.Access, map[string]any{
		"started_at": runStart.Add(-30 * 24 * time.Hour), "client_updated_at": runStart.Add(4 * time.Hour)})
	if r.Status != 400 {
		t.Fatalf("moving a GPS run in time should be refused: %d", r.Status)
	}
	mid, _ := e.putRun(u, manualRun(3, 20, runStart))
	e.expect(e.do("PATCH", "/api/v1/runs/"+mid, u.Access, map[string]any{
		"started_at": runStart.Add(-2 * time.Hour), "client_updated_at": runStart.Add(time.Hour)}), 200)
}

func TestPutRunOtherUsersID(t *testing.T) {
	e := newEnv(t)
	a, b := e.register("Alice", ""), e.register("Bob", "")
	id, _ := e.putRun(a, gpsRun(1, 3, runStart))

	steal := gpsRun(1, 3, runStart)
	steal["title"] = "ของ Bob"
	steal["client_updated_at"] = runStart.Add(48 * time.Hour).Format(time.RFC3339Nano)
	r := e.do("PUT", "/api/v1/runs/"+id, b.Access, steal)
	if r.Status != 409 || r.errCode(t) != "run_id_taken" {
		t.Fatalf("got %d %s", r.Status, r.Body)
	}
	m := e.expect(e.do("GET", "/api/v1/runs/"+id, a.Access, nil), 200)
	if m["title"] != "วิ่งเย็นริมสวน" {
		t.Fatal("owner's run was modified")
	}
	for _, path := range []string{"/api/v1/runs/" + id, "/api/v1/runs/" + id + "/track"} {
		if r := e.do("GET", path, b.Access, nil); r.Status != 404 {
			t.Fatalf("%s visible to another user: %d", path, r.Status)
		}
	}
	if r := e.do("PATCH", "/api/v1/runs/"+id, b.Access, map[string]any{"title": "x", "client_updated_at": testNow}); r.Status != 404 {
		t.Fatal("PATCH on another user's run")
	}
	if r := e.do("DELETE", "/api/v1/runs/"+id, b.Access, nil); r.Status != 404 {
		t.Fatal("DELETE on another user's run")
	}
	if r := e.do("POST", "/api/v1/runs/"+id+"/share", b.Access, nil); r.Status != 404 {
		t.Fatal("share another user's run")
	}
}

func TestPutRunValidation(t *testing.T) {
	e := newEnv(t)
	u := e.register("Val", "")
	put := func(mod func(map[string]any)) resp {
		b := gpsRun(0.2, 3, runStart)
		mod(b)
		return e.do("PUT", "/api/v1/runs/"+uuid.NewString(), u.Access, b)
	}
	cases := map[string]func(map[string]any){
		"no title":      func(b map[string]any) { b["title"] = "  " },
		"bad source":    func(b map[string]any) { b["source"] = "bike" },
		"manual+points": func(b map[string]any) { b["source"] = "manual" },
		"bad point":     func(b map[string]any) { b["points"] = [][]float64{{95, 100, 1, 0, 5}} },
		"short point":   func(b map[string]any) { b["points"] = [][]float64{{13, 100, 1}} },
		"end < start":   func(b map[string]any) { b["ended_at"] = runStart.Add(-time.Hour) },
		"bad tz":        func(b map[string]any) { b["timezone"] = "Nowhere/City" },
		"no version":    func(b map[string]any) { delete(b, "client_updated_at") },
		"negative dist": func(b map[string]any) { b["distance_m"] = -1 },
		"wrong type":    func(b map[string]any) { b["moving_time_s"] = "ten" },
	}
	for name, mod := range cases {
		if r := put(mod); r.Status != 400 {
			t.Errorf("%s: got %d %s", name, r.Status, r.Body)
		}
	}
	if r := e.do("PUT", "/api/v1/runs/not-a-uuid", u.Access, gpsRun(0.2, 3, runStart)); r.Status != 400 {
		t.Fatal("bad uuid accepted")
	}
}

func gz(t *testing.T, b []byte) []byte {
	var buf bytes.Buffer
	w := gzip.NewWriter(&buf)
	if _, err := w.Write(b); err != nil {
		t.Fatal(err)
	}
	w.Close()
	return buf.Bytes()
}

func TestGzipBody(t *testing.T) {
	e := newEnv(t)
	u := e.register("Zip", "")
	raw, _ := json.Marshal(gpsRun(2, 3, runStart))
	r := e.do("PUT", "/api/v1/runs/"+uuid.NewString(), u.Access, gz(t, raw), "Content-Encoding", "gzip")
	if r.Status != 201 {
		t.Fatalf("gzip upload: %d %s", r.Status, r.Body)
	}
	// 20 MB of spaces compresses to ~20 KB: must be rejected after inflating, not by raw size.
	bomb := append([]byte(`{"title":"x"`), bytes.Repeat([]byte(" "), 20<<20)...)
	bomb = append(bomb, '}')
	packed := gz(t, bomb)
	if len(packed) > 1<<20 {
		t.Fatalf("test bomb too big compressed: %d", len(packed))
	}
	r = e.do("PUT", "/api/v1/runs/"+uuid.NewString(), u.Access, packed, "Content-Encoding", "gzip")
	if r.Status != 413 || r.errCode(t) != "body_too_large" {
		t.Fatalf("gzip bomb: %d %s", r.Status, r.Body)
	}
	if r := e.do("PUT", "/api/v1/runs/"+uuid.NewString(), u.Access, []byte("not gzip"), "Content-Encoding", "gzip"); r.Status != 400 {
		t.Fatal("broken gzip accepted")
	}
	if r := e.do("PUT", "/api/v1/runs/"+uuid.NewString(), u.Access, raw, "Content-Encoding", "br"); r.Status != 415 {
		t.Fatalf("brotli: %d", r.Status)
	}
	// A raw body over 6 MB is refused by the HTTP server before any handler runs;
	// app.Test cannot show that status, so scripts/smoke.sh checks it against the real server.
}

func TestDeleteAndSyncList(t *testing.T) {
	e := newEnv(t)
	u := e.register("Sync", "")
	ids := []string{}
	for i := range 5 {
		id, _ := e.putRun(u, manualRun(float64(i+1), 10, runStart.Add(time.Duration(i)*time.Hour)))
		ids = append(ids, id)
		e.clock.Add(time.Second)
	}
	since := e.clock.Now().Add(-1500 * time.Millisecond).Format(time.RFC3339Nano) // just before run 4 was written

	e.clock.Add(time.Second)
	e.expect(e.do("DELETE", "/api/v1/runs/"+ids[0], u.Access, nil), 204)
	e.expect(e.do("DELETE", "/api/v1/runs/"+ids[0], u.Access, nil), 204) // idempotent
	e.expect(e.do("DELETE", "/api/v1/runs/"+uuid.NewString(), u.Access, nil), 204)
	if r := e.do("GET", "/api/v1/runs/"+ids[0], u.Access, nil); r.Status != 404 {
		t.Fatal("deleted run still readable")
	}
	gone := gpsRun(0.3, 3, runStart)
	gone["client_updated_at"] = testNow.Add(time.Hour).Format(time.RFC3339Nano)
	if r := e.do("PUT", "/api/v1/runs/"+ids[0], u.Access, gone); r.Status != 410 || r.errCode(t) != "run_deleted" {
		t.Fatalf("re-upload of deleted run: %d %s", r.Status, r.Body)
	}

	// Full restore (new phone): only live runs, paged 2 at a time.
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 10; page++ {
		path := "/api/v1/runs?limit=2"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		m := e.expect(e.do("GET", path, u.Access, nil), 200)
		for _, it := range m["items"].([]any) {
			id := it.(map[string]any)["id"].(string)
			if seen[id] {
				t.Fatal("page repeated a run")
			}
			seen[id] = true
		}
		if m["next_cursor"] == nil {
			break
		}
		cursor = m["next_cursor"].(string)
	}
	if len(seen) != 4 || seen[ids[0]] {
		t.Fatalf("restore got %d runs (deleted included: %v)", len(seen), seen[ids[0]])
	}

	// Incremental sync: run 4 (written at `since`) + the tombstone of run 0.
	m := e.expect(e.do("GET", "/api/v1/runs?updated_since="+since, u.Access, nil), 200)
	items := m["items"].([]any)
	if len(items) != 2 {
		t.Fatalf("incremental sync returned %d items: %s", len(items), mustJSON(items))
	}
	tomb := items[1].(map[string]any)
	if tomb["id"] != ids[0] || tomb["deleted"] != true {
		t.Fatalf("expected tombstone last, got %v", tomb)
	}
	st, err := time.Parse(time.RFC3339Nano, m["server_time"].(string))
	if err != nil || e.clock.Now().Sub(st) < SyncOverlap {
		t.Fatalf("server_time %v must be at least %v before now", m["server_time"], SyncOverlap)
	}
	if r := e.do("GET", "/api/v1/runs?updated_since=yesterday", u.Access, nil); r.Status != 400 {
		t.Fatal("bad updated_since accepted")
	}
}

func mustJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

func TestPhotos(t *testing.T) {
	e := newEnv(t)
	u := e.register("Pic", "")
	id, _ := e.putRun(u, gpsRun(0.5, 3, runStart))
	base := "/api/v1/runs/" + id + "/photo"

	if r := e.do("POST", base+"/upload-url", u.Access, map[string]any{"content_type": "image/gif", "size": 1000}); r.Status != 400 {
		t.Fatal("gif accepted")
	}
	if r := e.do("POST", base+"/upload-url", u.Access, map[string]any{"content_type": "image/jpeg", "size": 6 << 20}); r.Status != 400 {
		t.Fatal("6 MB accepted")
	}
	up := e.expect(e.do("POST", base+"/upload-url", u.Access, map[string]any{"content_type": "image/jpeg", "size": 200_000}), 200)
	key := up["key"].(string)
	if !strings.HasPrefix(key, "runs/"+u.ID+"/"+id+"/") || up["method"] != "PUT" {
		t.Fatal(up)
	}

	r := e.do("POST", base+"/confirm", u.Access, map[string]any{"key": key})
	if r.Status != 400 || r.errCode(t) != "upload_missing" {
		t.Fatalf("confirm before upload: %d %s", r.Status, r.Body)
	}
	if r := e.do("POST", base+"/confirm", u.Access, map[string]any{"key": "runs/someone-else.jpg"}); r.Status != 400 {
		t.Fatal("unknown key accepted")
	}

	e.store.Put(key, 200_000, "image/jpeg") // the phone's PUT
	m := e.expect(e.do("POST", base+"/confirm", u.Access, map[string]any{"key": key}), 200)
	if m["photo_url"] == nil {
		t.Fatal("photo_url missing")
	}

	// Replacing deletes the old object.
	up2 := e.expect(e.do("POST", base+"/upload-url", u.Access, map[string]any{"content_type": "image/webp", "size": 1000}), 200)
	e.store.Put(up2["key"].(string), 1000, "image/webp")
	e.expect(e.do("POST", base+"/confirm", u.Access, map[string]any{"key": up2["key"]}), 200)
	waitFor(t, func() bool { return !e.store.Has(key) })

	e.expect(e.do("DELETE", base, u.Access, nil), 204)
	if m := e.expect(e.do("GET", "/api/v1/runs/"+id, u.Access, nil), 200); m["photo_url"] != nil {
		t.Fatal("photo not removed")
	}

	// Upload that never gets confirmed is cleaned after 24 h.
	up3 := e.expect(e.do("POST", base+"/upload-url", u.Access, map[string]any{"content_type": "image/jpeg", "size": 1000}), 200)
	e.store.Put(up3["key"].(string), 1000, "image/jpeg")
	e.clock.Add(25 * time.Hour)
	if n, err := e.srv.CleanupUploads(t.Context()); err != nil || n != 1 || e.store.Has(up3["key"].(string)) {
		t.Fatalf("cleanup: n=%d err=%v", n, err)
	}
	u.Access = e.expect(e.do("POST", "/api/v1/auth/login", "", map[string]any{"email": u.Email, "password": "password123"}), 200)["access_token"].(string)

	// Avatar
	av := e.expect(e.do("POST", "/api/v1/me/avatar/upload-url", u.Access, map[string]any{"content_type": "image/jpeg", "size": 5000}), 200)
	e.store.Put(av["key"].(string), 5000, "image/jpeg")
	if m := e.expect(e.do("POST", "/api/v1/me/avatar/confirm", u.Access, map[string]any{"key": av["key"]}), 200); m["avatar_url"] == nil {
		t.Fatal("avatar_url missing")
	}
	e.expect(e.do("DELETE", "/api/v1/me/avatar", u.Access, nil), 204)
}

func waitFor(t *testing.T, ok func() bool) {
	t.Helper()
	for range 100 {
		if ok() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("condition not met in time")
}

func TestLeaderboard(t *testing.T) {
	e := newEnv(t)
	a, b, c := e.register("Aom", "TH"), e.register("Ben", "TH"), e.register("Cara", "JP")
	e.putRun(a, gpsRun(1, 3, runStart))
	e.putRun(b, gpsRun(3, 3, runStart))
	e.putRun(c, gpsRun(2, 3, runStart))

	// Not ranked: manual run, a car-speed run, and last week's run.
	e.putRun(a, manualRun(42, 60, runStart))
	_, fast := e.putRun(a, gpsRun(3, 6.5, runStart.Add(2*time.Hour)))
	if fast["verified"] != false || fast["verify_reason"] != "too_fast" {
		t.Fatalf("fast run: %v %v", fast["verified"], fast["verify_reason"])
	}
	e.putRun(a, gpsRun(5, 3, runStart.Add(-7*24*time.Hour)))

	lb := e.expect(e.do("GET", "/api/v1/leaderboard?metric=distance&period=week", a.Access, nil), 200)
	entries := lb["entries"].([]any)
	order := []string{}
	for _, it := range entries {
		order = append(order, it.(map[string]any)["display_name"].(string))
	}
	if strings.Join(order, ",") != "Ben,Cara,Aom" {
		t.Fatalf("order = %v", order)
	}
	me := lb["me"].(map[string]any)
	if me["rank"].(float64) != 3 || me["is_me"] != true || me["value"].(float64) > 2500 {
		t.Fatalf("me = %v (manual/fast/old runs must not count)", me)
	}
	if lb["period_start"] != "2026-11-09T00:00:00Z" || lb["period_end"] != "2026-11-16T00:00:00Z" {
		t.Fatalf("ISO week bounds: %v → %v", lb["period_start"], lb["period_end"])
	}

	all := e.expect(e.do("GET", "/api/v1/leaderboard?period=all", a.Access, nil), 200)
	if all["entries"].([]any)[0].(map[string]any)["display_name"] != "Aom" {
		t.Fatal("all-time should include last week's 5 laps")
	}

	th := e.expect(e.do("GET", "/api/v1/leaderboard?scope=country", a.Access, nil), 200)
	if len(th["entries"].([]any)) != 2 || th["country"] != "TH" {
		t.Fatalf("country board: %v", th)
	}

	around := e.expect(e.do("GET", "/api/v1/leaderboard/me?period=week", c.Access, nil), 200)
	if around["me"].(map[string]any)["rank"].(float64) != 2 ||
		around["ahead"].(map[string]any)["display_name"] != "Ben" || around["behind"].(map[string]any)["display_name"] != "Aom" {
		t.Fatalf("around me: %s", mustJSON(around))
	}

	best := e.expect(e.do("GET", "/api/v1/leaderboard?metric=best_5k&period=all", a.Access, nil), 200)
	if len(best["entries"].([]any)) != 2 { // Aom (old 5 laps) + Ben (3 laps ≈ 7 km)
		t.Fatalf("best_5k: %s", mustJSON(best["entries"]))
	}

	// Opting out hides the runner.
	e.expect(e.do("PATCH", "/api/v1/me", b.Access, map[string]any{"leaderboard_opt_in": false}), 200)
	lb = e.expect(e.do("GET", "/api/v1/leaderboard", b.Access, nil), 200)
	if len(lb["entries"].([]any)) != 2 || lb["me"] != nil {
		t.Fatal("opted-out runner still listed")
	}
	for _, q := range []string{"metric=steps", "period=year", "scope=moon", "limit=0"} {
		if r := e.do("GET", "/api/v1/leaderboard?"+q, a.Access, nil); r.Status != 400 {
			t.Fatalf("%s accepted", q)
		}
	}
}

func TestShareHidesHome(t *testing.T) {
	e := newEnv(t)
	u := e.register("Share", "TH")
	body := gpsRun(2, 3, runStart)
	id, _ := e.putRun(u, body)

	sh := e.expect(e.do("POST", "/api/v1/runs/"+id+"/share", u.Access, nil), 200)
	code := sh["code"].(string)
	if sh["url"] != "https://runtrail.test/s/"+code || sh["hide_ends"] != true || len(code) != 8 {
		t.Fatal(sh)
	}
	again := e.expect(e.do("POST", "/api/v1/runs/"+id+"/share", u.Access, map[string]any{"show_kcal": true}), 200)
	if again["code"] != code || again["show_kcal"] != true {
		t.Fatal("re-share should keep the code and update options")
	}

	pub := e.expect(e.do("GET", "/api/v1/public/runs/"+code, "", nil), 200)
	pts := body["points"].([][]float64)
	start, end := pts[0], pts[len(pts)-1]
	visible := 0
	for _, seg := range pub["segments"].([]any) {
		for _, p := range seg.([]any) {
			ll := p.([]any)
			lat, lng := ll[0].(float64), ll[1].(float64)
			visible++
			for _, home := range [][]float64{start, end} {
				if d := pathDistance([][]float64{{home[0], home[1]}, {lat, lng}}); d < 200 {
					t.Fatalf("point %.0f m from start/finish is public", d)
				}
			}
		}
	}
	if visible == 0 || pub["calories"] == nil || pub["note"] != nil {
		t.Fatalf("visible=%d calories=%v", visible, pub["calories"])
	}
	if strings.Contains(string(e.do("GET", "/api/v1/public/runs/"+code, "", nil).Body), `"t"`) {
		t.Fatal("timestamps leaked")
	}

	page := e.do("GET", "/s/"+code, "", nil)
	if page.Status != 200 || !strings.Contains(string(page.Body), `og:title`) || !strings.Contains(string(page.Body), "leaflet") {
		t.Fatalf("share page: %d", page.Status)
	}

	// Turning hiding off shows the full route.
	e.expect(e.do("POST", "/api/v1/runs/"+id+"/share", u.Access, map[string]any{"hide_ends": false}), 200)
	full := e.expect(e.do("GET", "/api/v1/public/runs/"+code, "", nil), 200)
	if n := len(full["segments"].([]any)[0].([]any)); n != len(pts) {
		t.Fatalf("full route has %d of %d points", n, len(pts))
	}

	e.expect(e.do("DELETE", "/api/v1/runs/"+id+"/share", u.Access, nil), 204)
	if r := e.do("GET", "/api/v1/public/runs/"+code, "", nil); r.Status != 404 {
		t.Fatal("unshared link still works")
	}
	if r := e.do("GET", "/s/"+code, "", nil); r.Status != 404 || !strings.Contains(string(r.Body), "ไม่พบการวิ่งนี้") {
		t.Fatalf("unshared page: %d", r.Status)
	}
}

func TestDeleteAccount(t *testing.T) {
	e := newEnv(t)
	u := e.register("Bye", "")
	id, _ := e.putRun(u, gpsRun(1, 3, runStart))
	sh := e.expect(e.do("POST", "/api/v1/runs/"+id+"/share", u.Access, nil), 200)

	e.expect(e.do("DELETE", "/api/v1/me", u.Access, nil), 204)
	if r := e.do("GET", "/api/v1/me", u.Access, nil); r.Status != 401 {
		t.Fatal("deleted account still authenticates")
	}
	if r := e.do("POST", "/api/v1/auth/refresh", "", map[string]any{"refresh_token": u.Refresh}); r.Status != 401 {
		t.Fatal("refresh after delete")
	}
	if r := e.do("GET", "/api/v1/public/runs/"+sh["code"].(string), "", nil); r.Status != 404 {
		t.Fatal("share link of deleted account still public")
	}
	e.register("Bye", "") // email is free again
}
