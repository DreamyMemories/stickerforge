package com.stickerforge.app

import android.app.Application

/**
 * Application shell. Dependencies are wired lazily so the app starts fast and
 * screens pull what they need from [AppGraph].
 */
class StickerForgeApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: StickerForgeApp
            private set
    }
}
