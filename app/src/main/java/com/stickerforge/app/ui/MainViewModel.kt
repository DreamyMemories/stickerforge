package com.stickerforge.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stickerforge.app.AppGraph
import com.stickerforge.app.StickerForgeApp
import com.stickerforge.app.core.ImageSource

/**
 * Activity-scoped state that survives configuration changes and is shared
 * between screens: the source being edited, its target pack and the last
 * chooser result.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val graph: AppGraph = (application as StickerForgeApp).graph

    /** Loaded frames waiting for the editor. */
    var pendingSource: ImageSource? = null

    /** Pack the editor should save into, or null when the user has to choose. */
    var targetPackId: String? = null

    /** Pre-selects animated/still export in the editor. */
    var preferAnimated: Boolean = true

    /** Set when the user shares an image or video into the app. */
    var sharedUri: android.net.Uri? = null

    /** Drops the pending frames and frees their memory. */
    fun clearPending() {
        pendingSource?.frames?.forEach { frame -> if (!frame.isRecycled) frame.recycle() }
        pendingSource = null
    }
}
