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

**Verified:** the workflow runs the same command that passed locally (258/258). CI result is the
measurement for this cycle; it is recorded in the next entry.

**Next candidate:** the app has no local device, so the next cycles should target logic that unit tests can
prove — stream selection, sync merge, progress rules — before any UI work.
