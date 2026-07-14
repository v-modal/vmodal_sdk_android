package com.vmodal.sdk.examples.search

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
    var apiKey by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("agroup") }
    var stream by rememberSaveable { mutableStateOf("astream") }
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        vm.search(apiKey, query, group, stream)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("VModal image search") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SearchForm(
                apiKey = apiKey,
                query = query,
                group = group,
                stream = stream,
                loading = state.loading,
                onApiKeyChange = { apiKey = it },
                onQueryChange = { query = it },
                onGroupChange = { group = it },
                onStreamChange = { stream = it },
                onSearch = submit,
                onForgetKey = {
                    apiKey = ""
                    vm.clearCredentials()
                },
            )
            SearchBody(state)
        }
    }
}

@Composable
private fun SearchForm(
    apiKey: String,
    query: String,
    group: String,
    stream: String,
    loading: Boolean,
    onApiKeyChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onGroupChange: (String) -> Unit,
    onStreamChange: (String) -> Unit,
    onSearch: () -> Unit,
    onForgetKey: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Runtime API key") },
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
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
                singleLine = true,
            )
            OutlinedTextField(
                value = stream,
                onValueChange = onStreamChange,
                modifier = Modifier.weight(1f),
                label = { Text("Stream") },
                singleLine = true,
            )
        }
        Button(
            onClick = onSearch,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Search")
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

@Composable
private fun SearchBody(state: SearchUiState) {
    when {
        state.loading -> Message("Searching and resolving images…")
        state.error.isNotBlank() -> Message(state.error, isError = true)
        state.images.isNotEmpty() -> {
            Text(
                text = "Showing ${state.images.size} images from ${state.total} matches · ${state.elapsedMs.toInt()} ms",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.images, key = SearchImage::id) { image ->
                    SearchImageCard(image)
                }
            }
        }
        state.searched -> Message("No image-backed matches were found.")
        else -> Message("Enter a query, collection, and stream to search video frames.")
    }
}

@Composable
private fun SearchImageCard(image: SearchImage) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            SubcomposeAsyncImage(
                model = image.url,
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
