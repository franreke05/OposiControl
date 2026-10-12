# News rendering contract (OposiControl editor -> OposiBot Home banner)

Status: the editor side is implemented; the consumer side does not exist yet. OposiBot shows a static image in its Home
banner and there is no app-facing news route (`GET /api/v1/app/noticias`, D4-18, is PRODUCT_BLOCKED by DEC-030). This
document fixes what OposiControl writes and how it paints it, so that the app can reproduce a published news item pixel
for pixel when that route and the banner are connected. Nothing here requires a schema change.

Source of truth in code: `features/news/.../editor/NewsDocument.kt` (canvas and unit constants),
`features/news/.../presentation/NewsTemplateArt.kt` (template colours and artwork) and
`features/news/.../presentation/NewsCanvas.kt` (`NewsCanvasSurface`, the painter).

## 1. Where it is shown

The Home banner of OposiBot (`HomeAdBanner.kt`, `BannerAtomo`, Figma node 59188:22663):

| Device | Banner size | Scale from canvas |
|---|---|---|
| Phone | 358 x 148 dp | 0.25 |
| Tablet | 704 x 291.04 dp | 704 / 1432 = 0.4916 |

Frame: corner radius 8 dp at 358 dp wide (scale it with the width) and a 0.5 px border of white at 40 % opacity.

## 2. Canvas and units

- The canvas is **1432 x 592 px**: the phone banner at 4 px per dp. It is a constant of this contract, the same for every
  news item; it is not stored in the row (the table has no column for it).
- Every layer coordinate, size, font size and inset below is in canvas px. The app multiplies by
  `bannerWidthDp / 1432`. A layer at `x = 64` is 16 dp from the left edge on a phone.
- 1432 x 592 is above the 800 x 500 floor that the mobile admin editor still assumes.

## 3. What a row carries

Canonical record (`/api/v1/admin/noticias/{id}`): `titulo`, `texto`, `capas` (array), `tipoFondo` (`A` | `B` | `C`),
`backgroundPath` / `backgroundUrl`, `imagenPath` / `imagenUrl`, `estado`, `publicarDesde`, `publishedAt`, `version`.

A consumer feed needs at least: `id`, `titulo`, `texto`, `capas`, `tipoFondo`, `backgroundUrl`, `imagenUrl`,
`publishedAt`.

Visible to users (current read policy of table `noticia`): `estado = 'published'` and (`publicar_desde` is null or
`publicar_desde <= now()`). A `scheduled` row never becomes visible by itself: there is no publishing job (see
`PRODUCT_DECISIONS_PENDING.md`, section 6). The editor says so and offers "Publicar ahora".

Not part of the banner: `texto` (body of the news item, for a detail view that does not exist yet) and `imagenUrl`
(thumbnail for lists). There are no link or call-to-action columns: what the banner's "Ir" button opens is undefined.

## 4. Background

1. If `backgroundUrl` is present: the image fills the canvas with centre crop, then a black veil at 28 % opacity over it.
2. Otherwise the template:
   - linear gradient from top-left to bottom-right: `#1C2535` -> `#141B26` -> `#0E141D`;
   - horizontal veil from the left: `#101722` at 90 % -> `#121A26` at 60 % -> transparent;
   - wave 1: cubic from (0.16 w, 0.78 h) with controls (0.34 w, 0.18 h) and (0.68 w, 0.86 h) to (1.02 w, 0.22 h); round-cap
     stroke of width 0.025 x min(w, h); brush from the accent at 48 % to transparent;
   - wave 2: cubic from (0.04 w, 0.62 h) with controls (0.30 w, 0.06 h) and (0.62 w, 0.72 h) to (0.92 w, 0.12 h); round-cap
     stroke of width 0.007 x min(w, h); brush from white at 20 % to transparent.
   - accent by `tipoFondo`: `A` = `#89D5FF` (Cielo), `B` = `#6EF3D5` (Menta), `C` = `#E9C985` (Oro).

These colours are document content. They do not follow the admin panel theme or the app theme.

## 5. Layers

`capas` is an array of objects. Paint them in ascending `orden` (first = back). Skip a layer with `visible = false`.

| Key | Meaning |
|---|---|
| `id` | Stable identifier of the layer (UUID). |
| `nombre` | Editor label. Not painted. |
| `tipo` | `titulo`, `texto` or `imagen`. |
| `contenido` | The text, or for `imagen` the public URL of the image. |
| `x`, `y`, `width`, `height` | Frame in canvas px. Always inside the canvas; minimum side 24. |
| `opacity` | 0..100, applied to the whole layer. |
| `rotationDegrees` | 0..359, clockwise, around the centre of the frame. |
| `fontFamily` | `sans` = Inter, the app typeface (default; also any unknown value). `serif`, `mono`, `cursive` = the generic family of each platform. |
| `fontSizeSp` | Font size in canvas px. `0` = derived from the type: `titulo` 72 (18 dp on a phone), others 48 (12 dp). |
| `fontWeight` | `normal`, `medium`, `semibold`, `bold`. Empty = derived: `titulo` bold, others normal. With Inter, `bold` resolves to SemiBold (see Fonts). |
| `textColor` | `#RRGGBB`. Empty = white. |
| `locked` | Editor only. Not painted. |
| `orden` | Paint order, 0 = back. |

Text layers: text starts at the top-left of the frame with an inset of 24 px horizontally and 16 px vertically, line
height 1.2 x the font size, wraps inside the frame, and whatever does not fit is clipped (the editor flags a clipped
layer). Image layers: the image fills the frame with centre crop.

A layer with empty `contenido` paints nothing, whatever its type. The editor draws the layer name and an outline as a
guide while editing, but never stores filler text: a new news item starts with three empty layers ("Título",
"Subtítulo", "Texto"). The title layer follows `titulo` only while both say the same; once it is rewritten in the
design tab it is independent, so the app must paint the layer, not `titulo`.

The editor writes every key of every layer (no omitted defaults) and renumbers `orden` from 0 on each save.

## 6. Known gaps

- **Consumer**: D4-18 / DEC-030. Until the feed and the banner exist, a published news item is not shown anywhere.
- **Scheduling**: no automatic activation (above).
- **Banner action**: no link model.
- **Mobile admin editor** (`MobileLayeredNewsEditor`, compact layout): paints titles at 26 and other text at 16 canvas
  units and ignores `fontSizeSp` and `fontWeight`, so its preview is not faithful to this contract (DEBT-090). The desktop
  editor is.
- **Fonts**: `sans` is painted with Inter in the editor, from the same three files the app bundles (Regular 400,
  Medium 500, SemiBold 600; `core/ui/.../composeResources/font/inter_*.ttf`, SIL OFL 1.1). Neither side has Inter Bold,
  so `bold` resolves to SemiBold on both and nothing synthesises a fake bold. If the app ever adds a Bold file, the admin
  must add the same one. `serif`, `mono` and `cursive` are whatever each platform provides: they are not identical
  between Android, iOS and the admin desktop, and the editor says so next to the selector.
