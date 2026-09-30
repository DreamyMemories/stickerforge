package com.stickerforge.app.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stickerforge.app.core.Frames
import com.stickerforge.app.model.Sticker
import com.stickerforge.app.model.StickerPack
import com.stickerforge.app.ui.EMOJI_PRESETS
import com.stickerforge.app.ui.MainViewModel
import com.stickerforge.app.whatsapp.StickerPackValidator
import com.stickerforge.app.whatsapp.WastickersFile
import com.stickerforge.app.whatsapp.WhitelistCheck
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(main: MainViewModel, onOpenEditor: () -> Unit, onGoSearch: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = main.graph.packRepository

    var selectedPackId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showNewPackDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var publisher by remember { mutableStateOf("StickerForge") }
    val packs = remember(refresh) { repository.listPacks() }

    LaunchedEffect(Unit) {
        publisher = main.graph.apiKeys.publisherOnce().ifBlank { "StickerForge" }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not read the file")
                    }
                    val imported = withContext(Dispatchers.IO) { WastickersFile.read(bytes) }
                    val pack = withContext(Dispatchers.IO) { repository.importPack(imported) }
                    refresh++
                    "Imported \"${pack.name}\""
                } catch (error: Exception) {
                    error.message ?: "Import failed"
                }
            }
        }
    }

    if (selectedPackId != null) {
        val pack = packs.firstOrNull { it.identifier == selectedPackId }
        if (pack == null) {
            selectedPackId = null
        } else {
            PackDetail(
                main = main,
                pack = pack,
                onBack = { selectedPackId = null },
                onChanged = { refresh++ },
                onOpenEditor = onOpenEditor,
                onGoSearch = onGoSearch,
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My packs") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Import .wastickers")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewPackDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New pack")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            if (packs.isEmpty()) {
                Text(
                    "No packs yet. Create one, then fill it with stickers from Giphy, Tenor, your gallery or anything you share into StickerForge.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            packs.forEach { pack ->
                PackRow(
                    pack = pack,
                    trayFile = repository.trayFile(pack.identifier),
                    onClick = { selectedPackId = pack.identifier },
                )
            }
        }
    }

    if (showNewPackDialog) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNewPackDialog = false },
            title = { Text("New pack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Pack name") },
                        singleLine = true,
                    )
                    Text(
                        "A pack needs 3 to 30 stickers, all static or all animated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pack = repository.createPack(name, publisher)
                        refresh++
                        selectedPackId = pack.identifier
                        showNewPackDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { OutlinedButton(onClick = { showNewPackDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackRow(pack: StickerPack, trayFile: File?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (trayFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(trayFile)
                        .memoryCacheKey("tray-${pack.identifier}-${trayFile.lastModified()}")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .height(56.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pack.stickerCount} sticker${if (pack.stickerCount == 1) "" else "s"}" +
                        if (pack.animated) " · animated" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackDetail(
    main: MainViewModel,
    pack: StickerPack,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenEditor: () -> Unit,
    onGoSearch: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = main.graph.packRepository

    var current by remember(pack.identifier) { mutableStateOf(pack) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var inWhatsApp by remember { mutableStateOf(false) }
    var editingSticker by remember { mutableStateOf<Sticker?>(null) }

    LaunchedEffect(refresh, pack.identifier) {
        current = repository.getPack(pack.identifier) ?: pack
        validationMessage = withContext(Dispatchers.IO) {
            runCatching {
                StickerPackValidator.validate(
                    pack = current,
                    readSticker = { sticker -> repository.stickerBytes(current.identifier, sticker.fileName) ?: ByteArray(0) },
                    readTray = { repository.ensureTray(current.identifier) ?: ByteArray(0) },
                )
            }.exceptionOrNull()?.message
        }
        inWhatsApp = withContext(Dispatchers.IO) { WhitelistCheck.isWhitelisted(context, current.identifier) }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val source = withContext(Dispatchers.IO) { Frames.fromUri(context, uri) }
                    main.pendingSource = source
                    main.targetPackId = current.identifier
                    main.preferAnimated = source.animated
                    onOpenEditor()
                } catch (error: Exception) {
                    message = error.message ?: "Could not read that file"
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                message = try {
                    val bytes = withContext(Dispatchers.IO) { repository.writeWastickers(current.identifier) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("Could not open the destination")
                    }
                    "Exported ${bytes.size / 1024} KB"
                } catch (error: Exception) {
                    error.message ?: "Export failed"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        repository.deletePack(current.identifier)
                        onChanged()
                        onBack()
                    }) { Icon(Icons.Filled.Delete, contentDescription = "Delete pack") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    main.targetPackId = current.identifier
                    onGoSearch()
                }) { Text("Add from Giphy/Tenor") }
                OutlinedButton(onClick = { pickMedia.launch(arrayOf("image/*", "video/*")) }) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Text("Gallery")
                }
            }

            Text(
                if (inWhatsApp) "This pack is in WhatsApp." else "Not added to WhatsApp yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            validationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            val stickers = current.stickers
            if (stickers.isEmpty()) {
                Text("No stickers yet.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(((stickers.size + 2) / 3 * 120).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(stickers, key = { it.id }) { sticker ->
                        val file = repository.stickerFile(current.identifier, sticker.fileName)
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            if (file != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(file)
                                        .memoryCacheKey("${sticker.id}-${file.lastModified()}")
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            TextButton(onClick = { editingSticker = sticker }) {
                                Text(sticker.emojis.joinToString(" "), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(WhitelistCheck.buildAddIntent(current.identifier, current.name))
                        } catch (_: ActivityNotFoundException) {
                            message = "WhatsApp is not installed on this device"
                        }
                    },
                    enabled = validationMessage == null,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Text("Add to WhatsApp")
                }
                OutlinedButton(onClick = { exportLauncher.launch("${current.identifier}.wastickers") }) {
                    Text("Export .wastickers")
                }
            }
        }
    }

    editingSticker?.let { sticker ->
        StickerEditDialog(
            sticker = sticker,
            onDismiss = { editingSticker = null },
            onSaveEmojis = { emojis ->
                repository.setStickerEmojis(current.identifier, sticker.id, emojis)
                editingSticker = null
                refresh++
                onChanged()
            },
            onMove = { direction ->
                val index = current.stickers.indexOfFirst { it.id == sticker.id }
                val target = (index + direction).coerceIn(0, current.stickers.lastIndex)
                repository.moveSticker(current.identifier, index, target)
                editingSticker = null
                refresh++
                onChanged()
            },
            onDelete = {
                repository.removeSticker(current.identifier, sticker.id)
                editingSticker = null
                refresh++
                onChanged()
            },
        )
    }
}

@Composable
private fun StickerEditDialog(
    sticker: Sticker,
    onDismiss: () -> Unit,
    onSaveEmojis: (List<String>) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var emojis by remember { mutableStateOf(sticker.emojis) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sticker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emoji tags (up to 3)")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(EMOJI_PRESETS) { emoji ->
                        FilterChip(
                            selected = emoji in emojis,
                            onClick = {
                                emojis = when {
                                    emoji in emojis -> emojis - emoji
                                    emojis.size < 3 -> emojis + emoji
                                    else -> emojis
                                }
                            },
                            label = { Text(emoji) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMove(-1) }) { Text("Move left") }
                    OutlinedButton(onClick = { onMove(1) }) { Text("Move right") }
                }
            }
        },
        confirmButton = { Button(onClick = { onSaveEmojis(emojis) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
