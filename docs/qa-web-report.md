# Web player QA report

Scope: `site/watch/` (UI), `server/` (static serving, `/api/auth/me`). Method: real server (pg-mem) on :4191, mock add-on harness, an edge-case add-on (long, CJK, RTL, XSS-shaped, missing-art titles, a 1,800-episode season), real Cinemeta data (via a proxy-backed route; CORS `*` confirmed), a Playwright route crawler (17 routes x 5 widths, dark and paper+text-L), a click-everything crawler (about 380 controls on 13 routes), keyboard/focus probes, scale probes (1,500-item shelf, 5,000-title catalog) and cold-load throttling.
Browsers: only Chromium exists in `/opt/pw-browsers`. Firefox and WebKit were NOT tested.
Not completed: offline mode run, reduced-motion audit, RTL layout screenshots beyond the details page, and a leak test with a controlled final route (heap stayed flat around 7 MB across 200 navigations, node counts were not comparable). These remain unverified.
Regression coverage: `site/watch/test/router.test.mjs`, `ux.test.mjs` (fmtBytes), `server/test/qa-web.test.js`, and `site/watch/test/e2e/qa_regress.py` (9 browser checks, run with `edge-addon.js`).

Severity counts (36 findings): High 0, Medium 14, Low 18, Info 4. Fixed 25, open 11.

## Findings

| # | Sev | Finding (repro, expected / actual) | Fixed |
|---|-----|------------------------------------|-------|
| 1 | Med | Modal dialogs (poster action sheet, confirms) do not trap Tab. Right-click a poster, press Tab: focus lands on the nav behind. Expected: stays in dialog, page inert. | Yes (Tab trap + `inert` on shell) |
| 2 | Med | Open `/watch/#/title/...` from another site, press the in-app Back arrow: leaves liuyingapp (`history.length>1`). | Yes (`goBack` only uses history after an in-app navigation) |
| 3 | Med | Player Exit pushed a new Title entry, so browser Back returned to the player and restarted playback. | Yes (replace) |
| 4 | Med | Shelf with 1,500 titles: 5.6 s to first card, 27k DOM nodes (also O(n*m) progress lookups). | Yes (progressive render, one progress map). Now 0.6 s, 1.2k nodes |
| 5 | Med | Browse of a 5,000-title catalog built every card: 61k nodes after a few scrolls. | Yes (60 cards at a time). Now about 6k nodes |
| 6 | Med | Series season with 1,800 episodes rendered all rows: 23.5k nodes. | Yes (60 at a time, "Show more", up-next always visible) |
| 7 | Med | Browse grid: a long unbroken title blew the track open, one poster filled the screen. | Yes (`min-width:0`, `overflow-wrap`) |
| 8 | Med | Streams page: 167 px horizontal overflow with a long unbroken title (hidden by `body{overflow-x:hidden}`). | Yes |
| 9 | Med | Hero with a long title grows upward under the top bar and clips the eyebrow. | Yes (3-line clamp) |
| 10 | Med | Paper theme: today's date tile on Calendar is white on cream (invisible). | Yes |
| 11 | Med | Paper theme: HDR / OK / warning badges and "not playable" notes 1.6-1.8:1. | Yes (theme-specific colours) |
| 12 | Med | Paper theme: gold text on dark artwork overlays (resume tag, rating chip, hero eyebrow) 2.6-3.7:1. | Yes (fixed light gold) |
| 13 | Med | Paper theme: hero base used the light surface, so light hero text vanished while artwork loads or fails. | Yes |
| 14 | Med | `hls.min.js` (414 KB raw) loaded on every page, delaying first view on slow links (cold 3G profile 17.5 s). | Yes (loaded when an HLS stream plays; still precached for offline) |
| 15 | Low | Focus lost to `<body>` after redraws: details More/sort/mark, shelf chips, source toggles, genre chips, stream sort. | Yes (`keepFocus`) |
| 16 | Low | A red `401` console error on every signed-out page load (`/api/auth/me`). | Web player: yes (`?soft=1`). `site/auth.js` pages: open |
| 17 | Low | Unknown URLs return bare plain text (and a CSP console error in Chrome's text viewer). | Yes (branded 404 for browsers) |
| 18 | Low | Tap targets under 44 px: top-bar buttons 42, avatar 36, hero dots 18x34, pause 34, "See all" 31, link buttons 33, chips/small buttons 34-36, switches 26. | Yes (hit areas enlarged; coarse-pointer sizing) |
| 19 | Low | Each poster has 3 tab stops (about 90 per row). | Yes (quick/more reachable via context-menu key / long press) |
| 20 | Low | White on primary-hover red 4.05:1; brand seal 4.34:1. | Yes |
| 21 | Low | Sign-in banner floats over content on details, streams, player, settings. | Yes (hidden there) |
| 22 | Low | Bottom nav labels 9.5 px. | Yes (10 px) |
| 23 | Low | Stream size under 1 MB shows "0 MB". | Yes (KB) |
| 24 | Low | Browse back arrow always goes to Today. | Yes |
| 25 | Low | Streams "Back to details" pushed a duplicate history entry (Back ping-pong). | Yes |
| 26 | Low | Series with a negative season defaults to "Season -1" and Play S-1 E1. | No |
| 27 | Low | Text size S/L is `zoom` on `#view` only: top bar, nav, dialogs and search overlay do not scale. | No |
| 28 | Low | Calendar day labels hardcode `en-US`; details use the browser locale. | No |
| 29 | Low | Desktop top bar repeats Calendar and You, already in the rail. | No |
| 30 | Low | Browse does not restore scroll position on Back (list is refetched). | No |
| 31 | Low | Cold load pulls 34 unbundled ES modules (waterfall); no `modulepreload`. | No (partly mitigated by #14) |
| 32 | Low | `site/login|signup|account|download`: nav links 16 px tall, brand 33 px, show-password button 42 px, seal contrast 4.34, `/me` still logs 401. Outside the allowed paths. | No |
| 33 | Low | No `robots.txt`, `sitemap.xml`, `/favicon.ico` on the origin. | No |
| 34 | Info | Click crawl: no no-op controls, no dead links, no uncaught exceptions, no CSP violations (apart from #16 and #17). | n/a |
| 35 | Info | `body{overflow-x:hidden}` hides real overflow bugs from document-level checks; audits must be element-level. | n/a |
| 36 | Info | Warm load: FCP 0.27 s, LCP 1.6 s, CLS 0; 4x CPU + 3G: LCP 2.6 s. Heap stays flat (about 7 MB). XSS-shaped titles, `javascript:` posters, RTL, CJK, emoji and missing art all render safely. | n/a |

## Critique (design review)

- Hierarchy: Today is strong. One hero, then Continue Watching, then rows, with a serif/sans pairing that reads as a brand. Weak spots are secondary: the top bar repeats rail destinations on desktop (#29), and the sign-in banner competed with content on task pages (#21).
- Spacing and density: rows and cards are consistent. Small controls (chips, `btn sm`, link buttons) are visually fine but were physically too small on touch (#18). The streams table is the densest screen: four muted columns with 11-12 px labels. It works, but quality and size need more weight than the source name.
- Copy: clear and honest ("Not playable in the browser, use the Android app"). Source errors say which source failed. "Listed" for the hero button is odd next to "Saved" on details; pick one verb.
- Empty states: good (large icon, one action). The calendar empty column says "Nothing scheduled..." but does not say that only shelf series appear; the lede does, so repeat it there.
- Loading states: skeletons on rows and details are good. Streams show per-source spinners. The 404 and cold-start states were the weakest and are now branded or lazy.
- Error states: per-row retry cards are the right pattern. Failures on one source never block others.
- Themes: dark and black are polished; paper had several unreadable spots (#10-#13), fixed. Re-check after any accent or theme change, since colours are set in many places.
- Accessibility: landmarks, skip link, focus rings, labelled controls and `aria-pressed` states are all present. The remaining structural gap is nested buttons inside poster anchors (invalid ARIA; now removed from tab order but still nested).
