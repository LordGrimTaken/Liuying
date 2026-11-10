# Improvement log

One entry per cycle. Each entry says what was measured before, what changed, and how the change was
verified. Numbers are from this machine unless a line says otherwise.

## Cycle 1 — 2026-11-09 — make the test suite part of CI

**Measured (before):**

| Suite | Result |
|---|---|
| `cloudflare` (`npm test`) | 59 tests, 0 failures |
| `site/watch` (`node --test test/*.mjs`) | 102 tests, 0 failures |
| `server` (`npm test`) | 8 tests, 0 failures |
| Android (`:app:testDebugUnitTest`) | **39 suites, 258 tests, 0 failures, 4 skipped** — but only runnable by hand |

**Problem:** the only CI job for the app built a debug APK and never ran the unit suite, so a red test
still produced a green build and an artifact.

**Changed:**
- `.github/workflows/android-debug.yml` — new `test` job runs `:app:testDebugUnitTest`; the APK job
  `needs: test`, and a failing suite uploads the HTML/XML report. Added a `concurrency` group so a
  superseded push cancels the older run.
- `gradlew` and `scripts/release-android.sh` had CRLF line endings, which makes `./gradlew` fail on Linux
  with `required file not found`; normalised to LF.
- `desktop/dist-mac/` (a build artifact from the Windows session) removed and ignored.

**Verified locally:** `:app:testDebugUnitTest` on Linux — 39 suites, 258 tests, 0 failures (6m 06s cold).
**Verified in CI:** run `42755431833` — `test` job success (4m 41s), `build` job success (1m 37s), APK
uploaded. The build no longer starts until the suite passes.

Two runner traps were fixed on the way: `android-actions/setup-android@v3` fails with *"Wrong version in
preinstalled sdkmanager"* (expects cmdline-tools 12266719, the image ships 20.0), so the jobs install the
packages with the preinstalled `sdkmanager`; and `gradlew`/`release-android.sh` had CRLF endings, which
makes them fail on Linux.

## Cycle 2 — 2026-11-10 — sync retries itself with backoff

**Measured (before):** a failed sync round only set the status line. `syncSoon()` was called on local
changes (library 1.5 s, progress 20 s), so after an offline blip the account stayed stale until the user
changed something or relaunched — and a 429 carrying `Retry-After` was ignored.

**Changed:**
- `domain/sync/RetryPolicy.kt` — pure schedule: retry only for `http == 0` (never reached the server),
  408, 429 and 5xx; delay 30 s doubling to a 15 min cap; a server `Retry-After` wins but is clamped to
  5 s…15 min so a bad header cannot spin or stall the app.
- `data/sync/SyncManager.kt` — counts consecutive failures, schedules `syncSoon(nextDelay)` after a
  retryable failure, and resets the counter on success. Non-retryable 4xx failures are not retried.

**Measured (after):** 40 suites, **262 tests**, 0 failures, 4 skipped (was 258) — the four new tests cover
the retryable set, the doubling/cap table, negative attempts, and `Retry-After` clamping.

**Next candidate:** the same "fail silently" pattern exists for the watch-party poll and for update
checks; and the shelf still never marks a series Completed (backlog L005).

## Cycle 3 — 2026-11-10 — stop the QuickJS teardown race

**Measured (before):** `QuickJsPluginRunnerTest.aLeftoverTimerDoesNotDelayTheAnswer` failed in **2 of 3 CI
runs** (`42756847245` first attempt, `42759560788`), always with *"Cannot get jni env because the vm is not
cached"*, and always passed locally in isolation. Two attempted fixes made every plugin test fail and were
reverted (async close on the VM executor; `submit().get()`).

**Hypothesis:** the native engine was closed in `finally` while a host callback (the leftover timer, an
in-flight host sleep) could still be running against it; under CI's scheduling that callback lands after
`close()` and the JVM binding reports the VM as no longer cached.

**Changed:** `job.cancel()` now also **joins** the run's coroutines, bounded to 2 s (`NonCancellable` +
`withTimeoutOrNull`), before `js.close()`. A run that cannot stop in that window still closes on a helper
thread, so a stuck engine cannot block the caller.

**Measured (after):**
- plugin suite locally: **3/3** runs green;
- full suite locally: 40 suites / **262 tests** / 0 failures / 4 skipped;
- CI `42763071466`: **green**, then **two reruns of the same commit: green** (3/3, against 1/3 before).

**Next candidate:** the watch-party poll has the same silent-failure shape as the old sync (it stops on a
404/401 but a transient network error just waits for the next tick); then backlog L005, a series never
reaching "Completed" on the shelf.

## Cycle 4 — 2026-11-10 — a failing watch-party poll backs off

**Measured (before):** the poll loop slept `PartyLogic.POLL_MS` (2 s) after every result, success or failure. A
phone in a room with no connection therefore made ~1 800 requests an hour and woke as often, while the only
feedback was the same "Connection lost. Retrying..." line.

**Changed:**
- `PartyLogic.pollDelayMs(failures)` — a healthy room keeps 2 s; consecutive failures double the wait
  (2 s, 4 s, 8 s, 15 s cap). Pure, with custom bounds for tests.
- `PartyController` counts consecutive failures, resets on success and on leaving a room, and delays by that
  value. Fatal codes (`not_found`, `forbidden`, `unauthenticated`) still end the room immediately.

**Measured (after):** **265 tests** (was 262; +3 covering the base interval, the doubling/cap, and custom
bounds), 0 failures, 4 skipped. A four-failure outage now costs ~240 requests an hour instead of ~1 800.

**Note:** the earlier note that the poll swallowed transient failures was wrong — it already kept polling with a
warning. The real defect was the missing backoff, which is what this cycle fixed.

## Known issue — flaky QuickJS plugin test in CI

`QuickJsPluginRunnerTest.aLeftoverTimerDoesNotDelayTheAnswer` failed on the first CI run of cycle 2
(`42756847245`, "Cannot get jni env because the vm is not cached") and passed on an immediate re-run of the
same commit. It passes locally in isolation. The message comes from the JVM build of the engine when the
native VM is touched after `close()`; a leftover timer plus a fast machine seems to hit it.

Two attempted fixes were reverted because they made every plugin test fail:
1. closing the VM asynchronously on its own executor — the next run then raced the close;
2. `submit().get()` to keep ordering — still failed.

Next attempt should be: `job.cancelAndJoin()` (bounded) before `js.close()`, so no host callback can still be
running when the VM is closed. Until then the gate is honest but occasionally red; re-run the job.
