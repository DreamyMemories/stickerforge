<div align="center">

# StickerForge

**A personal WhatsApp sticker maker for Android.**
Search Giphy and Tenor without the usual nannying, cut out exactly what you want,
and turn it into a real sticker pack WhatsApp can use.

</div>

---

## What it does

- **Search Giphy and KLIPY** from inside the app. Giphy requests `rating=r`,
  KLIPY requests its default `contentfilter=off`, and KLIPY's **Stickers only**
  mode returns transparent stickers straight out of the box. Keys are yours,
  stored on device only.
  (Tenor used to be here — Google shut the Tenor API down on 30 June 2026,
  which is why KLIPY, the drop-in successor, took its place.)
- **Pick exactly what to remove.** Three tools on one canvas:
  - **Eraser brush** with adjustable size and a soft edge
  - **Restore brush** to paint transparency back
  - **Magic wand** that flood-fills a colour region (tolerance slider)
  - plus **AI cutout**: one tap, on-device subject segmentation via ML Kit
- **Animated or still.** Animated GIFs from Giphy/Tenor keep moving in the
  editor (frame scrubber + playback). Your cutout applies to every frame, and
  export can be an animated WebP sticker or a single still frame.
- **Real WhatsApp packs.** Packs live in the app, are served to WhatsApp through
  the official sticker `ContentProvider` contract, and can be added with the
  normal "Add to WhatsApp" flow. Also exports/imports `.wastickers` files.
- **Anything is a source**: Giphy, Tenor, your gallery, or share an image/video
  straight into StickerForge.

Pack rules are enforced before you add a pack to WhatsApp: 3–30 stickers,
512×512 WebP, ≤100 KB static / ≤500 KB animated, 1–3 emoji tags each, tray icon
96×96.

## Screens

- **Search** (Giphy/KLIPY tabs, still previews, animated + still import)
- **Editor** (checkerboard canvas, brush erase/restore, wand, AI cutout,
  undo/redo, frame scrubber, emoji tags)

## Get it running

Requirements: Android Studio (Ladybug or newer) or just a JDK 17+ and the
Android SDK (platform 35, build-tools 35.0.0), plus an Android 9+ device.

```bash
# debug build
./gradlew :app:assembleDebug
# install on a connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the project in Android Studio and hit Run, or use the commands above.

### API keys

The app has no keys baked in. Open **Settings** in the app and paste:

1. **Giphy** – free key from [developers.giphy.com](https://developers.giphy.com/dashboard/?create=true)
2. **KLIPY** – free key from the [KLIPY partner panel](https://partner.klipy.com/api-keys)

Notes:

- Giphy is asked for `rating=r`; KLIPY is asked for `contentfilter=off`, which
  is its documented default.
- KLIPY's **Stickers only** toggle adds `searchfilter=sticker`, returning
  transparent stickers instead of GIFs.
- Personal builds can pre-seed both keys instead of typing them: put
  `GIPHY_API_KEY=...` and `KLIPY_API_KEY=...` in `local.properties` (gitignored)
  and they are injected as `BuildConfig` fields. Settings still overrides them.

## Making a pack

1. **Packs → +** to create a pack (3–30 stickers, all static or all animated).
2. **Add from Giphy/Tenor**, **Gallery**, or share media into the app.
3. Edit: brush out the background, magic-wand a flat colour, or hit **AI cutout**.
4. Pick a frame (animated sources), choose **Animated sticker** or **Still frame
   only**, tag 1–3 emojis, **Save to pack**.
5. In the pack: **Add to WhatsApp** and confirm in the WhatsApp dialog.

`Export .wastickers` writes a portable pack file for other sticker importers;
the download icon in the Packs toolbar imports one back.

## Project layout

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). In short:

```
core/         EditMask, MagicWand, WebpMuxer, StickerExporter, Frames, AiCutout
data/         Giphy/Tenor clients, API keys, pack repository, downloads
whatsapp/     ContentProvider, validator, whitelist check, .wastickers
ui/           Compose screens: search, editor, packs, settings
```

Interesting bits:

- **Animated WebP on Android.** Android can only *encode still* WebP frames, so
  `core/webp/WebpMuxer.kt` muxes frames into the animated container itself
  (VP8X/ANIM/ANMF), and `WebpInfo` parses it back for validation.
- **Mask-first editing.** The cutout is a single `ByteArray` alpha mask applied
  to every frame, which is what makes animated cutouts affordable.
- **Size budgets.** Export quality-searches until the sticker fits WhatsApp's
  100 KB / 500 KB limits instead of failing afterwards.

## Roadmap

- Pinch/pan crop and a proper transform tool
- Per-frame masks (when a GIF's background moves)
- Text/stroke overlays before export
- Watermark-free previews in the grid (already uses provider stills)

## Attribution & license

MIT. Portions of the WhatsApp integration (`whatsapp/`) are adapted from the
[official WhatsApp sticker sample](https://github.com/WhatsApp/stickers),
BSD licensed, © Meta Platforms, Inc. See [`NOTICE.md`](NOTICE.md).

Sticker search uses the official Giphy and KLIPY APIs with keys supplied at
runtime. Stickers you make with them are subject to those providers' terms and
to WhatsApp's acceptable use policy — this is a personal tool, be decent with it.
