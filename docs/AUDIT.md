# RunTrail — System Audit

Branch: `audit/full-system-fixes` · started 2026-11-12

This file records what was checked, what was found, what was fixed, and what is still unverified.
Every "passed" claim below links to a command that was actually run in this session.

## 1. Baseline (before any change)

| Suite | Command | Result |
|---|---|---|
| App JVM unit tests | `./gradlew testDebugUnitTest` | **32 / 32 passed**, 0 skipped |
| App build | `./gradlew assembleDebug` | passed |
| App lint | `./gradlew lint` | **0 errors, 225 warnings** |
| Backend Go tests | `cd backend && go test -count=1 ./...` | api, auth, track: **ok** |
| Backend format / vet | `gofmt -l .` · `go vet ./...` | clean |
| Instrumented tests | `adb shell am instrument …` | see §5 |
| Backend smoke | `make smoke` | see §5 |

Lint warnings by id (baseline): UnusedResources 32 · UseCompoundDrawables 23 · DisableBaselineAlignment 22 ·
Overdraw 21 · LockedOrientationActivity 19 · DiscouragedApi 19 · HardcodedText 13 · UseKtx 11 ·
PluralsCandidate 11 · DrawAllocation 8 · DuplicateIncludedIds 7 · others ≤ 5.

## 2. Findings

Severity: **S1** crash / data loss · **S2** wrong result or a control that does nothing · **S3** performance / jank · **S4** minor.

| # | Area | File | Problem | Sev | Status |
|---|---|---|---|---|---|
| A1 | Replay | `ui/replay/ReplayActivity.kt` `render()` | Runs without a GPS route (manual entries) give an empty route; `route[…]` throws `IndexOutOfBoundsException` → app crashes when "Replay" is tapped on a manual run | S1 | fixed — Replay explains and closes; Detail/Stats hide or explain Replay. Test: `ScreenCrashTest` (crashed before: `IndexOutOfBoundsException` at ReplayActivity.kt:158, logcat) |
| A2 | Stats | `ui/detail/RunStatsActivity.kt` `renderChart()` | `raw.max()` on an empty list throws `NoSuchElementException` when the elevation chart is chosen for a run without a route | S1 | fixed — empty-state text instead of `max()`. Test: `ScreenCrashTest` (failed before with `NoSuchElementException`) |
| A3 | Photos | `ui/common/ImageStore.kt` `saveFromUri()` | Decodes the full camera/gallery image (e.g. 50 MP ≈ 200 MB bitmap) before scaling → `OutOfMemoryError`; also runs on the main thread in Live, Edit and Share-image screens | S1 | fixed — bounds decode + `inSampleSize`, OOM/IO caught, work moved to background in Live/Edit/ShareImage. Tests: `ImageStoreTest` (4000×3000 + EXIF 90° → 960×1280), `DeviceLogicTest.sampleSize…` |
| A4 | GPS | `sensor/LocationTracker.kt` `start()` | `requestLocationUpdates(GPS_PROVIDER)` throws `IllegalArgumentException` on devices without a GPS provider → foreground service crashes | S1 | fixed — `pickProvider` GPS → fused → network, exceptions caught. Test: `DeviceLogicTest.prefersGps…` |
| A5 | Pickers | `SettingsActivity` goal/pace, `RegisterActivity` country | `selected = indexOf(..).coerceAtLeast(0)`: when the current value is not in the list (e.g. goal 23 km from the server), option 0 looks selected and tapping it does nothing | S2 | fixed — pickers pass -1 when the value is not listed; country picker always fires |
| A6 | Weekly goal | `MainActivity.render()`, `SettingsActivity` | Goal is stored in km (`weekly_goal_km` on the server, ProfileSetup labels it km) but Home multiplies it by 1609 m and Settings labels it "mi" in miles mode → app and server disagree about when the goal is reached | S2 | fixed — `Units.goalMeters/goalText/goalPercent`, km is canonical. Test: `UnitsTest.weeklyGoalIsAlwaysKilometres` |
| A7 | Leaderboard | `LeaderboardActivity`, profile | "My country" needs `country_code` on the server; accounts created without one get a validation error, and nothing in the app can set the country after registration → the "country" chip is a dead end | S2 | fixed — country row in Settings (synced via profile) and "Set country" action on the leaderboard error |
| A8 | Units | `ReplayActivity.buildAxis`, `RunStatsActivity` axis, `RunCalculator.records` | Axis marks are kilometres but labelled with the selected unit (mi); "Longest run" ignores the miles setting | S2 | fixed — `Units.axisMarks`, `RunCalculator.records(miles)`, replay segment label. Test: `UnitsTest` |
| A9 | Photos | `AddEditRunActivity` | There is no way to remove a photo, although sync supports `PhotoSync.DELETE`; replaced photos leave orphan files | S2 | fixed — remove-photo button; server photo removed through `PhotoSync.DELETE`; unused/replaced files deleted |
| A10 | Share | `ShareSheet` | Changing a privacy switch while a link request is in flight is dropped (`createLink` returns early) → server keeps old options | S2 | fixed — switch changes during a request are re-sent when it finishes |
| A11 | Sensor check | `SensorCheckActivity` | Rows that say "needs permission" / "GPS off" do nothing when tapped | S2 | fixed — GPS row asks for permission or opens location settings; step row asks for activity permission |
| A12 | Live run | `LiveRunActivity` back handler | When recording never started (location denied) Back needs a double press | S4 | fixed |
| P1 | Data refresh | `RunRepository.notifyChanged()` | One main-thread post per DB write; a pull of N runs re-renders every open screen N times (each render reloads all runs) | S3 | fixed — coalesced (120 ms). Measured: 100 writes → **2** screen refreshes (was 1 per write = 100). `PerfTest` |
| P2 | Home | `MainActivity.render()` | `getAll()` is executed 3× per render (`getAll`, `thisWeek`, `weekDailyKm`) | S3 | fixed — Home queries `recent(2)` + this week in SQL; Records loads on a background thread. Measured on emulator with 1,000 runs: **2026 ms → 14 ms** per Home render. `PerfTest` |
| P3 | History | `HistoryActivity.load()` | Every keystroke re-reads all runs from SQLite and calls `notifyDataSetChanged()` | S3 | fixed — list loaded once on a background thread, keystrokes filter in memory, DiffUtil. Measured: **636 ms → 9 ms** per keystroke. `PerfTest` |
| P4 | Share | `ShareSheet.showQr()` | ~518k `Bitmap.setPixel` calls on the main thread | S3 | fixed — `setPixels`. Measured: **155 ms → 9 ms**. `PerfTest` |
| P5 | Charts | `LineChartView`, `BarChartView`, `RingProgressView`, `ToggleSwitch`, `HoldToStopView` | Objects allocated inside `onDraw` (lint DrawAllocation) | S3 | fixed — rects/paths/gradients reused (not timed; lint DrawAllocation check below) |
| K1 | Server URL | `ui/common/ServerUrl.kt` | Changing the server while logged in keeps old tokens and `SYNCED`/`onServer` flags → existing runs never upload to the new server | S2 | fixed — server address cannot be changed while signed in; the sheet explains to sync and sign out first |
| B1 | Backend share | `api/share.go` `createShare` | Two concurrent requests can pick the same free code; the losing insert hits the UNIQUE constraint → HTTP 500 | S4 | fixed — retry with a new code on `runs_share_code_key` violation (race not reproduced in a test) |
| B2 | Backend stats | `api/users.go` `myStats` | Monthly buckets use the database `now()` instead of the server clock (`s.now()`), so the injected test clock is ignored | S4 | fixed — uses `s.now()`. Test: `TestStatsMonthlyFollowsServerClock` (failed before, passes after) |

### Checked and found correct (no change)

- Backend auth: argon2id, constant-time compare, dummy hash on unknown email, refresh rotation with grace window and family revoke on reuse, change-password revokes other sessions, `requireAuth` rejects deleted accounts.
- Backend ownership: every run/photo/share handler goes through `ownRun` (404 for other users), PUT with another user's UUID → 409.
- Backend SQL: all values are bound parameters; the only string-built SQL (`patchMe` SET list, leaderboard aggregate, PB column) uses fixed identifiers, never input.
- Backend body limits: raw 6 MB, decompressed 5 MB (gzip bomb guard), 50k points, 2k pauses.
- Track parity: app `GpsFilter` stores only accepted points; server `Clean` re-applies accuracy/teleport rules and `spansPause` drops the segment across a resume, so both sides sum the same segments.
- Lint `InlinedApi` (`FOREGROUND_SERVICE_TYPE_LOCATION`): `ServiceCompat.startForeground` ignores the type below API 29 → false positive.
- Lint `StaticFieldLeak` (`App.services`): it only holds the Application context → false positive.
- Lint `HardcodedText` (13): all are sample numbers in layouts that code overwrites at runtime; no user-visible untranslated text.

## 3. Screen × control table

Filled in during phase 3 (see below).

## 4. Fix log

(appended as fixes land)

## 5. Test results after fixes

(appended at the end)

## 6. Not verified / remaining risk

(appended at the end)
