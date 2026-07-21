package com.vmodal.sdk.examples.search

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: SearchViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("agroup") }
    var stream by rememberSaveable { mutableStateOf("astream") }
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        vm.search(apiKey, query, group, stream)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentUriUploadSource(context.applicationContext, uri) }
                .fold(
                    onSuccess = vm::selectVideo,
                    onFailure = { vm.selectionError(it.message ?: "Cannot read the selected video.") },
                )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("VModal upload and search") }) },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchForm(
                    state = state,
                    apiKey = apiKey,
                    query = query,
                    group = group,
                    stream = stream,
                    onApiKeyChange = { apiKey = it },
                    onQueryChange = { query = it },
                    onGroupChange = {
                        group = it
                        vm.coordinatesChanged()
                    },
                    onStreamChange = {
                        stream = it
                        vm.coordinatesChanged()
                    },
                    onPickVideo = { picker.launch(arrayOf("video/*")) },
                    onUpload = { vm.upload(apiKey, group, stream) },
                    onCreateIndex = { vm.createIndex(apiKey, group, stream) },
                    onSearch = submit,
                    onForgetKey = {
                        apiKey = ""
                        vm.clearCredentials()
                    },
                )
            }
            searchBody(state)
        }
    }
}

@Composable
private fun SearchForm(
    state: SearchUiState,
    apiKey: String,
    query: String,
    group: String,
    stream: String,
    onApiKeyChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onGroupChange: (String) -> Unit,
    onStreamChange: (String) -> Unit,
    onPickVideo: () -> Unit,
    onUpload: () -> Unit,
    onCreateIndex: () -> Unit,
    onSearch: () -> Unit,
    onForgetKey: () -> Unit,
) {
    val canSearch = state.action == null && (state.uploadedFile.isBlank() || state.indexReady)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Runtime API key") },
            enabled = state.action == null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe what you want to find") },
            placeholder = { Text("red car entering a parking lot") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { if (canSearch) onSearch() },
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = group,
                onValueChange = onGroupChange,
                modifier = Modifier.weight(1f),
                label = { Text("Collection") },
                enabled = state.action == null,
                singleLine = true,
            )
            OutlinedTextField(
                value = stream,
                onValueChange = onStreamChange,
                modifier = Modifier.weight(1f),
                label = { Text("Stream") },
                enabled = state.action == null,
                singleLine = true,
            )
        }
        OutlinedButton(
            onClick = onPickVideo,
            enabled = state.action == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.selectedFile.isBlank()) "Choose video" else "Choose another video")
        }
        if (state.selectedFile.isNotBlank()) {
            Text(
                text = "Selected: ${state.selectedFile}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onUpload,
                enabled = state.action == null && state.selectedFile.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.action == WorkflowAction.UPLOAD) "Uploading…" else "1. Upload")
            }
            Button(
                onClick = onCreateIndex,
                enabled = state.action == null && state.uploadedFile.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.action == WorkflowAction.INDEX) "Indexing…" else "2. Create index")
            }
        }
        if (state.action == WorkflowAction.UPLOAD || state.uploadProgress > 0) {
            LinearProgressIndicator(
                progress = state.uploadProgress / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Upload: ${state.uploadProgress}%", style = MaterialTheme.typography.bodySmall)
        }
        if (state.uploadedFile.isNotBlank()) {
            Text("Uploaded: ${state.uploadedFile}", style = MaterialTheme.typography.bodySmall)
        }
        if (state.indexStatus.isNotBlank()) {
            val job = state.indexJobId.takeIf { it.isNotBlank() }?.let { " · job $it" }.orEmpty()
            Text(
                text = "Index: ${state.indexStatus}$job",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.indexReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Button(
            onClick = onSearch,
            enabled = canSearch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.action == WorkflowAction.SEARCH) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("3. Search")
            }
        }
        TextButton(
            onClick = onForgetKey,
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Forget API key")
        }
    }
}

private fun LazyGridScope.searchBody(state: SearchUiState) {
    when {
        state.action == WorkflowAction.SEARCH -> fullWidthMessage("Searching and resolving images…")
        state.error.isNotBlank() -> fullWidthMessage(state.error, isError = true)
        state.images.isNotEmpty() -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Showing ${state.images.size} images from ${state.total} matches · ${state.elapsedMs.toInt()} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(state.images, key = SearchImage::id) { image ->
                Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                    SearchImageCard(image)
                }
            }
        }
        state.searched -> fullWidthMessage("No image-backed matches were found.")
        state.action == WorkflowAction.UPLOAD -> fullWidthMessage("Uploading the selected video…")
        state.action == WorkflowAction.INDEX -> fullWidthMessage("Waiting for the index job to finish…")
        else -> fullWidthMessage("Choose a video, upload it, create its index, then search the same collection and stream.")
    }
}

private fun LazyGridScope.fullWidthMessage(text: String, isError: Boolean = false) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Message(text, isError)
    }
}

@Composable
private fun SearchImageCard(image: SearchImage) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            SubcomposeAsyncImage(
                model = image.bytes,
                contentDescription = image.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                },
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Image unavailable", modifier = Modifier.padding(12.dp))
                    }
                },
                success = { SubcomposeAsyncImageContent() },
            )
            Text(
                text = image.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            if (image.title != image.filename) {
                Text(
                    text = image.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                )
            }
            Text(
                text = buildList {
                    add("stream: ${image.stream}")
                    if (image.timestamp.isNotBlank()) add("time: ${image.timestamp}")
                    if (image.score.isNotBlank()) add("score: ${image.score}")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
