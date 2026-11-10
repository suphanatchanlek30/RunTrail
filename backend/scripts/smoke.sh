#!/usr/bin/env bash
# End-to-end check against the running Docker stack (make up first).
# Walks the whole app flow with curl: register → login → profile → GPS run (gzip) → photo to S3
# → share page → leaderboard → refresh → logout → delete.
# Needs: curl, jq, python3.   Usage: ./scripts/smoke.sh [base_url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
pass=0

ok()   { pass=$((pass + 1)); printf '  \033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗ %s\033[0m\n' "$*"; exit 1; }
# req METHOD PATH [TOKEN] [BODY_FILE] [extra curl args...] → prints status, body in $TMP/body
req() {
  local m="$1" p="$2" tok="${3:-}" body="${4:-}"; shift 4 2>/dev/null || shift $#
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$m" "$API$p")
  [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  curl "${args[@]}" "$@"
}
expect() { [ "$1" = "$2" ] || fail "$3: HTTP $1 (want $2) $(cat "$TMP/body")"; ok "$3"; }
j() { jq -r "$1" "$TMP/body"; }

echo "RunTrail smoke test → $BASE"

code=$(curl -s -o "$TMP/body" -w '%{http_code}' "$BASE/readyz")
expect "$code" 200 "readyz: $(cat "$TMP/body")"

EMAIL="smoke$(date +%s)@example.com"
jq -n --arg e "$EMAIL" '{email:$e, password:"password123", display_name:"Smoke Runner", country_code:"TH", device_name:"smoke.sh"}' > "$TMP/reg.json"
expect "$(req POST /auth/register "" "$TMP/reg.json")" 201 "register $EMAIL"

jq -n --arg e "$EMAIL" '{email:$e, password:"password123", device_name:"smoke.sh"}' > "$TMP/login.json"
expect "$(req POST /auth/login "" "$TMP/login.json")" 200 "login"
ACCESS=$(j .access_token); REFRESH=$(j .refresh_token)

echo '{"first_name":"สโมค","weight_kg":62,"height_cm":172,"weekly_goal_km":25}' > "$TMP/me.json"
expect "$(req PATCH /me "$ACCESS" "$TMP/me.json")" 200 "PATCH /me (weight $(j .weight_kg) kg)"

# A real ~2.4 km lap around Lumphini Park, 1 point per second at 3 m/s (pace ≈ 5:33/km), started 1 h ago.
python3 - "$TMP/run.json" <<'PY'
import json, math, sys, time, uuid
loop = [(13.7280,100.5385),(13.7280,100.5445),(13.7330,100.5445),(13.7330,100.5385),(13.7280,100.5385)]
mlat = 6371000*math.pi/180; mlng = mlat*math.cos(math.radians(13.73))
segs = [(a,b,math.hypot((b[0]-a[0])*mlat,(b[1]-a[1])*mlng)) for a,b in zip(loop,loop[1:])]
start = int(time.time()*1000) - 3600_000
pts, d, t = [], 0.0, start
lap = sum(s[2] for s in segs)
while d <= lap:
    into = d
    for a,b,L in segs:
        if into <= L:
            f = into/L; pts.append([a[0]+(b[0]-a[0])*f, a[1]+(b[1]-a[1])*f, t, 5.0, 5.0]); break
        into -= L
    d += 3; t += 1000
def hav(p,q):
    la1,la2=math.radians(p[0]),math.radians(q[0]); dla=la2-la1; dlo=math.radians(q[1]-p[1])
    h=math.sin(dla/2)**2+math.cos(la1)*math.cos(la2)*math.sin(dlo/2)**2
    return 2*6371000*math.asin(math.sqrt(h))
dist = sum(hav(a,b) for a,b in zip(pts,pts[1:]))
dur = (pts[-1][2]-pts[0][2])//1000
iso = lambda ms: time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(ms/1000))
json.dump({"id": str(uuid.uuid4()), "body": {
  "title":"วิ่งรอบสวนลุมพินี","note":"smoke test","source":"gps","started_at":iso(start),
  "ended_at":iso(pts[-1][2]),"timezone":"Asia/Bangkok","distance_m":dist,"moving_time_s":dur,
  "calories":160,"elevation_gain_m":2,"cadence_spm":168,"temp_c":None,"points":pts,
  "client_updated_at":iso(int(time.time()*1000))}}, open(sys.argv[1],"w"))
PY
RUN_ID=$(jq -r .id "$TMP/run.json")
jq .body "$TMP/run.json" | gzip -c > "$TMP/run.json.gz"
POINTS=$(jq '.body.points | length' "$TMP/run.json")
expect "$(req PUT "/runs/$RUN_ID" "$ACCESS" "$TMP/run.json.gz" -H 'Content-Encoding: gzip')" 201 \
  "PUT run (gzip, $POINTS points, $(wc -c < "$TMP/run.json.gz" | tr -d ' ') bytes)"
[ "$(j .verified)" = "true" ] || fail "run not verified: $(j .verify_reason)"
ok "server verified run: $(j '.server_distance_m | floor') m, $(j '.splits | length') splits, best 1k $(j .best_1k_s) s"

expect "$(req PUT "/runs/$RUN_ID" "$ACCESS" "$TMP/run.json.gz" -H 'Content-Encoding: gzip')" 200 "PUT same run again (idempotent retry)"

# Photo: ask for a presigned URL, upload straight to S3 like the phone does, then confirm.
python3 -c "import base64,sys; open(sys.argv[1],'wb').write(base64.b64decode('/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA='))" "$TMP/photo.jpg"
SIZE=$(wc -c < "$TMP/photo.jpg" | tr -d ' ')
jq -n --argjson s "$SIZE" '{content_type:"image/jpeg", size:$s}' > "$TMP/up.json"
expect "$(req POST "/runs/$RUN_ID/photo/upload-url" "$ACCESS" "$TMP/up.json")" 200 "photo upload-url"
UPLOAD_URL=$(j .upload_url); KEY=$(j .key)
jq -n --arg k "$KEY" '{key:$k}' > "$TMP/key.json"
code=$(req POST "/runs/$RUN_ID/photo/confirm" "$ACCESS" "$TMP/key.json")
[ "$code" = 400 ] && [ "$(j .error.code)" = "upload_missing" ] || fail "confirm before upload should be 400, got $code"
ok "confirm before upload → 400 upload_missing"
code=$(curl -s -o "$TMP/s3" -w '%{http_code}' -X PUT -H 'Content-Type: image/jpeg' --data-binary "@$TMP/photo.jpg" "$UPLOAD_URL")
[ "$code" = 200 ] || fail "PUT to S3 failed: $code $(cat "$TMP/s3")"
ok "PUT photo to S3 ($(echo "$UPLOAD_URL" | cut -d/ -f1-3))"
expect "$(req POST "/runs/$RUN_ID/photo/confirm" "$ACCESS" "$TMP/key.json")" 200 "photo confirm"
curl -sf -o "$TMP/dl.jpg" "$(j .photo_url)" || fail "download via photo_url failed"
cmp -s "$TMP/photo.jpg" "$TMP/dl.jpg" || fail "downloaded photo differs"
ok "photo_url downloads the same bytes"

echo '{"hide_ends":true,"show_photo":true,"show_kcal":true}' > "$TMP/share.json"
expect "$(req POST "/runs/$RUN_ID/share" "$ACCESS" "$TMP/share.json")" 200 "create share link"
CODE=$(j .code); SHARE_URL=$(j .url)
code=$(curl -s -o "$TMP/page.html" -w '%{http_code}' "$BASE/s/$CODE")
[ "$code" = 200 ] && grep -q 'og:title' "$TMP/page.html" && grep -q 'leaflet' "$TMP/page.html" || fail "share page $code"
ok "share page $SHARE_URL renders (OG tags + Leaflet)"
expect "$(req GET "/public/runs/$CODE" "" "")" 200 "public run JSON"
python3 - "$TMP/body" "$TMP/run.json" <<'PY' || fail "privacy check failed"
import json, math, sys
pub = json.load(open(sys.argv[1])); pts = json.load(open(sys.argv[2]))["body"]["points"]
def hav(p,q):
    la1,la2=math.radians(p[0]),math.radians(q[0]); dla=la2-la1; dlo=math.radians(q[1]-p[1])
    return 2*6371000*math.asin(math.sqrt(math.sin(dla/2)**2+math.cos(la1)*math.cos(la2)*math.sin(dlo/2)**2))
vis = [p for s in pub["segments"] for p in s]
near = min(min(hav(p,pts[0]), hav(p,pts[-1])) for p in vis)
assert vis and near >= 200, near
print(f"  \033[32m✓\033[0m public route hides home: {len(vis)}/{len(pts)} points shown, closest {near:.0f} m from start/finish")
PY
pass=$((pass + 1))

expect "$(req GET "/notifications" "$ACCESS" "")" 200 "notifications: $(j '[.items[].type] | join(", ")') (unread $(j .unread_count))"
[ "$(j '[.items[] | select(.type == "run_verified")] | length')" -ge 1 ] || fail "no run_verified notification"
[ "$(j '[.items[] | select(.type == "share_viewed")] | length')" -ge 1 ] || fail "no share_viewed notification"
ok "real events became notifications (run verified + share viewed)"
echo '{"all":true}' > "$TMP/read.json"
expect "$(req POST /notifications/read "$ACCESS" "$TMP/read.json")" 204 "mark all notifications read"
expect "$(req GET "/leaderboard?metric=distance&period=week" "$ACCESS" "")" 200 \
  "leaderboard week: my rank $(j .me.rank) of $(j .total)"
expect "$(req GET "/leaderboard/me?period=week&scope=country" "$ACCESS" "")" 200 "leaderboard/me (TH)"
expect "$(req GET "/me/stats?tz=Asia/Bangkok" "$ACCESS" "")" 200 "stats: $(j .run_count) run(s), $(j '.total_distance_m | floor') m"
expect "$(req GET "/runs?updated_since=2000-01-01T00:00:00Z" "$ACCESS" "")" 200 "sync list: $(j '.items | length') item(s)"

jq -n --arg r "$REFRESH" '{refresh_token:$r}' > "$TMP/rt.json"
expect "$(req POST /auth/refresh "" "$TMP/rt.json")" 200 "refresh (rotated)"
NEW_REFRESH=$(j .refresh_token); ACCESS=$(j .access_token)
jq -n --arg r "$NEW_REFRESH" '{refresh_token:$r}' > "$TMP/rt2.json"
expect "$(req POST /auth/logout "" "$TMP/rt2.json")" 204 "logout"
expect "$(req POST /auth/refresh "" "$TMP/rt2.json")" 401 "refresh after logout → 401"

head -c 7000000 /dev/zero | tr '\0' ' ' > "$TMP/big.json"
code=$(req PUT "/runs/$(python3 -c 'import uuid;print(uuid.uuid4())')" "$ACCESS" "$TMP/big.json")
expect "$code" 413 "7 MB body → 413"

expect "$(req DELETE "/runs/$RUN_ID" "$ACCESS" "")" 204 "delete run"
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/s/$CODE")
expect "$code" 404 "share link dead after delete"

printf '\n\033[32mALL %d CHECKS PASSED\033[0m\n' "$pass"
