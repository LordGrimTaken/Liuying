# Liuying logo: process and critique

Brief: replace the plain seal (two Georgia glyphs on a red square) and the generic launcher icon with an original,
artful system. Palette: ink #14110F, paper #F2E9DA, vermilion #C8372D, gold #C9A45C. Everything is drawn as paths
(no font dependency at runtime). The characters are outlines of Noto Serif CJK SC Bold (SIL OFL), a Song/Ming
printing face, which is a legitimate modern seal style (宋体印); true seal-script (篆书) was judged too risky to
hand-draw without a calligrapher, since a wrong stroke in a Chinese brand name is worse than a plain correct one.

## Round 1: six concepts (concepts-1-first-six.png)
| | Concept | Verdict |
|---|---|---|
| A | Carved chop, 流影 side by side | Correct and legible, but this is what we already had. Kept as the seed of the final seal. |
| B | Ink enso with a play triangle in the gap | Beautiful stroke, but enso + play triangle reads as a generic "video player" icon. Enso itself kept. |
| C | Radicals 氵 + 彡 around a play mark | Clever (both characters carry a three-stroke radical) but cluttered; drops read as leaves. Rejected. |
| D | Moon gate with water ripples and play | Stock "streaming" look; the gold ring is fussy at 24px. Rejected. |
| E | Folding screen with a flowing band | Weak silhouette, no link to the name at small sizes. Rejected. |
| F | Vermilion disc with trailing shadow streaks | Distinctive, but reads as a flag or comet. Rejected. |

## Round 2 (concepts-2-second-round.png, concepts-2b-moon-explorations.png)
- A2 vertical chop: first version overflowed the cell (em box vs ink box); fixed by fitting each glyph's ink bbox to a cell.
- G1 moon + three streaks (a nod to 彡): looked like a jellyfish. Rejected.
- G2 three river strokes (川): read as "heat / steam" icon. Rejected.
- H1 moon + sliced reflection: read as a lightbulb or user avatar. Rejected.
- H2 moon melting into water: a weather icon. Rejected.
Lesson: pictorial moon/water ideas are all stock icons. The two assets with real character were the carved chop (cultural
anchor) and the enso brush stroke (movement). Combining them follows the classic ink-painting composition: a brush
circle signed with a red chop.

## Round 3: enso + chop (concepts-3-enso-chop-refinement.png)
First draft critique:
1. Chop characters touched the cell edges and each other. Fixed with exact bbox fitting (0.56 x 0.355 cells).
2. Ring too thin on the right; at 16px the tail vanished. Increased minimum width and slowed the taper.
3. Chop too small to hold its identity at 24px. Enlarged to 156/512 and made it overlap the ring tail, like a stamp laid over a brush stroke.
4. Rectangular chop looked stock. New silhouette: opposite corners swept (large radius top-left and bottom-right, small on the other two), a leaf/flowing corner that is also the shape language for avatar tiles and buttons.
5. Added a vermilion offset copy of the ring at 42% opacity: the ring casts a shadow (影) while the brush flows (流).

Second critique of the refined version:
- Silhouette at 16/24px: a paper arc with a red dot: distinct from every streaming app we know, no triangle, no wordmark letter. Pass.
- Dark and light: works as paper-on-ink and ink-on-paper; the chop keeps its paper-coloured characters on both.
- Monochrome (themed icons): chop knocked out of the silhouette with evenodd holes so characters stay legible. Pass.
- Characters: 流 above 影, read top to bottom (the unambiguous vertical order of a two-character seal). In a horizontal chop the traditional order is right to left (流 on the right), which is why concept A put 流 on the right; the vertical stack avoids confusing modern left-to-right readers.
- Weakness accepted: the brush hairlines (dry-brush holes) vanish below ~48px, which is intended; they are detail for large sizes only.
- Optical size: favicon 16px uses the chop with 流 alone (logo-seal-small.svg); 32px and up uses 流影.

## System
- logo-mark(.svg/-light/-transparent): ring + shadow + chop. Launcher icon, PWA icons, social card.
- logo-seal(.svg), logo-seal-small, logo-seal-mono: the chop alone. Top bar, favicon, page headers.
- logo-wordmark, logo-stacked (+ -light): lockups with "Liuying" in Noto Serif CJK SemiBold outlines.
- logo-mono(-light): single colour.
- Regenerate with the scripts in the session scratchpad (lib.py, enso.py, build.py, build2.py); parameters are RING, CS, CC, ROT.
