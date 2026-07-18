# Module VModal Android SDK

The `com.vmodal.sdk` package provides typed Kotlin clients for authentication,
search, collection lifecycle, index lifecycle, media access, and resumable
uploads. This reference is generated from public Kotlin declarations and KDoc.
It intentionally omits service hosts, endpoint paths, wire-level route tables,
and implementation source.

Unless a method name ends in `Async`, assume it is blocking and call it from
`Dispatchers.IO`, WorkManager, or another worker thread.

## Start here

- Create a `Client` from `SdkConfig`, or use `Client.fromEnv` in tools that
  already manage environment configuration.
- Confirm identity with `AuthResource.me` and availability with `Client.health`.
- Browse collections with `CollectionsResource.listGroups`, search with
  `SearchesResource.searchVideo`, and manage indexes with `IndexesResource`.
- Start cancelable uploads with `videoUploadAsync`. Use `videoUpload` only from
  a worker thread when blocking behavior is appropriate.
- Handle failures through the `SdkError` hierarchy.

## Client resources

`Client` owns the effective configuration, transport, and resource objects for
authentication, search, collections, indexes, administration, object storage,
and images. The Google Drive and SQL resources remain compatibility placeholders
and fail with `FeatureDisabled` before transport.

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
