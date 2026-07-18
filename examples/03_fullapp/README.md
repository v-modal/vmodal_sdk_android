# VModal Android full search application


1. Configure the SDK with a runtime API key.
2. Resolve the authenticated identity and list its video collections.
3. Reuse an existing collection or upload the bundled 10-frame sample.
4. Create an image index and refresh its asynchronous job status.
5. Search the indexed collection and inspect result fields.

The example deliberately exposes each stage as a separate action. A beginner
can validate one SDK contract at a time and stop at the first failed stage.

```text
authentication
  -> accessible collections
  -> existing indexed data OR upload
  -> index ready
  -> search
```

An empty collection list or empty search result is valid. It means the account
needs data or the query has no match; it is different from an authentication,
network, or API error.

## What you need

- Android Studio with Android SDK 34
- JDK 17
- An Android device or emulator running Android 7.0 (API 24) or newer
- A valid VModal runtime API key supplied by your authenticated application or
  VModal administrator

The project uses Kotlin 1.9.24, Android Gradle Plugin 8.4.2, Material 3,
coroutines, `StateFlow`, and lifecycle-aware Compose state collection.

> Never commit an API key, put it in Android resources, `BuildConfig`, Gradle
> properties, `local.properties`, the manifest, logs, or a deep link. This app
> accepts the key at runtime, clears the input after configuration, and keeps
> the credential only in `MutableApiKeyProvider` memory.

## Run from Android Studio

1. Open `uinterface/sdk_android/examples/03_fullapp` as the project.
2. Allow Gradle to sync and install any requested Android SDK 34 components.
3. Select the `app` run configuration.
4. Start an API 24+ emulator or connect an Android device.
5. Run **VModal Full Search**.

The settings include the SDK source project from `../..`, so local SDK changes
are available to the app after a Gradle rebuild.

## Build or install from the command line

From this directory:

```bash
./gradlew --no-daemon :app:assembleDebug
```

The lightweight launcher delegates to the reviewed Gradle 8.6 wrapper already
stored in `../02_search`; both examples therefore use the same pinned wrapper
binary. The debug APK is created under `app/build/outputs/apk/debug/`.

To install it on a connected device or running emulator:

```bash
./gradlew --no-daemon :app:installDebug
```

Check device visibility with `adb devices`, then open **VModal Full Search**
from the Android launcher.

## 1. Configure the client

1. Paste a current credential into **Runtime API key**.
2. Tap **Configure client**.
3. Confirm that the status asks you to resolve identity.

The app creates the public-gateway client with an in-memory provider:

```kotlin
val provider = MutableApiKeyProvider(apiKey)
val client = Client(
    baseUrl = PUBLIC_GATEWAY_URL,
    apiKeyProvider = provider,
)
```

Configuration itself does not prove that the key is valid. Continue to the
identity request before testing collection, upload, index, or search behavior.

## 2. Resolve identity and collections

Tap **Resolve auth.me**. The application calls:

```kotlin
val me = client.auth.me()
val groups = client.collections.listGroups("vid_file")
```

Do not continue after an authentication error. On success, the screen displays
the profile type and the sorted video collections visible to that key. If the
suggested `android_example` collection is not visible and the account already
has collections, the first returned collection is selected.

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

The suggested new-data scope is:

```text
collection: android_example
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

## 4. Create and inspect the image index

After an upload, or when an existing collection has no ready image index:

1. Tap **Create index**.
2. Note the returned job ID and initial state.
3. Tap **Refresh status** periodically.
4. Continue when the state is `success`, `completed`, `done`, or `ok`.

The index request uses the same Collection and Stream as upload:

```kotlin
val job = client.indexes.createIndex(
    mode = "vid_file",
    groupName = collectionName,
    streamName = streamName,
    indexType = "vid_img_emb",
    modality = "vid_img_emb",
    version = "new_version",
    reProcess = true,
)
```

Index creation is asynchronous. A successful submit response means the job was
accepted, not that the collection is ready. The refresh action calls
`client.indexes.indexStatus(jobId)` and leaves polling cadence under the user's
control, as in the Flutter example.

## 5. Search and inspect results

1. Keep the default query, `red`, for the bundled colored sample or enter a
   description for your video.
2. Tap **Search** after the image index is ready.
3. Inspect up to five cards showing the best title or item ID, source modality,
   timestamp, and normalized score returned by the API.

Before every search, the app refreshes the authenticated key's collections,
checks that the requested collection is visible, and obtains its latest
advertised LanceDB version:

```kotlin
val groups = client.collections.listGroups("vid_file")
val group = groups.findGroup(collectionName, "vid_file")
val version = group?.latestLancedbVersion

val result = client.searches.searchVideo(
    queryText = query,
    groupName = collectionName,
    streamName = streamName,
    searchSources = listOf("image"),
    limit = 5,
    versionLancedb = version,
)
```

Search stops locally when the collection is not returned for the current key
or has no LanceDB version. This avoids silently requesting an unrelated default
index. A zero-result response is displayed as **No matching results**.

## Credential and lifecycle behavior

- The API-key field uses ordinary in-memory Compose state, not saved state.
- **Configure client** clears the visible key field after injection.
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
    ├── FullAppScreen.kt         Staged controls, status, and result cards
    ├── FullAppViewModel.kt      SDK workflow, state, validation, cancellation
    └── AndroidUploadSource.kt   Bundled asset and content-URI adapters
```

`FullAppViewModel` runs blocking SDK requests on `Dispatchers.IO`, bridges the
asynchronous upload callback to a cancellable coroutine, and publishes one
immutable `StateFlow<FullAppUiState>`. The screen collects it with
`collectAsStateWithLifecycle()`.

## Test a locally published SDK artifact

By default, the example compiles the SDK source checkout. To verify a locally
published Maven artifact instead, publish from the SDK root and enable the
project switch:

```bash
cd uinterface/sdk_android
gradle --no-daemon clean build publishToMavenLocal

cd examples/03_fullapp
./gradlew --no-daemon :app:assembleDebug \
  -PvmodalUseMavenLocal=true -PvmodalSdkVersion=1.0.0
```

## Troubleshooting

### Android SDK location not found

Install Android SDK 34 in Android Studio and either open the project there or
set `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the command-line shell. Do not commit
the machine-specific `local.properties` file.

### `Configure the client first`

Enter a runtime key and tap **Configure client** before invoking identity,
collection, upload, index, or search operations.

### Authentication or network error

Confirm that the key is current, belongs to the intended VModal environment,
and that the device can reach the public gateway. Tap **Forget API key** before
switching identities.

### The selected video has no size

Choose a document provider that exposes a readable stream and exact byte size.
Signed uploads require a known content length. The bundled sample always has a
known size.

### Index remains queued or running

Indexing is asynchronous and can take several minutes. Keep the job ID and tap
**Refresh status** again. For production UX, poll with an explicit backoff and
timeout policy or restore the job later through `jobsList()`.

### Search says the collection is unavailable

Tap **Refresh collections** and select a name visible to the current API key.
For a newly uploaded collection, wait until it appears and its image index
advertises a LanceDB version.

## Related documentation

- [Android SDK README](../../README.md)
- [SDK behavior and uploads](../../docs/sdk_doc.md)
- [Runtime API-key management](../../docs/manage_api_key.md)
- [Search application guide](../../docs/search_app.md)
