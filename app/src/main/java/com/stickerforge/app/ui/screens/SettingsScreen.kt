package com.stickerforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stickerforge.app.ui.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(main: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = main.graph.apiKeys

    var giphyKey by remember { mutableStateOf("") }
    var klipyKey by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            giphyKey = keys.giphyKeyOnce()
            klipyKey = keys.klipyKeyOnce()
            publisher = keys.publisherOnce().ifBlank { "StickerForge" }
            loaded = true
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("API keys", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keys are stored on this device only. Search is unfiltered where the provider allows it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Giphy", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = giphyKey,
                            onValueChange = { giphyKey = it },
                            label = { Text("Giphy API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    keys.setGiphyKey(giphyKey)
                                    savedNotice = "Giphy key saved"
                                }
                            }) { Text("Save") }
                            OutlinedButton(onClick = {
                                openUrl(context, "https://developers.giphy.com/dashboard/?create=true")
                            }) { Text("Get a key") }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("KLIPY", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = klipyKey,
                            onValueChange = { klipyKey = it },
                            label = { Text("KLIPY API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "KLIPY is the drop-in Tenor replacement (Tenor's API shut down on 30 June 2026) and requests are unfiltered by default. Grab a key from the KLIPY partner panel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    keys.setKlipyKey(klipyKey)
                                    savedNotice = "KLIPY key saved"
                                }
                            }) { Text("Save") }
                            OutlinedButton(onClick = {
                                openUrl(context, "https://partner.klipy.com/api-keys")
                            }) { Text("Get a key") }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Packs", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = publisher,
                            onValueChange = { publisher = it },
                            label = { Text("Publisher name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            scope.launch {
                                keys.setPublisher(publisher)
                                savedNotice = "Publisher saved"
                            }
                        }) { Text("Save") }
                    }
                }
            }

            item {
                savedNotice?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }

            item {
                Divider()
                Spacer8()
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "StickerForge makes WhatsApp sticker packs from Giphy/Tenor GIFs, your camera roll, or anything you share into it. Search results respect the rating you ask the provider for; content ultimately comes from Giphy and Tenor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Spacer8() {
    Column(Modifier.padding(top = 8.dp)) {}
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    } catch (_: Exception) {
        // no browser installed, ignore
    }
}
