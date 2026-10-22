# Liuying logo v2: process and critique

Brief: a completely different mark from v1 (brush enso + vermilion chop). Nothing ring-like, no square seal as the idea, none of the
rejected v1 ideas unless fundamentally reinterpreted. Everything drawn from scratch as paths (no font dependency); the only font
outlines are the two characters 流影 (Noto Serif CJK SC SemiBold, SIL OFL), so the characters are correct by construction.
Tools: parametric Python (ribbons with variable width, raster boolean ops traced back to vector) rendered with Chromium.
Sheets: sheet-contact-sheet.png (everything at 160/96/48/32/24/16 px, mono, circle and squircle masks), concepts-1-*, concepts-2-*.

## Round 1: nine concepts (concepts/*.svg, concepts-1-nine-first-round.png)
| | Concept | Honest verdict |
|---|---|---|
| c1 | Shadow-theatre (皮影) screen: puppet silhouette on a lit paper screen | Best silhouette: a bright rounded window with a dark figure survives 24px and is unlike any streaming icon. Figure was crude (totem-like). Shortlisted. |
| c2 | Paper-cut lantern with windows of light | Clean, reads at 24px. But a lantern is the Chinese-restaurant cliche and says nothing about video or shadow. Rejected. |
| c3 | 流 glyph with a drifting shadow | Correct character (font outlines) but 10-stroke glyph + 5 echoes is mud below 48px, and a character as logo reads like a localised app name. Rejected. |
| c4 | L with offset translucent shadow | Perfect legibility at 16px, but it is "an italic L with a drop shadow": any app could own it. Rejected as too generic. |
| c5 | Film strip bending like a river into an L | First draft looked like a boot (tapered tail). Idea (L + film + river) kept and redrawn as B2. Shortlisted. |
| c6 | Cloud scroll (祥云) double volute | Reads as spectacles / "CC" at small sizes and as a hotel logo large. Rejected. |
| c7 | Shanshui mist layers + moon | Lovely tile, but at 32px it is three smudged bands and a dot: identity lives only in colour. Also leans on the moon, which v1 already exhausted. Rejected as an icon, could be an onboarding illustration. |
| c8 | Kite with ribbon tail | Pleasant and unique, but reads children's / toy app; tail turns to noise below 48px. Rejected. |
| c9 | Interlocking square hooks (回纹 meander) | Reads as a "2" or a "G". Rejected. |
A tenth sketch (koi with a shadow fish) was dropped before the sheet: tail fins tangled and fish icons are plentiful.

## Round 2: shortlist and redesign (concepts-2-shortlist-redesign.png, sheet-shortlist.png)
### A2 Shadow-theatre screen
Redesign notes: figure rebuilt as a profile puppet (3/4 headdress fan, defined face, nipped waist, flared robe with pierced diamond
cut-outs, a control rod). The long water sleeve (水袖, literally "flowing") leaves the screen and the colour inverts at the frame
edge: dark on gold inside, gold on dark outside. That single device carries 流 (flow) and 影 (shadow/light) and is what makes
the mark memorable. A faint halo ring suggests light spilling from the screen. Separate small optical size (A2s: no rod, no holes,
no halo, 3 plumes, heavier sleeve) for 16 to 32px.
- Legibility: 96/48 clear; 32 reads as a lit window with a figure; 24/16 as a bright rounded rect with a dark figure (small size). Acceptable, not heroic.
- Distinctiveness: high. No streaming brand uses a figure or a screen-and-sleeve idea.
- Cultural accuracy: shadow-puppet theatre is a real Chinese tradition (皮影戏) and the figure is a generic opera-style character (no deity, no specific role). Pierced openwork and rods are authentic details. A puppet specialist might want a more traditional head type: the face is intentionally simplified.
- Trademark: no resemblance to Netflix, Stremio, Nuvio, Plex, Kodi, Jellyfin or Disney+ marks (none use a figure, a lit rectangle or a flowing sleeve).
- Dark/light/mono: works as gold-on-ink, paper-on-vermilion, ink-on-paper (the screen goes dark, "a night performance"), and mono (true holes, sleeve outside the frame stays).
- Weakness accepted: more detailed than a typical app icon; a tall rectangle plus a sleeve is a wide-ish composition so the adaptive icon version must be smaller (about 81% of the tile scale) to stay in the 66dp safe zone.

### B2 Film-strip L
Redesign notes: constant-width strip, big outer / small inner bend radius (a river bend), sprocket holes both edges, strip ends
flat and dissolves into three dots (vermilion) as the "drifting shadow".
- Legibility: the best of the set, at 16px it is simply an L. Mono is excellent.
- Distinctiveness: medium. "Film strip" is a stock streaming symbol and the L is a plain letter; the dots help but it still reads as a tidy generic.
- Cultural link: none beyond the name; no 影 or 流 meaning that a viewer can read without being told.
- Trademark: nothing close, but film-strip-L is the kind of mark that collides with some other media app.

## Decision: A2
The owner asked for a distinct, memorable, contemporary silhouette that is clearly not the ink circle + chop. B2 is safer at 16px
but is the sort of mark you forget. A2 is the only one where the picture is the name (flowing shadow) and the silhouette is
immediately ownable. Its small-size weakness is solved by the optical-size variant (A2s) and by always pairing it with the
wordmark where space allows.

## Wordmark
Hand-built geometric lowercase with a capital L: constant 15-unit stroke, round bowls, slanted L foot (the same cut as a puppet
cut-out), diamond i-dots (echoing the pierced diamonds on the robe), the y and g tails flick left like a sleeve. Pure paths, no font.
Stacked/horizontal lockups add 流影 (font outlines, correct simplified characters, read left to right 流 then 影).

## System
- logo-mark(.svg): gold on ink tile; -light (ink on paper); -vermilion (paper on vermilion); -transparent; -mono / -mono-light.
- logo-mark-small.svg: optical size for favicons and the Android top bar.
- logo-wordmark, logo-wordmark-light, logo-wordmark-only, logo-stacked, logo-stacked-light (svg + png).
- logo-mark-1024.png and 512 exports; logo-1024.png copy.
