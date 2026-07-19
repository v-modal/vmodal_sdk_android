# VModal Android upload, index, and search example

This is a complete Jetpack Compose app that uses the VModal Android SDK to
pick a video, upload it, wait for its index job, search the same collection and
stream, and display matching video frames. It is an end-to-end evaluation app,
not a form that assumes an already indexed collection.

The sample targets Android 7.0 (API 24) and later. It uses Kotlin, Material 3,
coroutines, `StateFlow`, lifecycle-aware Compose state collection, and Coil.

## Evaluation workflow

The screen keeps one collection and stream pair for every SDK operation:

| Field | Purpose | Default |
|---|---|---|
| Runtime API key | Authenticates SDK requests through the public gateway | Empty |
| Search text | Natural-language description of the video frames to find | Empty |
| Collection | VModal group used for upload, indexing, and search | `agroup` |
| Stream | Stream used for upload, indexing, and search | `astream` |

Use the numbered actions in order:

1. **Choose video** opens Android's Storage Access Framework picker through
   `ActivityResultContracts.OpenDocument`. The app streams its `content://`
   URI; no filesystem path or storage permission is required.
2. **Upload** sends the video as `vid_file` / `vid_raw` and displays progress
   plus the stored filename.
3. **Create index** submits a `vid_img_emb` index for that collection and
   stream. The app polls every five seconds and shows the job ID and state until
   success, failure, or the 30-minute example timeout.
4. **Search** refreshes the authenticated key's `vid_file` collections, rejects
   a collection that is not visible to that key, and sends the collection's
   latest advertised LanceDB version with a limit of 50 and the sources `ocr`,
   `asr`, and `image`. You can also search an existing indexed collection
   directly without selecting or uploading another video.

Usable hits are converted to image coordinates and resolved in one bulk
request. The UI then shows:

- the number of image-backed results displayed;
- the total number of search matches;
- backend search time in milliseconds;
- a responsive image grid with captions, filenames, streams, timestamps, and
  scores when the backend returns those fields;
- loading, validation, API-error, empty-result, and image-error states.

The app supports the keyboard Search action and follows the device light or
dark theme.

## Prerequisites

- Android Studio with Android SDK 34
- JDK 17
- An Android device or emulator running API 24 or newer
- A runtime VModal API key
- A small video readable through Android's system document picker

The included Gradle wrapper uses Gradle 8.6. No separate Gradle installation is
required.

## Run from Android Studio

1. Open `uinterface/sdk_android/examples/02_search` as the project.
2. Allow Gradle to sync and install any requested Android SDK 34 components.
3. Select the `app` run configuration and an API 24+ device or emulator.
4. Run the app.
5. Enter the runtime API key and choose a unique collection and stream.
6. Choose a video, tap **1. Upload**, and wait for upload completion.
7. Tap **2. Create index** and wait for a successful state such as `completed`
   or `success`.
8. Enter a query and tap **3. Search**. Result cards appear below the workflow.

By default, `settings.gradle.kts` includes the SDK source project from `../..`,
so SDK edits are immediately available to the example after a Gradle rebuild.

## Build or install from the command line

From this directory:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Dependency verification is lenient by default so unavailable signing keys or
new transitive metadata are reported without blocking Android Studio sync or a
local build. Use `--dependency-verification strict` only when intentionally
auditing and updating `gradle/verification-metadata.xml`.

The debug APK is generated under `app/build/outputs/apk/debug/`.

To install it on a connected device or running emulator:

```bash
./gradlew --no-daemon :app:installDebug
```

Check the available devices with `adb devices`, then launch **VModal Upload &
Search** from the Android launcher.

## Test the locally published SDK artifact

The default build consumes the SDK source checkout. To verify the same app
against a locally published Maven artifact, first publish from the SDK root:

```bash
cd uinterface/sdk_android
./gradlew --no-daemon clean build publishToMavenLocal
```

Then build the example with the Maven-local switch and matching version:

```bash
cd examples/02_search
./gradlew --no-daemon :app:assembleDebug \
  -PvmodalUseMavenLocal=true -PvmodalSdkVersion=1.0.0
```

When `vmodalUseMavenLocal` is enabled, the app resolves
`com.vmodal:vmodal-sdk-android:<version>` from `mavenLocal()` instead of
including the SDK source project.

## Request and data flow

```text
Android OpenDocument picker
    -> ContentResolver-backed UploadSource
    -> auth.me() resolves user and tenant identity
    -> collections.videoUploadAsync(...)
    -> indexes.createIndex(...)
    -> indexes.indexStatus(jobId) polling
    -> collections.listGroups(vid_file)
    -> GroupsResponse.findGroup(...) and latestLancedbVersion
    -> searches.searchVideo(..., versionLancedb=...)
    -> search hits mapped to image-coordinate records
    -> images.getUrlBulk(...)
    -> presigned URLs stored in SearchUiState
    -> Coil renders the adaptive image grid
```

The first SDK operation creates an authenticated client for the public gateway
and calls `auth.me()`. The returned `userId`, tenant ID, and email are copied
into a second client used for upload, index, search, and image requests. Later
operations reuse it while rotating the value held by `MutableApiKeyProvider`.

`AndroidUploadSource.kt` reads the document's display name, byte size, and MIME
type without discovering a device filesystem path. Its `UploadSource` reopens
the URI through `ContentResolver` when the SDK streams it. Providers that do
not report a byte size cannot be used because signed uploads require a known
content length.

`SearchViewModel` validates input, verifies the search collection against the
authenticated key's group list, bridges the cancellable async upload API into
its coroutine, and runs blocking authentication, index, search, and image calls
on `Dispatchers.IO`. Upload progress and index polling update one immutable
`StateFlow<SearchUiState>`, which the screen collects with
`collectAsStateWithLifecycle()`.

For every usable hit, `SearchRepository` builds the image lookup contract:

```text
mode                = vid_file
group_name          = collection entered in the form
modality            = image
stream_name         = stream from the hit, or the form value as fallback
filename            = normalized basename from the hit
ts_unix_13digits    = normalized timestamp when available
```

Hits without a filename cannot be resolved and are omitted. As a result, the
number of displayed images can be smaller than the total search match count.
The bulk response's `input_index` maps each returned URL back to its search hit.

## Credential handling

This app asks for a key only because it is a local integration example. The key
field is masked and uses ordinary in-memory Compose state rather than
`rememberSaveable`, so it is not restored with the other form fields. The
**Forget API key** action cancels ViewModel-owned work, clears the selected URI
and SDK key provider, drops the clients, and resets the workflow. The selected
URI is session-only and must be chosen again after process recreation.

Do not copy the debug credential flow into a distributed application. A
production app should obtain a user-scoped, revocable credential from its
authenticated backend and inject it through `MutableApiKeyProvider`. Never put
an API key in source code, `BuildConfig`, Android resources, Gradle properties,
`local.properties`, the manifest, or a deep link.

## Source layout

```text
app/src/main/
├── AndroidManifest.xml       INTERNET permission and launcher activity
└── kotlin/com/vmodal/sdk/examples/search/
    ├── MainActivity.kt       Compose host and light/dark Material theme
    ├── AndroidUploadSource.kt ContentResolver-to-UploadSource adapter
    ├── SearchScreen.kt       Picker, workflow controls, and result grid
    └── SearchViewModel.kt    Upload/index/search calls, polling, and state
```

The picker uses AndroidX Activity's maintained activity-result contract, which
is already supplied by `activity-compose`; no third-party picker dependency is
needed. The manifest requests only `INTERNET` and disables app backup.

## Troubleshooting

### Gradle cannot find the SDK project

Open or build from this `02_search` directory without moving it away from the
SDK checkout. Its settings expect the SDK root at `../..`. If testing the Maven
artifact instead, publish it locally and pass both Gradle properties shown
above.

### The app reports an authentication error

Confirm that the runtime key is current and that the device can reach the
public VModal gateway. Tap **Forget API key**, enter the updated key, and search
again.

### No image-backed matches were found

Confirm that the index reached a successful state, the collection appears in
the authenticated key's group list with a LanceDB version, and search still
uses the same collection and stream as upload. A search can have text matches
but show no cards when hits lack the filename data needed for image URL
resolution.

### The selected video cannot be read

Choose a local or cloud provider that exposes both a readable stream and the
document size. Signed uploads require a known content length. The example never
asks you to discover or type an app-private path.

### Indexing remains queued or running

Keep the app open while this evaluation example polls. Indexing is asynchronous
and can take several minutes. The screen preserves the job ID and latest state;
failed terminal states become errors, and polling stops after 30 minutes.

### An image card says “Image unavailable”

Presigned URLs are temporary. Run the search again to resolve fresh URLs. Also
confirm that the device has network access and that the source frame still
exists.

### Android Studio uses the wrong Java version

Set the Gradle JDK to JDK 17 in Android Studio. The project compiles Java and
Kotlin to JVM target 17.

## Related documentation

- [Android SDK README](../../README.md)
- [SDK behavior and uploads](../../docs/sdk_doc.md)
- [Runtime API-key management](../../docs/manage_api_key.md)
- [Search app overview](../../docs/search_app.md)
- [Kotlin starter examples](../01_starter/README.md)
