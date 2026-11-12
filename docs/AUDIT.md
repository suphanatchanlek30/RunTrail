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
| Instrumented tests | `adb shell am instrument -w -e e2eApiUrl http://10.0.2.2:8080 …` | **12 / 12 passed** (incl. sync against the real backend) |
| Backend smoke | `make smoke` | **29 / 29 passed** |

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
| A13 | Lists | `SensorCheckActivity`, `NotificationsActivity` | `setOnClickListener(null)` makes a view clickable, so rows without an action looked tappable (Notifications set `isClickable` before it, so it was undone) | S4 | fixed — reset `isClickable` after the call; found by `ControlsAuditTest` |
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

How this was checked (emulator Pixel 9, Android 17, app in Guest mode, in-memory test database):

1. `ControlsAuditTest` opens 19 screen states and walks every visible view. It fails if a view is clickable
   (or is named `btn*/action*/chip*/seg*/speed*/fab*`) but has no click handler. **Result: 0 dead controls**
   (first run found `SensorCheck.row`, fixed below; `Stats.card*` and `Edit.rowLocked` were information boxes, not buttons).
2. `TapEverythingTest` relaunches the screen for each control, taps it, and fails if the process crashes.
   **Result: passed — no crash on any tapped control.**

Not tapped automatically, and why:
- open another app or a system dialog: camera, gallery, share chooser, "save to gallery", permission prompts
  (`btnCamera`, `btnGallery`, `photoChip`, `Records.btnShare`, `ShareImage.btnShare/btnSave/segPhoto`, `Permissions.cardLocation/cardActivity/btnNext`);
- change the real user's data on this emulator: `ProfileSetup.btnDone`, `Settings.rowAutoPause/rowKmAlert/rowRecreate`;
- start a real GPS recording: `Home.fabStart/btnFirstRun` (the recording service is covered by `RunTrackingServiceTest`).
- account rows (`rowPassword`, `rowLogout`, `rowDeleteAccount`, `rowCountry`, `rowLeaderboard`, `rowUpdates`) are hidden in Guest mode,
  so they were reviewed in code only; their server side is covered by Go tests and `smoke.sh`.

| Screen | Controls with a handler (all tapped by `TapEverythingTest` unless noted) |
|---|---|
| Welcome | btnStart, dot0, dot1, dot2, tvBody, tvHeadline |
| Permissions | btnBack, btnNext, btnSkip, cardActivity, cardLocation |
| Login | btnBack, btnGuest, btnLogin, btnRegister, btnServer |
| Register | btnBack, btnRegister, fieldCountry |
| ProfileSetup | btnBack, btnDone, chip10, chip15, chip20, chip30, segKm, segMi |
| Home | btnBell, btnSeeAll, card, cardWeekly, fabStart, nav_history, nav_home, nav_leaderboard, nav_records, nav_settings |
| History | btnFilter, chip5k, chipAll, chipMonth, chipWeek, nav_history, nav_home, nav_leaderboard, nav_records, nav_settings |
| Leaderboard | btnEmptyAction, btnRefresh, chip10k, chip5k, chipCountry, chipDistance, chipGlobal, nav_history, nav_home, nav_leaderboard, nav_records, nav_settings, segAll, segMonth, segWeek |
| Records | btnShare, nav_history, nav_home, nav_leaderboard, nav_records, nav_settings |
| Settings | cardProfile, nav_history, nav_home, nav_leaderboard, nav_records, nav_settings, rowAccount, rowAutoPause, rowGoal, rowKmAlert, rowLanguage, rowPace, rowRecreate, rowSensors, rowServer, rowSync, rowTheme, rowUnit |
| Notifications | btnBack, btnEmptyAction, btnRefresh |
| SensorCheck | btnBack |
| Detail(GPS) | actionDelete, actionImage, actionReplay, actionShare, badgeSync, btnBack, btnEdit, btnMore, btnShare, cardPhoto, mapSpace, rowMain, rowSecondary |
| Detail(manual) | actionDelete, actionImage, actionReplay, actionShare, badgeSync, btnBack, btnEdit, btnMore, btnShare, cardPhoto, mapSpace, rowMain, rowSecondary |
| Stats | btnBack, btnMore, chipElevation, chipPace, segCharts, segOverview, segSplits |
| Replay | btnBack, btnNext, btnPlay, btnPrev, speed1, speed2, speed4, speed8 |
| Edit | btnCamera, btnClose, btnGallery, btnSave, btnSaveTop, fieldDate, photoChip |
| AddManual | btnCamera, btnClose, btnGallery, btnSave, btnSaveTop, fieldDate, photoChip |
| ShareImage | btnBack, btnSave, btnShare, segPhoto, segRoute, segSquare, segStory |

## 4. Fix log

| Commit | What |
|---|---|
| `8798853` | A1–A6, A8, A9, A12, P1 (crashes, photo OOM, GPS provider, pickers, km/mile, remove photo) |
| `b6a47fd` | A7, A10, A11, K1, P2–P5, B1, B2 (dead controls, share/sync edge cases, main-thread work, backend) |
| `6f19c5d` | A13 + `ControlsAuditTest` / `TapEverythingTest` |
| last commit | sensor math tests (cadence extracted to a pure function), README, this report |

## 5. Test results after fixes

All suites were run **three times in a row** on 2026-11-12 (emulator Pixel 9 / Android 17, backend rebuilt with `make up`).
Every round had identical results — no flaky tests.

| Suite | Before | After (each of 3 rounds) |
|---|---|---|
| App JVM unit (`testDebugUnitTest --rerun-tasks`) | 32 passed | **41 passed**, 0 skipped, 0 failed |
| App instrumented (`am instrument`, with `e2eApiUrl`) | 12 passed | **24 passed**, 0 failed |
| Backend Go (`go test -count=1 ./...`) | api/auth/track ok | **api/auth/track ok** (+ `TestStatsMonthlyFollowsServerClock`) |
| Backend smoke (`scripts/smoke.sh`) | 29 / 29 | **29 / 29** |
| Lint | 0 errors, 225 warnings | **0 errors, 217 warnings** (DrawAllocation 8 → 0) |
| `gofmt -l` / `go vet` | clean | clean |

New tests: `UnitsTest`, `DeviceLogicTest`, `SensorMathTest` (JVM); `ScreenCrashTest`, `ImageStoreTest`, `PerfTest`,
`ControlsAuditTest`, `TapEverythingTest` (instrumented); `TestStatsMonthlyFollowsServerClock` (Go).
No existing test was removed, skipped or weakened.

A first full round (before the backend config override, see §6) had 1 instrumented failure and stopped the smoke
script at the S3 upload step, both caused by the stale LAN IP in `backend/.env`. Its output was not kept, so the failing
test name is not recorded; the three clean rounds above were run after the IP was corrected.

The instrumented tests use an in-memory database and a separate prefs file; `ControlsAuditTest`/`TapEverythingTest`
set the real `onboarded` flag for the run and restore it afterwards. `connectedDebugAndroidTest` was **not** used,
because it uninstalls the app and would wipe the owner's data on this emulator.

## 6. Not verified / remaining risk

Stated plainly so nobody reads this audit as "zero bugs, proven". It is not.

**Not checked on a real phone (emulator only).** The emulator has no step counter, barometer or ambient-temperature
sensor, and its GPS is simulated. Code paths for "sensor missing" were exercised; real readings were not.
How to check on a device: run 1 km outdoors → Cadence shows 150–190 spm while running and `--` when standing;
Sensor check lists Step Counter as Ready after granting "Physical activity"; screen-off for 5 min during a run
keeps distance growing (the service test covers this logic, not a real Doze cycle).

**Not run end-to-end by hand on this emulator:** a real GPS recording through the UI (start → pause → hold-to-stop).
Reason: the emulator holds the owner's own app data and a test recording would be saved into it.
Covered instead by `RunTrackingServiceTest` (service, screen off), `LiveRunStateTest` (auto-pause, batched fixes)
and `SyncEndToEndTest` (offline → sync → photo → share → edit/delete → pull on a new device).

**Account-only controls** (change password, logout, delete account, country, leaderboard opt-in, updates alerts)
are hidden in Guest mode, so `TapEverythingTest` did not tap them. Their server side is covered by Go tests and smoke.

**Share-code race (B1)** is fixed by reasoning; the race was not reproduced in a test.

**Lint warnings left on purpose:** `LockedOrientationActivity` (portrait lock is a design decision; Android 16+
ignores it on large screens), `UnusedResources`, `Overdraw`, `UseCompoundDrawables`, `DisableBaselineAlignment`,
`PluralsCandidate` (Thai has no plural forms) — none affect behaviour.

**Environment note.** `backend/.env` contains the Mac's LAN IP. During this session the Mac moved networks
(172.25.83.16 → 172.20.10.2), so presigned photo URLs pointed at a dead address and the first full test round failed at
the S3 upload step. `.env` was **not** edited; tests ran with a temporary compose override in a scratch folder.
To fix it permanently, update `HOST_IP`, `PUBLIC_BASE_URL` and `S3_PUBLIC_ENDPOINT` in `backend/.env`, then `make up`.
