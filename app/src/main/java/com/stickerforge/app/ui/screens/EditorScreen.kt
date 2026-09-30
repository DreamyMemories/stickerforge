package com.stickerforge.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModel
import com.stickerforge.app.StickerForgeApp
import com.stickerforge.app.core.AiCutout
import com.stickerforge.app.core.EditMask
import com.stickerforge.app.core.ImageSource
import com.stickerforge.app.core.StickerExporter
import com.stickerforge.app.model.StickerPack
import com.stickerforge.app.ui.EMOJI_PRESETS
import com.stickerforge.app.ui.MainViewModel
import kotlin.math.hypot
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTool { ERASE, RESTORE, WAND }

class EditorViewModel(
    application: Application,
    private val source: ImageSource,
    initialAnimatedExport: Boolean,
) : ViewModel() {

    private val graph = (application as StickerForgeApp).graph
    private val repository = graph.packRepository

    val mask = EditMask(source.width, source.height)
    val maskBitmap: Bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    private val framePixels = IntArray(source.width * source.height)

    data class State(
        val frameIndex: Int = 0,
        val playing: Boolean = false,
        val tool: EditorTool = EditorTool.ERASE,
        val brushFraction: Float = 0.09f,
        val tolerance: Int = 40,
        val exportAnimated: Boolean = true,
        val busy: Boolean = false,
        val saving: Boolean = false,
        val finished: Boolean = false,
        val maskVersion: Int = 0,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State(exportAnimated = initialAnimatedExport))
    val state = _state.asStateFlow()

    private var lastPoint: Offset? = null
    private var dirty: IntArray? = null

    init {
        refreshMask()
        refreshFramePixels()
    }

    val animatedSource: Boolean get() = source.animated && source.frames.size > 1
    val frameCount: Int get() = source.frames.size

    fun setFrame(index: Int) {
        val clamped = index.coerceIn(0, source.frames.lastIndex)
        _state.update { it.copy(frameIndex = clamped) }
        refreshFramePixels()
    }

    fun togglePlay() = _state.update { it.copy(playing = !it.playing) }

    fun setTool(tool: EditorTool) = _state.update { it.copy(tool = tool) }

    fun setBrushFraction(fraction: Float) = _state.update { it.copy(brushFraction = fraction) }

    fun setTolerance(tolerance: Int) = _state.update { it.copy(tolerance = tolerance) }

    fun setExportAnimated(animated: Boolean) = _state.update { it.copy(exportAnimated = animated) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------------ brush

    fun brushStart(x: Float, y: Float) {
        mask.checkpoint()
        lastPoint = Offset(x, y)
        stamp(x, y)
        flush()
        syncHistory()
    }

    fun brushMove(x: Float, y: Float) {
        val start = lastPoint ?: run { lastPoint = Offset(x, y); Offset(x, y) }
        val radius = brushRadius()
        val dx = x - start.x
        val dy = y - start.y
        val distance = hypot(dx, dy)
        val increments = (distance / (radius / 3f).coerceAtLeast(2f)).toInt().coerceIn(1, 64)
        for (step in 1..increments) {
            stamp(start.x + dx * step / increments, start.y + dy * step / increments)
        }
        lastPoint = Offset(x, y)
        flush()
    }

    fun brushEnd() {
        lastPoint = null
        flush()
    }

    fun tap(x: Float, y: Float) {
        if (_state.value.tool != EditorTool.WAND) {
            brushStart(x, y)
            brushEnd()
            return
        }
        mask.checkpoint()
        mask.magicWand(
            pixels = framePixels,
            seedX = x.toInt().coerceIn(0, mask.width - 1),
            seedY = y.toInt().coerceIn(0, mask.height - 1),
            tolerance = _state.value.tolerance,
            contiguous = true,
            erase = true,
        )
        refreshMask()
        syncHistory()
    }

    private fun brushRadius(): Float = (mask.width * _state.value.brushFraction).coerceAtLeast(3f)

    private fun stamp(x: Float, y: Float) {
        when (_state.value.tool) {
            EditorTool.RESTORE -> mask.restoreCircle(x, y, brushRadius(), HARDNESS)
            else -> mask.eraseCircle(x, y, brushRadius(), HARDNESS)
        }
        markDirty(x, y)
    }

    private fun markDirty(x: Float, y: Float) {
        val radius = brushRadius().toInt() + 2
        val left = (x.toInt() - radius).coerceAtLeast(0)
        val top = (y.toInt() - radius).coerceAtLeast(0)
        val right = (x.toInt() + radius).coerceAtMost(mask.width)
        val bottom = (y.toInt() + radius).coerceAtMost(mask.height)
        val current = dirty
        dirty = if (current == null) {
            intArrayOf(left, top, right, bottom)
        } else {
            intArrayOf(
                minOf(current[0], left),
                minOf(current[1], top),
                maxOf(current[2], right),
                maxOf(current[3], bottom),
            )
        }
    }

    private fun flush() {
        val rect = dirty ?: return
        dirty = null
        if (rect[2] > rect[0] && rect[3] > rect[1]) {
            refreshMask(rect[0], rect[1], rect[2], rect[3])
        }
    }

    // ------------------------------------------------------------- operations

    fun undo() {
        mask.undo()
        refreshMask()
        syncHistory()
    }

    fun redo() {
        mask.redo()
        refreshMask()
        syncHistory()
    }

    fun resetMask() {
        mask.checkpoint()
        mask.reset()
        refreshMask()
        syncHistory()
    }

    fun runAiCutout() {
        val frame = source.frames[_state.value.frameIndex]
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val result = graph.aiCutout.segment(frame)) {
                is AiCutout.Result.Ok -> {
                    mask.checkpoint()
                    mask.applyConfidenceMask(result.confidence, 0.35f, 0.65f)
                    refreshMask()
                    syncHistory()
                    _state.update { it.copy(busy = false, message = "Background removed. Clean up the edges with the brush.") }
                }
                is AiCutout.Result.Failed -> _state.update {
                    it.copy(busy = false, message = result.message)
                }
            }
        }
    }

    // ----------------------------------------------------------------- export

    fun save(packId: String, emojis: List<String>) {
        if (_state.value.saving) return
        val animated = animatedSource && _state.value.exportAnimated
        // Check compatibility before spending seconds encoding frames.
        val pack = repository.getPack(packId)
        if (pack == null) {
            _state.update { it.copy(message = "That pack no longer exists.") }
            return
        }
        if (!repository.canAdd(packId, animated)) {
            _state.update {
                it.copy(
                    message = "A pack cannot mix static and animated stickers. " +
                        "This pack already holds ${if (pack.animated) "animated" else "static"} stickers - " +
                        "pick another pack or create a new one.",
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, message = null) }
            try {
                val bytes = withContext(Dispatchers.Default) {
                    if (animated) {
                        StickerExporter.exportAnimated(source.frames, source.durationsMs, mask)
                    } else {
                        StickerExporter.exportStatic(source.frames[_state.value.frameIndex], mask)
                    }
                }
                val budget = if (animated) StickerExporter.ANIMATED_BUDGET_BYTES else StickerExporter.STATIC_BUDGET_BYTES
                withContext(Dispatchers.IO) { repository.addSticker(packId, bytes, emojis, animated) }
                val warning = if (bytes.size > budget) {
                    "Saved, but ${bytes.size / 1024} KB is over the WhatsApp ${budget / 1024} KB limit."
                } else {
                    null
                }
                _state.update { it.copy(saving = false, finished = true, message = warning) }
            } catch (error: Exception) {
                _state.update { it.copy(saving = false, message = error.message ?: "Could not save the sticker") }
            }
        }
    }

    // ---------------------------------------------------------------- backing

    private fun refreshFramePixels() {
        val frame = source.frames[_state.value.frameIndex]
        frame.getPixels(framePixels, 0, source.width, 0, 0, source.width, source.height)
    }

    private fun syncHistory() = _state.update {
        it.copy(canUndo = mask.canUndo, canRedo = mask.canRedo, maskVersion = it.maskVersion + 1)
    }

    private fun refreshMask(
        left: Int = 0,
        top: Int = 0,
        right: Int = mask.width,
        bottom: Int = mask.height,
    ) {
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        if (width == 0 || height == 0) return
        val buffer = IntArray(width * height)
        for (y in 0 until height) {
            val sourceRow = (top + y) * mask.width + left
            val targetRow = y * width
            for (x in 0 until width) {
                val alpha = mask.alpha[sourceRow + x].toInt() and 0xFF
                buffer[targetRow + x] = (alpha shl 24) or 0x00FFFFFF
            }
        }
        maskBitmap.setPixels(buffer, 0, width, left, top, width, height)
        _state.update { it.copy(maskVersion = it.maskVersion + 1) }
    }

    override fun onCleared() {
        super.onCleared()
        maskBitmap.recycle()
    }

    private companion object {
        const val HARDNESS = 0.75f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(main: MainViewModel, onDone: () -> Unit) {
    val source = main.pendingSource
    if (source == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val viewModel: EditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                EditorViewModel(main.getApplication(), source, main.preferAnimated)
            }
        },
    )
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showSaveDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(state.finished) {
        if (state.finished) {
            main.clearPending()
            onDone()
        }
    }

    // playback
    LaunchedEffect(state.playing, state.frameIndex, viewModel.frameCount) {
        if (!state.playing || viewModel.frameCount <= 1) return@LaunchedEffect
        delay(source.durationsMs.getOrElse(state.frameIndex) { 100 }.toLong())
        viewModel.setFrame((state.frameIndex + 1) % viewModel.frameCount)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Make a sticker") },
                navigationIcon = {
                    IconButton(onClick = {
                        main.clearPending()
                        onDone()
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = viewModel::resetMask) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "Reset")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val frameImage = remember(state.frameIndex) { source.frames[state.frameIndex].asImageBitmap() }
                val maskImage = remember(state.maskVersion) { viewModel.maskBitmap.asImageBitmap() }
                Checkerboard(Modifier.matchParentSize())
                Canvas(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(state.tool, state.brushFraction, state.tolerance, canvasSize) {
                            if (canvasSize.width == 0) return@pointerInput
                            val scale = viewModel.mask.width.toFloat() / canvasSize.width
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val start = down.position
                                var dragged = false
                                if (state.tool != EditorTool.WAND) {
                                    viewModel.brushStart(start.x * scale, start.y * scale)
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (!dragged &&
                                        (change.position - start).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        dragged = true
                                    }
                                    if (dragged && state.tool != EditorTool.WAND) {
                                        viewModel.brushMove(change.position.x * scale, change.position.y * scale)
                                        change.consume()
                                    }
                                }
                                if (state.tool == EditorTool.WAND && !dragged) {
                                    viewModel.tap(start.x * scale, start.y * scale)
                                } else if (state.tool != EditorTool.WAND) {
                                    viewModel.brushEnd()
                                }
                            }
                        },
                    onDraw = {
                        val factor = size.width / frameImage.width
                        scale(factor, factor, pivot = Offset.Zero) {
                            drawImage(frameImage)
                            drawImage(maskImage, blendMode = BlendMode.DstIn)
                        }
                    },
                )
                if (state.busy) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }

            if (viewModel.animatedSource) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::togglePlay) {
                        Icon(
                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.playing) "Pause" else "Play",
                        )
                    }
                    Slider(
                        value = state.frameIndex.toFloat(),
                        onValueChange = { viewModel.setFrame(it.toInt()) },
                        valueRange = 0f..(viewModel.frameCount - 1).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.weight(1f),
                    )
                    Text("${state.frameIndex + 1}/${viewModel.frameCount}")
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.tool == EditorTool.ERASE,
                        onClick = { viewModel.setTool(EditorTool.ERASE) },
                        label = { Text("Erase") },
                    )
                    FilterChip(
                        selected = state.tool == EditorTool.RESTORE,
                        onClick = { viewModel.setTool(EditorTool.RESTORE) },
                        label = { Text("Restore") },
                    )
                    FilterChip(
                        selected = state.tool == EditorTool.WAND,
                        onClick = { viewModel.setTool(EditorTool.WAND) },
                        label = { Text("Magic wand") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = viewModel::runAiCutout,
                        enabled = !state.busy,
                        label = { Text("AI cutout") },
                        leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                    )
                }

                when (state.tool) {
                    EditorTool.WAND -> {
                        Text("Tolerance: ${state.tolerance}")
                        Slider(
                            value = state.tolerance.toFloat(),
                            onValueChange = { viewModel.setTolerance(it.toInt()) },
                            valueRange = 0f..120f,
                        )
                    }
                    else -> {
                        Text("Brush size")
                        Slider(
                            value = state.brushFraction,
                            onValueChange = viewModel::setBrushFraction,
                            valueRange = 0.02f..0.35f,
                        )
                    }
                }

                if (viewModel.animatedSource) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.exportAnimated,
                            onClick = { viewModel.setExportAnimated(true) },
                            label = { Text("Animated sticker") },
                        )
                        FilterChip(
                            selected = !state.exportAnimated,
                            onClick = { viewModel.setExportAnimated(false) },
                            label = { Text("Still frame only") },
                        )
                    }
                }

                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }

                Button(
                    onClick = { showSaveDialog = true },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.saving) "Saving…" else "Save to pack")
                }
                Text(
                    "Eraser and wand work on every frame of an animated sticker at once, so the same cutout is applied throughout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showSaveDialog) {
        SaveStickerDialog(
            main = main,
            emojis = emptyList(),
            animatedExport = viewModel.animatedSource && state.exportAnimated,
            onDismiss = { showSaveDialog = false },
            onSave = { packId, emojis ->
                showSaveDialog = false
                viewModel.save(packId, emojis)
            },
        )
    }
}

@Composable
private fun Checkerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cells = 16
        val cell = size.width / cells
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val light = (row + column) % 2 == 0
                drawRect(
                    color = if (light) Color(0xFF2B2B33) else Color(0xFF1F1F26),
                    topLeft = Offset(column * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

@Composable
fun SaveStickerDialog(
    main: MainViewModel,
    emojis: List<String>,
    animatedExport: Boolean,
    onDismiss: () -> Unit,
    onSave: (packId: String, emojis: List<String>) -> Unit,
) {
    val repository = main.graph.packRepository
    var packs by remember { mutableStateOf(repository.listPacks()) }
    // A pack is all-static or all-animated, so only compatible packs are offered.
    fun accepts(pack: StickerPack): Boolean = pack.stickers.isEmpty() || pack.animated == animatedExport
    var selectedPack by remember {
        mutableStateOf(
            main.targetPackId?.takeIf { id -> packs.any { it.identifier == id && accepts(it) } }
                ?: packs.firstOrNull { accepts(it) }?.identifier,
        )
    }
    var selectedEmojis by remember { mutableStateOf(emojis.ifEmpty { listOf("\uD83D\uDE00") }) }
    var newPackName by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("StickerForge") }

    LaunchedEffect(Unit) {
        publisher = main.graph.apiKeys.publisherOnce().ifBlank { "StickerForge" }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save sticker") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (packs.isEmpty()) {
                    Text("No packs yet — create one below.")
                }
                packs.forEach { pack: StickerPack ->
                    val compatible = accepts(pack)
                    FilterChip(
                        selected = selectedPack == pack.identifier,
                        onClick = { if (compatible) selectedPack = pack.identifier },
                        enabled = compatible,
                        label = {
                            Text(
                                "${pack.name} (${pack.stickerCount})" + when {
                                    compatible -> ""
                                    pack.animated -> " · animated only"
                                    else -> " · static only"
                                },
                            )
                        },
                    )
                }
                if (packs.isNotEmpty() && selectedPack == null) {
                    Text(
                        "None of these packs can take a ${if (animatedExport) "animated" else "static"} " +
                            "sticker — create a new pack below.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPackName,
                        onValueChange = { newPackName = it },
                        label = { Text(if (animatedExport) "New animated pack" else "New pack name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val created = repository.createPack(newPackName, publisher)
                            packs = repository.listPacks()
                            selectedPack = created.identifier
                            newPackName = ""
                        },
                        enabled = newPackName.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Create") }
                }
                Text("Emoji tags (up to 3)")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(EMOJI_PRESETS) { emoji ->
                        FilterChip(
                            selected = emoji in selectedEmojis,
                            onClick = {
                                selectedEmojis = when {
                                    emoji in selectedEmojis -> selectedEmojis - emoji
                                    selectedEmojis.size < 3 -> selectedEmojis + emoji
                                    else -> selectedEmojis
                                }
                            },
                            label = { Text(emoji) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedPack?.let { onSave(it, selectedEmojis) } },
                enabled = selectedPack != null,
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
