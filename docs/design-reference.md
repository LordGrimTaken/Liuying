# Liuying design reference (web player pass 4)

The web player (`site/watch`) follows a modern streaming-app layout with Liuying's own ink-and-seal identity. The Android app should use the same patterns and tokens so both feel like one product. Everything below is implemented in `site/watch/app.css`, `ui/hero.js`, `ui/catalogPage.js` and `main.js`.

## Identity (unchanged)

| Token | Value | Use |
| --- | --- | --- |
| ink | `#14110F` | page background |
| surface / surface2 | `#1D1916` / `#26201B` | cards, skeletons |
| paper | `#F2E9DA` | text on ink, light theme background |
| vermilion (accent) | `#C8372D` (hover `#E0493D`) | primary buttons, active nav pill, active tab underline, avatar |
| gold | `#C9A45C` | hairlines, eyebrow text, rating badge |
| mute | `#B3A893` | secondary text, inactive nav |
| Logo | the seal: vermilion rounded square with 流 / 影 | top bar (left), desktop rail |
| Type | serif (Georgia family) for titles and headings, system sans for UI and body | |
| Language | English UI text, Chinese aesthetic | |

Accent can be swapped (vermilion, gold, jade, indigo); every accent use goes through one token so the pill, tab underline, Play button and avatar all follow it.

## Spacing, radii, sizes

- Page gutter: 16 dp on phones, 28 dp from 700 px wide. Rows bleed to the screen edge and start at the gutter (`scroll-padding` = gutter).
- Vertical rhythm: row spacing 30 dp, heading to content 12 dp, hero actions 18 dp below synopsis.
- Radii: poster and thumbnail 8; button 12 (hero action buttons 14); card 12; dialog 18; chip, avatar-pill and nav pill fully rounded (nav pill 16); avatar 11 (rounded square); the seal stays at 3.
- Top bar: 58 dp plus the status-bar inset. Icon buttons 42 dp (24 dp glyph, stroke 1.7). Avatar 36 dp.
- Bottom nav: 64 dp plus the gesture inset; each item is flex 1, icon 22 dp over a 10 sp label.
- Hero: 62% of the viewport height on phones (min 440, max 680 dp), 72% on wide screens (min 500, max 720).
- Hero action buttons: three equal columns, 50 dp tall, 10 dp gap, max width 520.
- Poster width: 118 dp phone default (100 small, 148 large), 2:3. Continue-watching cards are 16:9, 72% of the screen width (max 270).
- Section heading: serif bold 22 to 28 sp. Hero title: serif bold 32 to 58 sp, line height 1.04.

## Patterns

1. **Top bar** (fixed, translucent). Seal plus the Liuying wordmark on the left, round icon buttons on the right: Search, Calendar, Settings, then the account avatar. Over artwork it has only a dark top-down scrim; after 24 dp of scroll, or on any page that does not start with artwork, it becomes a blurred solid bar (200 ms fade between the two). Signed in, the avatar is a vermilion rounded square showing the first letter of the username with a small sync dot (green synced, gold syncing, red offline or error). Signed out it shows a person glyph and opens sign in.
2. **Hero** (Today, Movies, Series). Full-bleed artwork fills the top of the screen under the translucent bar and fades into the page colour. Text sits bottom-left: gold eyebrow, large bold serif title, a line with year, a bordered type chip and up to two genres, a three-line clamped synopsis, then the three action buttons: **Play** (accent filled), **List** (toggles to Listed with a check) and **Details**, the latter two translucent with a blur. Below the buttons: pause control and pill pagination dots on the left, the rating badge (star plus rating, gold outline) floating on the right. Slides crossfade over 900 ms, the text block rises 10 dp and fades in over 500 ms with a small stagger, rotation every 8 s (stops on hover, focus, pause, or reduced motion), swipe left or right changes slide.
3. **Genre tabs** (Movies, Series). A horizontally scrollable strip with an "All" tab first, then the union of the genre options from that type's catalogs. The active tab is brighter and bold with a 3 dp accent underline that scales in over 280 ms. Rows below are the first catalogs of that type (up to six), each with a big heading, a short source subtitle and "See all". Choosing a genre reloads the rows with that genre and keeps the hero.
4. **Bottom navigation** (phones). Seven items: Today, Movies, Series, Shelf, Calendar, Search, You. Inactive items are muted icon plus label. The active item sits in a rounded pill (radius 16) tinted with the accent at 24% opacity, with accent-coloured icon and label and a semibold label. Search opens the search overlay instead of a page; You opens Settings (account, appearance, sources). On desktop the same items become a left rail with Sources added, and the top bar loses the wordmark.
5. **Empty states.** Centred in the page: 84 dp muted icon (60% opacity), bold serif title, one or two lines of hint (max 36 characters wide), one accent button. Example: Shelf, "Your shelf is empty" / "Add movies and series to your shelf with the + button." / "Browse titles".
6. **Sign-in nudge.** A compact floating card above the bottom nav (not a banner that pushes content). Dismissible and remembered.
7. **Posters.** No overlay controls on touch screens; long press opens the action sheet. Progress bar (3 dp, accent) along the bottom for partly watched titles.

## Motion

| Use | Duration | Curve |
| --- | --- | --- |
| press feedback (buttons, posters, nav) | 120 to 200 ms, scale 0.94 to 0.97 | ease-out |
| colour, background, border changes | 200 ms | ease |
| nav pill, tab underline, top-bar fade, page content | 280 to 320 ms | `cubic-bezier(.2,.7,.3,1)` |
| route change | 320 ms: new page fades in and rises 10 dp (only on navigation, never on background refresh) | same |
| image fade-in after decode | 400 ms | same |
| hero crossfade | 900 ms (text 500 ms) | linear / same |
| skeleton shimmer | 1.4 s loop, left to right | linear |

Rules: rows use scroll snapping with momentum; reserve space (skeletons, aspect-ratio) so nothing jumps when content arrives; images are lazy and fade in; every animation is removed when the system reduced-motion setting or Liuying's own Reduce motion setting is on.

## Page map

- **Today**: hero, Continue Watching, one row per home catalog, Refresh button.
- **Movies / Series**: hero, genre tabs, rows of the first catalogs of that type.
- **Shelf** (My List): Continue Watching, filter toolbar, grid or list; empty state as above.
- **Calendar**, **Settings**: standard pages under the solid top bar.
- **Search**: full-screen overlay opened from the nav, the top bar or the `/` key.
