package com.vmodal.sdk.examples.app1

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
fun KitchenScreen(vm: KitchenViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.usePickedVideo(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Header()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectCard(st, vm)
            VideoCard(st, vm) { picker.launch(arrayOf("video/*")) }
            ScopeCard(st, vm)
            PipelineCard(st, vm)
            SearchCard(st, vm)
            if (st.frames.isNotEmpty()) {
                ResultsBlock(st)
            }
            Banner(st)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "Kitchen Search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Semantic video search with the V-Modal Android SDK",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun ConnectCard(st: KitchenUiState, vm: KitchenViewModel) {
    StepCard(1, "Connect") {
        OutlinedTextField(
            value = st.apiKey,
            onValueChange = vm::onApiKey,
            label = { Text("Runtime API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = vm::connect,
                enabled = st.busy == Busy.NONE,
            ) {
                Text("Connect")
            }
            if (st.connectedUser.isNotBlank()) {
                Text(
                    text = "Connected as ${st.connectedUser}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun VideoCard(st: KitchenUiState, vm: KitchenViewModel, onPick: () -> Unit) {
    StepCard(2, "Video") {
        Text(
            text = "Best demo: a 20-40 s clip with clear objects — sugar, egg, knife, mixing, baking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::useBundledVideo) {
                Text("Bundled clip")
            }
            Button(onClick = onPick) {
                Text("Pick from device")
            }
        }
        if (st.videoName.isNotBlank()) {
            Text(
                text = "Selected: ${st.videoName} (${st.videoOrigin})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ScopeCard(st: KitchenUiState, vm: KitchenViewModel) {
    StepCard(3, "Collection & stream") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = st.collection,
                onValueChange = vm::onCollection,
                label = { Text("Collection") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = st.stream,
                onValueChange = vm::onStream,
                label = { Text("Stream") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PipelineCard(st: KitchenUiState, vm: KitchenViewModel) {
    StepCard(4, "Upload · Index") {
        Button(
            onClick = vm::upload,
            enabled = st.connectedUser.isNotBlank() && st.videoName.isNotBlank() && st.busy == Busy.NONE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Upload video")
        }
        if (st.busy == Busy.UPLOAD || st.uploaded) {
            LinearProgressIndicator(
                progress = { st.uploadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = vm::createIndex,
            enabled = st.uploaded && st.busy == Busy.NONE,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create index")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = vm::refreshIndex,
                enabled = st.indexStatus.isNotBlank() && st.busy == Busy.NONE,
            ) {
                Text("Index status")
            }
            if (st.indexStatus.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = st.indexStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (st.indexReady) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchCard(st: KitchenUiState, vm: KitchenViewModel) {
    StepCard(5, "Search") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = st.query,
                onValueChange = vm::onQuery,
                label = { Text("Query, e.g. sugar, egg, cutting") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = vm::search,
                enabled = st.indexReady && st.busy == Busy.NONE,
            ) {
                Text("Search")
            }
        }
        if (st.searched && st.searchInfo.isNotBlank()) {
            Text(
                text = st.searchInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ResultsBlock(st: KitchenUiState) {
    Text(
        text = "Results",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        st.frames.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { frame ->
                    FrameCell(frame)
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.FrameCell(frame: ResultFrame) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = frame.model,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = frame.caption,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun Banner(st: KitchenUiState) {
    val isError = st.error.isNotBlank()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Text(
            text = if (isError) st.error else st.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(14.dp),
        )
    }
}
