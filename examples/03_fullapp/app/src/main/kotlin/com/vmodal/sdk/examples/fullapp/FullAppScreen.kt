package com.vmodal.sdk.examples.fullapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullAppScreen(vm: FullAppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var apiKey by remember { mutableStateOf("") }
    val busy = state.action != null
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
        topBar = { TopAppBar(title = { Text("VModal full search app") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionTitle("1. Configure and authenticate")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Runtime API key") },
                    supportingText = { Text("Injected by the parent app; never committed.") },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = {
                        val hasKey = apiKey.trim().isNotEmpty()
                        vm.configure(apiKey)
                        if (hasKey) apiKey = ""
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Configure client")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = vm::resolveIdentity,
                        enabled = !busy && state.configured,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Resolve auth.me")
                    }
                    OutlinedButton(
                        onClick = vm::refreshCollections,
                        enabled = !busy && state.configured,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Refresh collections")
                    }
                }
            }

            item {
                SectionTitle("2. Select the data scope")
                OutlinedTextField(
                    value = state.collection,
                    onValueChange = vm::setCollection,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Collection") },
                    supportingText = { Text("Must be visible to this API key before search.") },
                    enabled = !busy,
                    singleLine = true,
                )
                if (state.collectionsLoaded) {
                    Text(
                        if (state.collections.isEmpty()) {
                            "Available collections: none"
                        } else {
                            "Available collections: ${state.collections.joinToString()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = state.stream,
                    onValueChange = vm::setStream,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Stream") },
                    enabled = !busy,
                    singleLine = true,
                )
            }

            item {
                SectionTitle("3. Upload a video")
                Text(
                    "Selected: ${state.selectedFile}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = vm::useBundledSample,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Use sample")
                    }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("video/*")) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Choose video")
                    }
                }
                LinearProgressIndicator(
                    progress = state.uploadProgress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = vm::upload,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.action == FullAppAction.UPLOAD) "Uploading…" else "Upload")
                    }
                    OutlinedButton(
                        onClick = vm::cancelUpload,
                        enabled = state.action == FullAppAction.UPLOAD,
                    ) {
                        Text("Cancel")
                    }
                }
                if (state.uploadedFile.isNotBlank()) {
                    Text("Uploaded: ${state.uploadedFile}", style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SectionTitle("4. Create and inspect the index")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = vm::createIndex,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Create index")
                    }
                    OutlinedButton(
                        onClick = vm::refreshIndex,
                        enabled = !busy && state.indexJobId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Refresh status")
                    }
                }
                Text(
                    "Index: ${state.indexStatus}${state.indexJobId.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item {
                SectionTitle("5. Search")
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search query") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focus.clearFocus()
                            if (!busy) vm.search()
                        },
                    ),
                )
                Button(
                    onClick = {
                        focus.clearFocus()
                        vm.search()
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.action == FullAppAction.SEARCH) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Search")
                    }
                }
                if (state.searched && state.results.isEmpty()) {
                    Text("No matching results.")
                }
            }

            items(state.results, key = SearchItem::id) { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            result.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                }
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = {
                        apiKey = ""
                        vm.forgetApiKey()
                    },
                    enabled = state.configured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Forget API key")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}
