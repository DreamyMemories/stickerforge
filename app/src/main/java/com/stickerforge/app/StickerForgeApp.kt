package com.stickerforge.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

/**
 * Application shell. Dependencies are wired lazily so the app starts fast and
 * screens pull what they need from [AppGraph].
 */
class StickerForgeApp : Application(), ImageLoaderFactory {

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Coil's default singleton loader shows only the first frame of animated
     * GIF/WebP, which would make the search grid look static. Adding the
     * animated decoders (coil-gif) makes the previews play.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(GifDecoder.Factory())
            add(ImageDecoderDecoder.Factory())
        }
        .build()

    companion object {
        lateinit var instance: StickerForgeApp
            private set
    }
}
