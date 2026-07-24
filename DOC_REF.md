# Module VModal Android SDK

The `com.vmodal.sdk` package provides typed Kotlin clients for authentication,
search, collection lifecycle, index lifecycle, media access, and resumable
uploads. This reference is generated from public Kotlin declarations and KDoc.
It intentionally omits service hosts, endpoint paths, wire-level route tables,
and implementation source.

For new content integrations, use `VModal.configure` and create an immutable
`VModalScope` for one project, collection, and stream. Use `Client.coroutines`
for authentication, administration, images, R2, and advanced compatibility
operations that are not represented by the scoped facade. Existing resource
methods remain supported.

## Start here

- Create a `VModalProject` with `VModal.configure`, then select content with
  `VModalProject.scope`.
- Reuse that immutable `VModalScope` for upload, metadata, search, asset,
  index, and deletion operations so organization fields cannot drift.
- Create a lower-level `Client` from `SdkConfig`, or use `Client.fromEnv`, for
  authentication and advanced resources.
- Create `CoroutineClient` with `Client.coroutines`; the caller owns its
  ViewModel, lifecycle, application, or worker scope.
- Confirm identity with `CoroutineAuthResource.me`, browse collections with
  `CoroutineCollectionsResource.listGroups`, search with
  `CoroutineSearchesResource.searchVideo`, and manage indexes through the
  coroutine facade.
- Collect `CoroutineCollectionsResource.videoUploadEvents` once for upload
  progress and the final `VideoUploadEvent.Completed` result.
- Keep `videoUploadAsync` and blocking `videoUpload` for callback, Java, and
  incremental-migration consumers.
- Handle failures through the `SdkError` hierarchy.

## Client resources

`Client` owns the effective configuration, transport, and resource objects for
authentication, search, collections, indexes, administration, object storage,
and images. The Google Drive and SQL resources remain compatibility placeholders
and fail with `FeatureDisabled` before transport.

`VModalProject` and `VModalScope` are immutable high-level delegates over those
same clients. They encode `projectId` plus `collectionName` internally and map
`streamName` consistently across content operations. Public scoped options do
not expose backend organization fields.

`CoroutineClient` is a lightweight facade over that same client. It creates no
scope, retains no Android lifecycle object, and never selects
`Dispatchers.Main`. Built-in cancellable transport calls cancel promptly with
their caller. A legacy synchronous custom transport can stop result delivery
but may not stop arbitrary blocking I/O immediately.

`Client.unsafeDirect` is a trusted-network escape hatch. Do not treat caller
identity values as an Internet trust boundary. Close or clear a mutable
`ApiKeyProvider` when the credential lifecycle ends.

## Requests and responses

Request models expose typed fields, defaults, validation, and conversion helpers.
Typed response models preserve their original decoded payload through `raw` so
applications can tolerate newly added service fields without losing data.

The primary request types are `SearchRequest`, `DeleteCollectionRequest`,
`CollectionAddAssetsRequest`, `IndexationSubmitRequest`, and
`IndexationDeleteRequest`. Resource methods return typed models such as
`SearchResponse`, `GroupsResponse`, `IndexationStatusResponse`, and
`ImageUrlResponse`.

## Uploads

`UploadSource` abstracts files and replayable streams. `VideoUploadOptions`
controls retries, cancellation-aware progress, optional resume state, and the
explicit multipart opt-in. `UploadHandle.cancel` requests cancellation for
asynchronous operations; completion and failure callbacks run at most once.

`VideoUploadEvent` exposes `Progress` and one final `Completed` result through a
cold Flow. Each collection starts an independent upload. Collector cancellation
cancels the shared `UploadHandle`; applications that need multiple observers
must share the single collection in an app-owned scope.

`UploadSessionStore` persists resumable state. Use `MemoryUploadSessionStore`
for process-local work or `FileUploadSessionStore` when WorkManager must resume
after process restart. `AdaptiveUploadPolicy` chooses a preset from file size
and `UploadConditions` without reading Android platform state itself.

## Errors

- `AuthError` asks the caller to renew or replace credentials.
- `ValidationFailed` identifies invalid local input or rejected request data.
- `ApiError` preserves service status and response details for diagnostics.
- `FeatureDisabled` is deterministic and should not be retried.
- `TransportError` wraps connectivity failures.
- `ResponseTooLarge` protects bounded response reads.
- `MalformedResponse` identifies invalid structured response content.

## Low-level extension points

Most applications should use `Client`. Tests and custom integrations may inject
`VmodalTransport` or `SignedUploadTransport`, create `VmodalFilePart` values
with `filePart` or `streamPart`, and use `VmodalJson` for SDK-compatible JSON
handling. These hooks are public contracts; protocol orchestration helpers and
parser internals are intentionally absent from this reference.
