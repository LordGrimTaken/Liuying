# Contributing

Thanks for wanting to help. Liuying is a small project; the fastest way to get a change in is to keep it
focused and verifiable.

## Before you start

- Read [README.md](README.md) for the layout and the build commands.
- For anything larger than a typo, open an issue first and describe what you plan to change and why.
  A short discussion usually saves a long review.
- By contributing you agree that your work is licensed under the project's [MIT license](LICENSE).

## Setting up

| Part | Command |
|---|---|
| Android | `./gradlew :app:assembleDebug` |
| Android tests | `./gradlew :app:testDebugUnitTest` |
| Worker (Cloudflare) | `cd cloudflare && npm ci && npm test` |
| Express server (legacy) | `cd server && npm ci && npm test` |
| Web player | serve `site/` with any static server |

You need JDK 17+, Android SDK platform 36 with build-tools 36, and Node 20+.

## What a good change looks like

- **One concern per pull request.** A bug fix and a refactor in the same diff is two pull requests.
- **Tests.** Logic that can be tested in a JVM unit test belongs there: `app/src/test/...`. Server and
  Worker changes need a test in their existing `node --test` suites. If a change genuinely cannot be
  tested, say why in the description.
- **Pass the suites you touched.** Run them yourself before opening the pull request; CI is a
  confirmation, not a first attempt.
- **Match the surrounding code.** Kotlin formatting is the standard IntelliJ/Kotlin style; the web player
  is plain ES modules with no build step; no new dependencies without a reason in the description.
- **No secrets.** Never commit `keystore.properties`, `*.jks`, tokens, `.env` files or a signing key.
  `local.properties` is machine-specific and ignored.

## Commit messages

Short imperative subject, then a body when the reason is not obvious:

```
Fix stream sheet losing its selection after rotation

The selected stream was remembered by index, which shifts when the
quality filter is applied. Remember it by url instead.
```

## What we will not merge

- Anything that bundles, hosts or links to media content, or that circumvents DRM or paywalls.
- Analytics, trackers, ads or anything that phones home without being asked.
- Changes that break the "works signed out" and "no account required" guarantees.
- Silent changes to release signing, versioning or the Play build type without discussion.

## Reporting bugs

Include the app version (Settings → About), Android version and device, what you expected, what happened,
and the steps to reproduce. For playback problems, name the source type and whether the stream plays in
another player. Do not paste personal URLs or credentials.

## Security

If you find a security problem, do not open a public issue. Describe it privately to the maintainer first
and give them a chance to fix it before any disclosure.

## Review

Every pull request is reviewed for correctness, test coverage and scope. Expect questions rather than a
silent merge; small, clear changes move fastest.
