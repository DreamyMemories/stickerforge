package com.stickerforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.stickerforge.app.core.Frames
import com.stickerforge.app.data.GifResult
import com.stickerforge.app.data.GifSource
import com.stickerforge.app.data.ApiResult
import com.stickerforge.app.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.withContext

class SearchViewModel(private val main: MainViewModel) : ViewModel() {

    data class State(
        val source: GifSource = GifSource.GIPHY,
        val query: String = "",
        val results: List<GifResult> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val missingKey: Boolean = false,
        val next: String? = null,
        val opened: GifResult? = null,
        val busyId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun setSource(source: GifSource) {
        _state.update { it.copy(source = source, results = emptyList(), next = null, error = null, missingKey = false) }
        load(reset = true)
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun submit() = load(reset = true)

    fun loadMore() = load(reset = false)

    fun open(result: GifResult) = _state.update { it.copy(opened = result) }

    fun dismiss() = _state.update { it.copy(opened = null) }

    private fun load(reset: Boolean) {
        val current = _state.value
        if (current.loading) return
        if (!reset && current.next == null) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, missingKey = false) }
            val cursor = if (reset) null else current.next
            val result = if (current.query.isBlank()) {
                main.graph.gifSearch.trending(current.source, cursor)
            } else {
                main.graph.gifSearch.search(current.source, current.query, cursor)
            }
            when (result) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        results = if (reset) result.value.items else it.results + result.value.items,
                        next = result.value.next,
                    )
                }
                is ApiResult.MissingKey -> _state.update {
                    it.copy(loading = false, missingKey = true)
                }
                is ApiResult.HttpError -> _state.update {
                    it.copy(loading = false, error = "Provider said HTTP ${result.code}")
                }
                is ApiResult.NetworkError -> _state.update {
                    it.copy(loading = false, error = "Network problem: ${result.message}")
                }
                is ApiResult.ParseError -> _state.update {
                    it.copy(loading = false, error = "Could not read the response")
                }
            }
        }
    }

    /** Downloads the chosen result and hands it to the editor. */
    fun use(result: GifResult, animated: Boolean, onReady: () -> Unit) {
        _state.update { it.copy(busyId = result.id, error = null) }
        viewModelScope.launch {
            try {
                val url = when {
                    result.mp4Url != null -> result.mp4Url
                    else -> result.gifUrl
                }
                val bytes = withContext(Dispatchers.IO) { main.graph.downloader.download(url) }
                val source = withContext(Dispatchers.IO) {
                    Frames.fromBytes(
                        context = main.getApplication(),
                        bytes = bytes,
                        mimeHint = if (url.contains(".mp4")) "video/mp4" else null,
                    )
                }
                main.pendingSource = source
                main.preferAnimated = animated && source.animated
                main.targetPackId = main.targetPackId
                _state.update { it.copy(busyId = null, opened = null) }
                onReady()
            } catch (error: Exception) {
                _state.update { it.copy(busyId = null, error = error.message ?: "Download failed") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    main: MainViewModel,
    onOpenSettings: () -> Unit,
    onEdit: () -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory { initializer { SearchViewModel(main) } },
    )
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state.results.isEmpty() && !state.loading) viewModel.setSource(state.source)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find a GIF") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.source == GifSource.GIPHY,
                    onClick = { viewModel.setSource(GifSource.GIPHY) },
                    label = { Text("Giphy") },
                )
                FilterChip(
                    selected = state.source == GifSource.TENOR,
                    onClick = { viewModel.setSource(GifSource.TENOR) },
                    label = { Text("Tenor") },
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search ${if (state.source == GifSource.GIPHY) "Giphy" else "Tenor"}") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { viewModel.submit() }),
            )

            when {
                state.missingKey -> MissingKeyCard(
                    source = state.source,
                    onOpenSettings = onOpenSettings,
                )
                state.error != null -> Text(
                    text = state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.id + it.source.name }) { result ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { viewModel.open(result) },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(110.dp),
                            ) {
                                AsyncImage(
                                    model = result.previewUrl,
                                    contentDescription = result.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                )
                                if (state.busyId == result.id) {
                                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                    if (state.next != null) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                            OutlinedButton(
                                onClick = { viewModel.loadMore() },
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                            ) { Text(if (state.loading) "Loading…" else "Load more") }
                        }
                    }
                }
                if (state.loading && state.results.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
    }

    state.opened?.let { result ->
        ModalBottomSheet(onDismissRequest = { viewModel.dismiss() }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    model = result.previewUrl,
                    contentDescription = result.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
                Text(
                    result.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Button(
                    onClick = { viewModel.use(result, animated = true, onReady = onEdit) },
                    enabled = state.busyId == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Edit as animated sticker") }
                OutlinedButton(
                    onClick = { viewModel.use(result, animated = false, onReady = onEdit) },
                    enabled = state.busyId == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use one still frame") }
                Text(
                    "Animated stickers keep playing on WhatsApp; still frames are simpler and smaller.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MissingKeyCard(source: GifSource, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "No ${if (source == GifSource.GIPHY) "Giphy" else "Tenor"} API key yet",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Add your free key in Settings and searching works immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSettings) { Text("Open Settings") }
        }
    }
}
