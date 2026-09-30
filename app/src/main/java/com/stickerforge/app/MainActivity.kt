package com.stickerforge.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stickerforge.app.ui.AppNav
import com.stickerforge.app.ui.MainViewModel
import com.stickerforge.app.ui.theme.StickerForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val incoming = extractSharedUri(intent)

        setContent {
            StickerForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val main: MainViewModel = viewModel()
                    var sharedUri by remember { mutableStateOf(incoming) }
                    AppNav(main = main, sharedUri = sharedUri, onSharedConsumed = { sharedUri = null })
                }
            }
        }
    }

    private fun extractSharedUri(intent: Intent?): android.net.Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
    }
}
