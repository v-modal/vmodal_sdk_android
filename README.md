<div align="center">
  <img src="assets/vmodal-logo.svg" alt="VModal" width="88">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/android-original.svg" alt="Android" width="88">
  <h1>VModal for Android</h1>
  <p><strong>Give your Android app a multimodal memory.</strong></p>
  <p>Upload video. Find moments by meaning, speech, text, or imagery.<br>Build the experience in Kotlin, Compose, Views, coroutines, and the Android tools you already know.</p>
  <img src="https://img.shields.io/badge/Android-native-3DDC84?logo=android&logoColor=white" alt="Android native">
  <img src="https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 1.9+">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Gradle-8.6-02303A?logo=gradle&logoColor=white" alt="Gradle 8.6">
  <img src="https://img.shields.io/badge/license-Apache%202.0-6C63FF" alt="Apache License 2.0">
</div>

<br>

<img src="assets/dev_homepage.jpg" alt="A wall of searchable video moments and developer screens" width="100%">

<p align="center"><em>Turn every video library into an experience Android users can explore.</em></p>

## Build the feature people remember

VModal brings multimodal video search and mobile-friendly uploads to Kotlin with a small, typed API. Your app owns the screens and lifecycle; the SDK handles the gateway, request models, response parsing, signed upload streams, progress, and cancellation.

| Your Android experience | VModal gives you |
|---|---|
| “Find the red car entering the parking lot” | Semantic video and image search |
| Search words spoken or shown on screen | ASR and OCR search sources |
| Upload from the system photo picker | Streaming `content://` URI support |
| A cancel action that really cancels | `UploadHandle` with progress and cancellation |
| Compose, Views, or your own design system | A UI-free Kotlin client |
| Existing authentication and DI | App-owned runtime credentials—no login UI imposed |
| Work that survives beyond one screen | Worker-friendly blocking APIs and async uploads |

> [!TIP]
> **Building a mobile video experience?** [Get a free beta API key](https://v-modal.com/page/contact.ts) and join the [VModal Discord](https://discord.gg/CRNsdJHg6). We would love to help you ship it.

## Kotlin SDK reference

Browse the generated [Kotlin SDK reference](docs_sdk/index.html) for public
classes, constructors, properties, extension functions, and methods. KDoc beside
the Kotlin declarations is the content authority. The published reference
intentionally omits service hosts, endpoint paths, route tables, and
implementation source; route synchronization is checked by a separate
regression tool.

## Start in minutes

### 1. Add the SDK

The release coordinate is:

```kotlin
dependencies {
    implementation("com.vmodal:vmodal-sdk-android:1.0.0")
}
```

Keep `mavenCentral()` in `dependencyResolutionManagement`. Maven Central publication is still pending, so current adopters should clone the [public SDK repository](https://github.com/v-modal/vmodal_sdk_android) beside their app and include the source project:

```kotlin
// settings.gradle.kts
include(":vmodal-sdk-android")
project(":vmodal-sdk-android").projectDir = file("../vmodal_sdk_android")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":vmodal-sdk-android"))
}
```

The project uses Java 17. Your app also needs network permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. Connect with your runtime API key

Load the key through your app's authenticated backend or secure, app-owned storage. Never bundle a real key in `BuildConfig`, resources, the manifest, or source control.

```kotlin
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.SdkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val keys = MutableApiKeyProvider(apiKeyLoadedByYourApp)

val vmodal = withContext(Dispatchers.IO) {
    val bootstrap = Client(SdkConfig(apiKeyProvider = keys))
    val me = bootstrap.auth.me()

    Client(
        bootstrap.cfg.copy(
            userId = requireNotNull(me.userId),
            tenantId = me.tenantId.orEmpty(),
            email = me.email.orEmpty(),
        )
    )
}
```

Keep `keys` and `vmodal` at application or authenticated-session scope so Activities, ViewModels, and workers share the same identity and key rotations.

## Search video from a ViewModel

Blocking SDK calls belong on `Dispatchers.IO`. Bring only the result back to your UI state:

```kotlin
viewModelScope.launch {
    val results = withContext(Dispatchers.IO) {
        val groups = vmodal.collections.listGroups("vid_file")
        val group = groups.findGroup("travel-diaries", "vid_file")
            ?: error("Collection is not available for this API key")
        val version = group.latestLancedbVersion
            ?: error("Collection has no searchable LanceDB version")

        vmodal.searches.searchVideo(
            queryText = "the cyclist crossing the bridge at sunset",
            groupName = group.groupName,
            streamName = "astream",
            searchSources = listOf("ocr", "asr", "image"),
            limit = 20,
            versionLancedb = version,
        )
    }

    println("${results.cntActual} moments found")
    results.data.forEach(::println)
}
```

Search collection names are scoped to the authenticated runtime key. Use a
`vid_file` `GroupItem` returned by `listGroups()`, and send its
`latestLancedbVersion`; omitting the advertised version can target the wrong
index or an unavailable default.

The response stays typed where the contract is stable and preserves `raw: Map<String, Any?>` so new server fields remain available immediately.

## Upload from an Android picker

Convert the selected `content://` URI into a reopenable `UploadSource` using the [`ContentResolver` adapter](examples/01_starter/08_content_uri_source.kt), then start an asynchronous signed upload:

```kotlin
val source = contentUriSource(
    context = applicationContext,
    uri = selectedVideoUri,
    fileName = "weekend-ride.mp4",
)

val upload = vmodal.collections.videoUploadAsync(
    source = source,
    collectionName = "travel-diaries",
    subCollectionName = "mobile-uploads",
    onProgress = { value ->
        println("Uploading ${value.percent}%")
    },
    onSuccess = { result ->
        println("Ready: ${result.destPath}")
    },
    onFailure = { error ->
        error.printStackTrace()
    },
)

// Connect this to your Compose or View cancel action when needed:
// upload.cancel()
```

The SDK streams the video instead of loading it into memory. Upload callbacks run off the main thread; switch to `Dispatchers.Main` before changing Views or Compose state.

Signed single upload is the production default for every file size. Multipart upload is experimental and must be enabled explicitly with `VideoUploadOptions(multipart = true)`; it fails with `FeatureDisabled` when the gateway does not expose the complete multipart route family.

## Made for Android lifecycles

- Use `viewModelScope` or `lifecycleScope` for search and collection operations.
- Feed picker results through `ContentResolver` without copying the whole file into memory.
- Use `videoUploadAsync()` for UI-driven uploads and retain its `UploadHandle` for cancellation.
- Use the blocking `videoUpload()` inside WorkManager or another worker thread.
- Keep the SDK UI-free: Jetpack Compose and classic Views are both first-class consumers.
- Rotate a same-user credential without rebuilding the client: `keys.rotate(freshKey)`.
- On logout or account switch, cancel work, clear upload persistence, call `keys.clear()`, and build a new client for the next identity.

## One client, focused resources

```text
vmodal.auth          identity and health
vmodal.searches      multimodal video search
vmodal.collections   upload and collection lifecycle
vmodal.indexes       create, inspect, and delete indexes
vmodal.admin         usage and cache statistics
vmodal.r2            presigned object-storage operations
vmodal.images        image retrieval
```

All SDK failures derive from `SdkError`. Apps can handle `AuthError`, `ValidationFailed`, `ApiError`, `FeatureDisabled`, `TransportError`, `ResponseTooLarge`, and `MalformedResponse` separately.

## Security and network behavior

Gateway mode is the default. It sends caller identity only through `Authorization: Bearer <key>` and ignores caller-supplied identity headers. `Client.unsafeDirect(...)` is reserved for trusted private networks whose downstream service independently authenticates identity.

- `GET` and `HEAD` may retry recognized transient failures; mutations are sent once.
- Authenticated calls require HTTPS, except literal loopback hosts used for development.
- Redirects are not followed.
- JSON/text responses are bounded to 8 MiB, errors to 1 MiB, and binary responses to 64 MiB.
- Presigned uploads never receive the VModal bearer credential or identity headers.

For the complete contract, read [SDK behavior and uploads](docs/sdk_doc.md) and [runtime API-key management](docs/manage_api_key.md).

## Android toolchain

| Component | Reference configuration |
|---|---:|
| Kotlin | `1.9.24` |
| Java / JVM target | `17` |
| Gradle | `8.6` |
| Android Gradle Plugin | `8.4.2` |
| Reference app `minSdk` | API 24 / Android 7.0 |
| Reference app `compileSdk` | API 34 |

The core artifact deliberately avoids Android framework dependencies, which keeps it JVM-testable. The included Android reference app demonstrates Compose, `content://` uploads, lifecycle scopes, and source-project consumption.

Gradle 8.6 is the supported build version and is pinned by the checked-in root
wrapper. Use `./gradlew` for root builds and Android Studio imports; an installed
system Gradle, including Gradle 9, is not part of the supported toolchain.

## Explore the SDK

- [Run the staged full search application](examples/03_fullapp/)
- [Run the Android upload → index → search app](examples/02_search/)
- [Copy the Kotlin starter examples](examples/01_starter/)
- [Read the upload and WorkManager guide](docs/sdk_doc.md)
- [Manage API keys safely](docs/manage_api_key.md)
- [Build the complete upload → index → search experience](docs/search_app.md)
- [Browse the API quick reference](DOC_REF.md)
- [Open an issue](https://github.com/v-modal/vmodal_sdk_android/issues)

## Development

```bash
git clone https://github.com/v-modal/vmodal_sdk_android.git
cd vmodal_sdk_android
./gradlew --no-daemon help
bash install.sh check
bash test.sh all
```

Build the included Android app against the source checkout:

```bash
cd examples/02_search
./gradlew --no-daemon :app:assembleDebug
```

No emulator or API credential is required for the offline SDK gate. Maintainers can follow the [Maven Central release guide](docs/maven_release.md).

---

<div align="center">
  <img src="assets/kotlin-original.svg" width="38" alt="Kotlin">
  &nbsp;&nbsp;
  <img src="assets/android-original.svg" width="38" alt="Android">
  &nbsp;&nbsp;
  <img src="assets/androidstudio-original.svg" width="38" alt="Android Studio">
  &nbsp;&nbsp;
  <img src="assets/gradle-original.svg" width="38" alt="Gradle">
  <p><strong>Build video experiences people can search, not just scroll.</strong></p>
  <sub>Built for Android developers by <a href="https://v-modal.com">VModal</a>. Licensed under the <a href="LICENSE">Apache License 2.0</a>.</sub>
  <br>
  <sub>Android and the Android robot are trademarks of Google LLC. Asset attribution is documented in <a href="assets/README.md">assets/README.md</a>.</sub>
</div>
