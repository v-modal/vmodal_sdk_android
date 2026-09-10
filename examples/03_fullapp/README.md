<a id="fullapp-demo"></a>

# VModal Android full search application


1. Connect with a runtime API key; the app verifies it and loads collections.
2. Keep the generated demo collection or enter an existing visible collection.
3. Upload the bundled 10-frame sample or a video selected from Android.
4. Create an image index; the app waits for the asynchronous job automatically.
5. Search the indexed collection and inspect ranked frames in a responsive
   image grid.

The example deliberately exposes each stage as a separate action. A beginner
can validate one SDK contract at a time and stop at the first failed stage.

This is a downstream consumer smoke application, not a reusable VModal UI
library. A consuming app owns navigation, state presentation, accessibility,
theming, and its design system.

![VModal Android application and searchable media screens](../../assets/dev_homepage.jpg)

```text
authentication
  -> accessible collections
  -> existing indexed data OR upload
  -> index ready
  -> search hits
  -> one bulk image-URL lookup
  -> adaptive image grid
```

An empty collection list or empty search result is valid. It means the account
needs data or the query has no match; it is different from an authentication,
network, or API error.

## What you need

- Android Studio with Android SDK Platform 34
- JDK 17 (the command-line wrapper selects an installed JDK 17 automatically)
- An Android device or emulator running Android 7.0 (API 24) or newer
- A valid VModal runtime API key supplied by your authenticated application or
  VModal administrator

The project uses Kotlin 1.9.24, Android Gradle Plugin 8.4.2, Material 3,
coroutines, `StateFlow`, lifecycle-aware Compose state collection, and Coil
2.6.0 for image loading.

> Never commit an API key, put it in Android resources, `BuildConfig`, Gradle
> properties, `local.properties`, the manifest, logs, or a deep link. This app
> accepts the key at runtime, clears the input after a successful connection,
> and keeps the credential only in `MutableApiKeyProvider` memory.

## Run from Android Studio

1. In Android Studio, set **Settings > Build Tools > Gradle > Gradle JDK** to
   JDK 17. Do this before the first sync; newer bundled JBR versions are not
   compatible with the pinned Gradle 8.6 build.
2. Open `uinterface/sdk_android/examples/03_fullapp` as the project.
3. Allow Gradle to sync and install Android SDK Platform 34 if requested.
4. Select the `app` run configuration.
5. Start an API 24+ emulator or connect an Android device.
6. Run **VModal Full Search**.

The settings include the SDK source project from `../..`, so local SDK changes
are available to the app after a Gradle rebuild.

## Build or install from the command line

Clone the complete public SDK repository, then run one install command:

```bash
git clone https://github.com/v-modal/vmodal_sdk_android.git
cd vmodal_sdk_android/examples/03_fullapp
./gradlew --no-daemon :app:installDebug
```

The example has its own reviewed Gradle 8.6 wrapper. It automatically selects a
locally installed JDK 17 and the standard Android SDK location on macOS, Linux,
or Windows. If a required tool is missing, it stops before Gradle with a direct
install/setup instruction. The first build downloads dependencies and compiles
the SDK source, so it can take a few minutes; later builds reuse the cache. The
debug APK is written under `app/build/outputs/apk/debug/`.

The example intentionally consumes the SDK source at `../..`. Clone the whole
repository; copying only `03_fullapp` is not a supported source build. Check
device visibility with `adb devices`, then open **VModal Full Search**.

## 1. Connect and verify the key

1. Paste a current credential into **Runtime API key**.
2. Tap **Connect and verify key**.
3. Continue only after the app says it is connected and collections are loaded.

The app creates the public-gateway client with an in-memory provider:

```kotlin
val provider = MutableApiKeyProvider(apiKey)
val client = Client(
    baseUrl = PUBLIC_GATEWAY_URL,
    apiKeyProvider = provider,
)
```

Client construction does not validate a key, so the same Connect action
immediately calls:

```kotlin
val me = client.auth.me()
val groups = client.collections.listGroups("vid_file")
```

On success, the app rebuilds the immutable client with the resolved identity,
loads collections, marks the session connected, and clears the visible key
field. On failure it destroys the client, keeps the key editable, and explains
whether to replace the key, correct input, check connectivity, wait after a
rate limit, or retry a temporary server failure.

## 2. Choose the collection and stream

The app generates a new name such as `android_demo_2f04bc191a` on startup. Keep
it for the bundled demo so data from a shared beta credential does not collide
with another run. To search existing data, enter a collection shown in the
bounded five-name preview. The complete list remains available internally and
can be refreshed with **Refresh collections**.

Use **Refresh collections** whenever another upload or client may have changed
the account. Collection access is credential-scoped: a name from another
account or environment can return HTTP 404 even when the API route is healthy.

The screen shows the index job created in the current run. An application that
also needs jobs from previous runs can load them with:

```kotlin
val jobs = client.indexes.jobsList(
    mode = "vid_file",
    groupName = collectionName,
)
```

A completed state such as `success`, `completed`, `done`, or `ok` means that
the collection may already be searchable. A queued state should be checked by
job ID, while an empty list means a new index may be needed.

<a id="fullapp-upload"></a>

## 3. Select or upload a video

The default source is
[`asset/video_10frames.mp4`](asset/video_10frames.mp4), a small one-second,
320 × 240 H.264 video with exactly 10 frames. Gradle packages this existing
file as an Android asset. The app reads its small byte array once and gives the
SDK a reopenable `UploadSource`.

To use it:

1. Leave the default Collection and Stream, or choose your intended scope.
2. Tap **Use sample** if another file is currently selected.
3. Tap **Upload**.
4. Watch the progress indicator and final filename.
5. Use **Cancel** to cancel an active upload. The sample is small and may finish
   before cancellation can be tapped.

One generated new-data scope looks like:

```text
collection: android_demo_2f04bc191a
stream:     astream
```

To upload your own video, tap **Choose video**. Android's Storage Access
Framework returns a `content://` URI. `AndroidUploadSource.kt` reads its display
name, exact size, and MIME type through `ContentResolver`, then reopens the URI
whenever the SDK needs a stream. No storage permission or device filesystem
path is required.

The upload uses one consistent contract:

```kotlin
client.collections.videoUploadAsync(
    source = source,
    collectionName = collectionName,
    subCollectionName = streamName,
    mode = "vid_file",
    modality = "vid_raw",
    onProgress = { progress -> /* update UI */ },
    onSuccess = { result -> /* continue to indexing */ },
    onFailure = { error -> /* show error */ },
)
```

Changing Collection or Stream clears upload, index, and search state so a job
or result from one scope is not accidentally presented as belonging to another.

<a id="fullapp-index"></a>

## 4. Create and inspect the image index

After an upload, or when an existing collection has no ready image index:

1. Tap **Create index and wait**.
2. Watch the returned job ID and live queued/running state.
3. Continue when the app reports **Index is ready**.

The index request uses the same Collection and Stream as upload:

```kotlin
val job = client.indexes.createIndex(
    mode = "vid_file",
    groupName = collectionName,
    streamName = streamName,
    version = "new_version",
    reProcess = true,
)
```

Index creation is asynchronous. A successful submit response means only that
the job was accepted. The app polls `client.indexes.indexStatus(jobId)` every
five seconds, stops on recognized success or failure states, and times out
after ten minutes with a retry instruction. Scope, credential, and lifecycle
changes cancel the wait.

<a id="fullapp-search"></a>

## 5. Search and inspect results

1. Keep the default query, `red`, for the bundled colored sample or enter a
   description for your video.
2. Tap **Search** after the image index is ready.
3. Inspect the responsive grid. Each card shows the ranked frame, title,
   filename, stream, timestamp, and score when those fields are available.

Before every search, the app refreshes the authenticated key's collections,
checks that the requested collection is visible, and obtains its latest
advertised LanceDB version:

```kotlin
val groups = client.collections.listGroups("vid_file")
val group = groups.findGroup(collectionName, "vid_file")
val version = group?.latestLancedbVersion

val result = client.searches.searchVideo(
    queryText = query,
    mode = "vid_file",
    groupName = collectionName,
    streamName = streamName,
    limit = 50,
    textEmbScoreMin = 0.0,
    imageEmbScoreMin = 0.0,
    versionLancedb = version,
)
```

Search stops locally when the collection is not returned for the current key
or has no LanceDB version. This avoids silently requesting an unrelated default
index. The bounded request returns at most 50 ranked rows. Every row with a
usable video filename or indexed `title` becomes an image candidate, and all
candidates are resolved with one call:

```kotlin
val urls = client.images.getUrlBulk(candidateRecords)
```

Candidate records use `mode=vid_file`, `modality=vid_img`, the selected
collection, the hit's `stream`/`stream_name`, the normalized video basename or
indexed `title`, and an optional normalized 13-digit timestamp from
`ts_unix`. The bulk response's `input_index` points into that
candidate request, not directly into the unfiltered search rows. The app
validates every index, rejects duplicates and invalid or blank records, then
restores ranked search order. This prevents a partial or out-of-order bulk
response from attaching an image to another hit's metadata.

The summary deliberately keeps four values separate:

- total backend matches from `cntTotal`;
- rows returned in this response from `cntActual`;
- server search duration from `executionTimeMs`;
- resolved images currently displayed in the grid.

A zero-match response displays **No matching results**. Matches without a
usable/resolved image display **No image-backed matches were found**. A decode
or image-network failure displays **Image unavailable** only in that card;
successful sibling cards remain visible.

Signed image URLs are temporary. The app obtains fresh URLs for each search,
keeps them only in ViewModel/UI memory, and clears them when the query, scope,
credential, upload, or index work changes. The SDK preserves absolute object
storage URLs and qualifies a relative `url_pre_signed` proxy path against the
configured search-API base. The returned `/image/get_image` locator is a
POST-only API contract, not a Coil-loadable GET URL. The example sends all
resolved locators through `images.getImageBulkFromUrls()`, decodes the returned
base64 content into byte arrays, and gives those bytes to Coil. URLs are never
used as card IDs, persisted, or logged.

<a id="fullapp-credentials"></a>

## Credential and lifecycle behavior

- The API-key field uses ordinary in-memory Compose state, not saved state.
- **Connect and verify key** clears the visible key only after `auth.me()` and
  collection loading succeed.
- **Forget API key** cancels ViewModel-owned work, clears the provider and
  client, restores the bundled sample, and resets the workflow.
- Replacing the key clears identity, collection, upload, index, and result state.
- `onCleared()` cancels work and clears the credential provider.
- Upload cancellation also cancels the SDK `UploadHandle`.

A production application should obtain a user-scoped, revocable credential
from its authenticated backend and inject it into the SDK. The runtime text
field exists only to make this local integration example self-contained.

## Source layout

```text
app/src/main/
├── AndroidManifest.xml
└── kotlin/com/vmodal/sdk/examples/fullapp/
    ├── MainActivity.kt          Compose host and light/dark theme
    ├── FullAppScreen.kt         Staged controls and adaptive image grid
    ├── FullAppViewModel.kt      Workflow, deterministic image join, lifecycle
    └── AndroidUploadSource.kt   Bundled asset and content-URI adapters
app/src/test/.../FullAppSearchMappingTest.kt
                                Offline search/image contract tests
app/src/androidTest/.../FullAppSearchGridTest.kt
                                Compose grid and card-state tests
```

`FullAppViewModel` runs blocking SDK requests on `Dispatchers.IO`, bridges the
asynchronous upload callback to a cancellable coroutine, and publishes one
immutable `StateFlow<FullAppUiState>`. The screen collects it with
`collectAsStateWithLifecycle()`.

Run the offline tests and debug build from this example directory:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Run the live upload, indexation, `blue` search, and image retrieval check from
the examples directory:

```bash
VMODAL_API_KEY="..." bash test.sh live_03_fullapp
```

The live check exercises both bulk URL resolution and the authenticated bulk
image-content POST used by the app, then validates that the returned body has a
supported encoded-image structure and nonzero dimensions. It never prints the
URL, its query credential, or the runtime Bearer token.

With an unlocked API 24+ emulator or device connected, run the Compose tests:

```bash
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

## Test the isolated SDK artifact

By default, the example compiles the SDK source checkout. To verify the exact
checksummed Maven artifact used by pull-request CI, run:

```bash
cd uinterface/sdk_android
bash test.sh ci
```

The dispatcher passes an isolated `vmodalMavenRepo` and exact SDK version. In
Maven mode this app resolves the SDK coordinate only from that repository,
runs deterministic unit tests with live retrieval disabled, and never falls
back to global Maven Local or the SDK source project.

## Troubleshooting

### Android SDK location not found

Install Android SDK 34 in Android Studio and either open the project there or
set `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the command-line shell. Do not commit
the machine-specific `local.properties` file.

### `Connect and verify the key first`

Enter a runtime key and tap **Connect and verify key** before collection,
upload, index, or search operations.

### Authentication or network error

Confirm that the key is current, belongs to the intended VModal environment,
and that the device can reach the public gateway. Tap **Forget API key** before
switching identities.

### The selected video has no size

Choose a document provider that exposes a readable stream and exact byte size.
Signed uploads require a known content length. The bundled sample always has a
known size.

### Index remains queued or running

Indexing is asynchronous and can take several minutes. The app waits for up to
ten minutes. After a reported failure or timeout, confirm the collection and
stream, then tap **Create index and wait** again. A production app can restore
the job later through `jobsList()`.

### Search says the collection is unavailable

Tap **Refresh collections** and select a name visible to the current API key.
For a newly uploaded collection, wait until it appears and its image index
advertises a LanceDB version.

### Search finds matches but no images are displayed

**No image-backed matches were found** means the backend count is non-zero but
none of the returned rows produced a valid image candidate/URL. Confirm the
same collection and stream were used for upload, indexing, search, and image
lookup, then search again to obtain fresh temporary URLs.

### One card says `Image unavailable`

That card's temporary URL may have expired, connectivity may have changed, or
the image bytes may not decode. Other cards are independent and remain usable.
Search again to resolve a fresh URL and image-content batch. Filter Logcat by
`VModalFullAppImage`; failures report only the exception class chain and an HTTP
status when one is available. Signed URL values, query strings, exception
messages, and credentials are not logged.

## Related documentation

- [Android SDK README](../../README.md)
- [SDK behavior and uploads](../../docs/sdk_doc.md)
- [Runtime API-key management](../../docs/manage_api_key.md)
- [Search application guide](../../docs/search_app.md)
