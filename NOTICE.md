# Third-party notices

## WhatsApp / stickers

Files under `app/src/main/java/com/stickerforge/app/whatsapp/` are derived from
the official WhatsApp sticker sample app:

    https://github.com/WhatsApp/stickers

    Copyright (c) Meta Platforms, Inc. and affiliates.
    All rights reserved.

    This source code is licensed under the BSD-style license found in the
    LICENSE file in the root directory of that source tree.

They were adapted to serve dynamically created sticker packs from app storage
instead of bundled assets. The adapted files keep the original BSD header.

## Google ML Kit

Subject segmentation is provided by ML Kit
(`com.google.android.gms:play-services-mlkit-subject-segmentation`), used under
the Google APIs terms of service.

## Giphy / KLIPY

Sticker search uses the official Giphy and KLIPY HTTP APIs with API keys that
the user supplies at runtime. Keys are never bundled with the app.

Tenor was replaced by KLIPY after Google discontinued the Tenor API on
30 June 2026.
