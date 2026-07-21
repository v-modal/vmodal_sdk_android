# Coroutines and upload Flow

Use the coroutine facade for new Kotlin integrations. Existing
[blocking resources](../DOC_REF.md) and the callback-based
[`videoUploadAsync`](../DOC_REF.md) remain supported for incremental migration
and Java callers.

## Entry point and ownership

`Client.coroutines()` returns a lightweight `CoroutineClient` over the same
configured client:

```kotlin
val api = client.coroutines()
val groups = api.collections.listGroups("vid_file")
val me = api.auth.me()
```

The facade owns no `CoroutineScope`, Android lifecycle, worker, or UI state.
The calling application owns the scope. The default fallback dispatcher is
worker-safe; pass a dispatcher only for a custom legacy transport or a
deterministic test. SDK calls never select `Dispatchers.Main`.

Use `viewModelScope` for screen work. Collect the resulting `StateFlow` in
Compose or Views with lifecycle awareness:

```kotlin
class SearchViewModel(private val client: Client) : ViewModel() {
    private val mutableResults = MutableStateFlow<SearchResponse?>(null)
    val results = mutableResults.asStateFlow()

    fun search(query: String) {
        viewModelScope.launch {
            mutableResults.value = client.coroutines().searches.searchVideo(
                queryText = query,
                groupName = "travel",
                streamName = "astream",
            )
        }
    }
}

@Composable
fun SearchRoute(viewModel: SearchViewModel) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    // Render results. Collection stops when the UI lifecycle is inactive.
}
```

`viewModelScope` supplies the UI lifecycle and main-thread state semantics. The
SDK performs its own cancellable network suspension; wrapping coroutine calls
in `withContext(Dispatchers.IO)` is unnecessary.

## Upload progress and completion

`videoUploadEvents` is a cold `Flow<VideoUploadEvent>`. Construction performs
no I/O. Each collection starts one independent upload and ends after one
`Completed` event:

```kotlin
viewModelScope.launch {
    client.coroutines().collections.videoUploadEvents(
        source = source,
        collectionName = "travel",
        subCollectionName = "mobile",
    ).collect { event ->
        when (event) {
            is VideoUploadEvent.Progress -> showProgress(event.progress.percent)
            is VideoUploadEvent.Completed -> showResult(event.response)
        }
    }
}
```

Cancelling the collector cancels the shared `UploadHandle` and its active
signed requests. Failure terminates the Flow with the original typed SDK
error. `CancellationException` stays cancellation and must be rethrown when a
caller catches broad exceptions.

Do not collect the same cold Flow twice unless two uploads are intended. If
several UI consumers need one operation, collect it once in an app-owned scope
and publish app state with `stateIn`, `shareIn`, or a repository-owned
`StateFlow`. Sharing policy, replay, and lifetime belong to the application;
the SDK does not create an application scope.

Use `collections.videoUpload(...)` when only the final response is needed. It
is suspend and accepts an optional progress callback. Keep
`CollectionsResource.videoUploadAsync(...)` and its `UploadHandle` for existing
callback integrations.

## WorkManager

Use `CoroutineWorker` for an upload that must outlive a screen. Persist a URI
only after the scheduling Activity has taken persistable read permission, and
obtain the configured `Client` from application/session-owned dependencies.
Do not persist a bearer token in `WorkRequest` input data.

```kotlin
override suspend fun doWork(): Result {
    return try {
        client.coroutines().collections.videoUploadEvents(
            source = sourceFromPersistedUri(),
            collectionName = collection,
            subCollectionName = stream,
        ).collect { event ->
            if (event is VideoUploadEvent.Progress) {
                setProgress(workDataOf("progress" to event.progress.percent))
            }
        }
        Result.success()
    } catch (error: CancellationException) {
        throw error // Worker cancellation; never Result.retry().
    } catch (error: Exception) {
        if (runAttemptCount < 2 && error.isTransient()) Result.retry()
        else Result.failure()
    }
}
```

Retry only bounded, appropriate transient failures such as connectivity errors
or HTTP `408`, `429`, `500`, `502`, `503`, and `504`. Never retry cancellation,
authentication, validation, disabled features, malformed responses, or an
unsafe mutation merely because its outcome is unknown. Reconcile server state
before replaying an ambiguous mutation.

The compile-checked complete pattern is
[`VmodalUploadWorker.kt`](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt).

## Cancellation guarantees

The built-in cancellable transport cancels the active HTTP call promptly.
Coroutine cancellation is never wrapped as `SdkError`. Upload collection
cancellation also cancels the upload handle and all active signed-part calls.

An injected legacy `VmodalTransport` has a weaker guarantee: cancellation
stops result delivery and interrupts worker execution where supported, but
arbitrary blocking transport code may continue until it returns. Use the
built-in transport when prompt underlying I/O cancellation is required.

## Operation-by-operation migration

Suspend operations use the blocking resource names under `Client.coroutines()`:

| Existing API | Preferred Kotlin API |
| --- | --- |
| `client.auth.me()` | `client.coroutines().auth.me()` |
| `client.collections.listGroups(...)` | `client.coroutines().collections.listGroups(...)` |
| `client.searches.searchVideo(...)` | `client.coroutines().searches.searchVideo(...)` |
| `client.indexes.createIndex(...)` | `client.coroutines().indexes.createIndex(...)` |
| `client.indexes.indexStatus(...)` | `client.coroutines().indexes.indexStatus(...)` |
| `client.images.getUrlBulk(...)` | `client.coroutines().images.getUrlBulk(...)` |
| `videoUploadAsync(... callbacks ...)` | `client.coroutines().collections.videoUploadEvents(...)` |
| blocking `videoUpload(...)` extension | `client.coroutines().collections.videoUpload(...)` |

The same facade covers authentication, collections, search, indexes, images,
admin, and R2 resources. Request models, defaults, route selection, retry
safety, response models, and the `SdkError` hierarchy stay aligned with the
blocking API. Disabled operations still throw `FeatureDisabled`.

Migrate one operation at a time. A coroutine ViewModel can temporarily call a
remaining blocking operation inside an app-owned `Dispatchers.IO` context,
while new calls use the facade. Existing callback upload code can remain until
its owning screen or worker is migrated. See the
[Android integration cookbook](android_integration_cookbook.md),
[blocking/upload guide](sdk_doc.md), and [generated API reference](../DOC_REF.md).

## Published dependency

`kotlinx-coroutines-core` 1.8.1 is a public API dependency and appears with
compile scope in the published POM. Consumers receive the public suspend and
Flow types from the SDK artifact dependency.
