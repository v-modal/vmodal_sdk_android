# VModal Android SDK technical introduction

This guide covers installation, client setup, search, uploads, Android
lifecycle integration, network behavior, the supported toolchain, and local
development. For a product overview and a choice of runnable examples, return
to the [Android SDK README](../README.md).

## Kotlin SDK reference

Browse the generated [Kotlin SDK reference](https://v-modal.github.io/vmodal_sdk_android/)
for public classes, constructors, properties, extension functions, and methods.
KDoc beside the Kotlin declarations is the content authority. The published
reference intentionally omits service hosts, endpoint paths, route tables, and
implementation source; route synchronization is checked by a separate
regression tool.

Network diagnostics are disabled by default. For opt-in, SDK-sanitized
request-start, response, failure, retry, timing, and signed-upload
events—including a small Android Logcat binding—see the
[redacted network diagnostics guide](network_diagnostics.md). There is no
unredacted mode, and uploaded bytes, raw URLs, credentials, headers, bodies,
and exception messages never reach the sink.

## Start in minutes

For new content flows, the preferred API binds every upload, search, asset,
index, and deletion call to one immutable project/collection/stream scope:

```kotlin
import com.vmodal.sdk.VModal

val content = VModal.configure(
    projectId = "food_app",
    apiKey = apiKeyLoadedByYourApp,
).scope(
    collectionName = "user_123",
    streamName = "uploads",
)

val results = content.search("the cyclist crossing the bridge at sunset")
```

Use the lower-level `Client` API for authentication, administration, images,
R2, and advanced operations not yet represented by the scoped facade.

### 1. Add the SDK

The release coordinate is:

```kotlin
dependencies {
    implementation("com.vmodal:vmodal-sdk-android:2.0.0")
}
```

Keep `mavenCentral()` in `dependencyResolutionManagement`. Maven Central
publication is still pending, so current adopters should clone the
[public SDK repository](https://github.com/v-modal/vmodal_sdk_android) beside
their app and include the source project:

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

Load the key through your app's authenticated backend or secure, app-owned
storage. Never bundle a real key in `BuildConfig`, resources, the manifest, or
source control.

```kotlin
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.SdkConfig

val keys = MutableApiKeyProvider(apiKeyLoadedByYourApp)

val bootstrap = Client(SdkConfig(apiKeyProvider = keys))
val me = bootstrap.coroutines().auth.me()

val vmodal = Client(
    bootstrap.cfg.copy(
        userId = requireNotNull(me.userId),
        tenantId = me.tenantId.orEmpty(),
        email = me.email.orEmpty(),
    )
)
```

Keep `keys` and `vmodal` at application or authenticated-session scope so
Activities, ViewModels, and workers share the same identity and key rotations.

## Search video from a ViewModel

The coroutine facade is preferred for new Kotlin code. The ViewModel owns the
scope; the SDK owns no lifecycle or UI dispatcher:

```kotlin
viewModelScope.launch {
    val api = vmodal.coroutines()
    val groups = api.collections.listGroups("vid_file")
    val group = groups.findGroup("travel-diaries", "vid_file")
        ?: error("Collection is not available for this API key")
    val version = group.latestLancedbVersion
        ?: error("Collection has no searchable LanceDB version")

    val results = api.searches.searchVideo(
        queryText = "the cyclist crossing the bridge at sunset",
        groupName = group.groupName,
        streamName = "astream",
        limit = 20,
        versionLancedb = version,
    )

    println("${results.cntActual} moments found")
    results.data.forEach(::println)
}
```

Search collection names are scoped to the authenticated runtime key. Use a
`vid_file` `GroupItem` returned by `listGroups()`, and send its
`latestLancedbVersion`; omitting the advertised version can target the wrong
index or an unavailable default.

The response stays typed where the contract is stable and preserves
`raw: Map<String, Any?>` so new server fields remain available immediately.

## Upload from an Android picker

Convert the selected `content://` URI into a reopenable `UploadSource` using the
[`ContentResolver` adapter](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/ContentUriUploadSource.kt),
then collect one signed upload:

```kotlin
import com.vmodal.sdk.VideoUploadEvent

val source = contentUriSource(
    context = applicationContext,
    uri = selectedVideoUri,
    fileName = "weekend-ride.mp4",
)

vmodal.coroutines().collections.videoUploadEvents(
    source = source,
    collectionName = "travel-diaries",
    subCollectionName = "mobile-uploads",
).collect { event ->
    when (event) {
        is VideoUploadEvent.Progress -> println("Uploading ${event.progress.percent}%")
        is VideoUploadEvent.Completed -> println("Ready: ${event.response.destPath}")
    }
}
```

The Flow is cold: every collection starts a new upload. Collect once in a
caller-owned scope. If multiple consumers need one operation, share app state
with `stateIn`, `shareIn`, or a repository `StateFlow`. Cancelling collection
cancels the upload. The SDK streams the video instead of loading it into memory.

Existing integrations may keep `videoUploadAsync()` and its `UploadHandle`, or
the blocking `videoUpload()` on a worker thread, while migrating one operation
at a time.

Signed single upload is the production default for every file size. Multipart
upload is experimental and must be enabled explicitly with
`VideoUploadOptions(multipart = true)`; it fails with `FeatureDisabled` when the
gateway does not expose the complete multipart route family.

## Made for Android lifecycles

- Use `Client.coroutines()` from `viewModelScope` or `lifecycleScope` for new
  Kotlin search and collection operations.
- Collect UI state with lifecycle awareness (`collectAsStateWithLifecycle` or
  `repeatOnLifecycle`).
- Feed picker results through `ContentResolver` without copying the whole file
  into memory.
- Collect `videoUploadEvents()` once for UI-driven uploads; collector
  cancellation reaches the upload handle.
- Use `CoroutineWorker` for durable uploads. Never retry cancellation, and
  bound retries to transient failures.
- Keep callback `videoUploadAsync()` and blocking `videoUpload()` for existing
  consumers during migration.
- Keep the SDK UI-free: Jetpack Compose and classic Views are both first-class
  consumers.
- Rotate a same-user credential without rebuilding the client:
  `keys.rotate(freshKey)`.
- On logout or account switch, cancel work, clear upload persistence, call
  `keys.clear()`, and build a new client for the next identity.

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

All SDK failures derive from `SdkError`. Apps can handle `AuthError`,
`ValidationFailed`, `ApiError`, `FeatureDisabled`, `TransportError`,
`ResponseTooLarge`, and `MalformedResponse` separately.

## Security and network behavior

Gateway mode is the default. It sends caller identity only through
`Authorization: Bearer <key>` and ignores caller-supplied identity headers.
`Client.unsafeDirect(...)` is reserved for trusted private networks whose
downstream service independently authenticates identity.

- `GET` and `HEAD` may retry recognized transient failures; mutations are sent
  once.
- Authenticated calls require HTTPS, except literal loopback hosts used for
  development.
- Redirects are not followed.
- JSON/text responses are bounded to 8 MiB, errors to 1 MiB, and binary
  responses to 64 MiB.
- Server error bodies keep their useful structure, but filesystem paths are
  replaced with `****` before an `SdkError` reaches application code.
- Presigned uploads never receive the VModal bearer credential or identity
  headers.

Releases use a **minimal release security** profile with one blocking security
job: candidate-tree verified-secret detection. Normal SDK tests, route sync,
authenticated live tests, clean consumers, version/license checks, and tested-
artifact checksums remain blocking correctness gates. OSV/SBOM generation,
full-history scanning, strict dependency-verification metadata, wrapper-JAR
shell hashing, and compiled route-string scans are preserved but inactive. The
profile therefore does not claim a complete dependency or supply-chain audit;
see the [Maven Central release guide](maven_release.md) for residual risks.

For the complete contract, read [SDK behavior and uploads](sdk_doc.md) and
[runtime API-key management](manage_api_key.md).

## Android toolchain

| Component | Reference configuration |
|---|---:|
| Kotlin | `1.9.24` |
| Java / JVM target | `17` |
| Gradle | `8.6` |
| Android Gradle Plugin | `8.4.2` |
| Reference app `minSdk` | API 24 / Android 7.0 |
| Reference app `compileSdk` | API 34 |

The core artifact deliberately avoids Android framework dependencies, which
keeps it JVM-testable. The included Android reference app demonstrates Compose,
`content://` uploads, lifecycle scopes, and source-project consumption.

Gradle 8.6 is the supported build version and is pinned by the checked-in root
wrapper. Use `./gradlew` for root builds and Android Studio imports; an installed
system Gradle, including Gradle 9, is not part of the supported toolchain.

## Development

```bash
git clone https://github.com/v-modal/vmodal_sdk_android.git
cd vmodal_sdk_android
./gradlew --no-daemon help
bash install.sh check
bash test.sh ci
bash test.sh all
```

Build the included Android app against the source checkout:

```bash
cd examples/02_search
./gradlew --no-daemon :app:assembleDebug
```

`bash test.sh ci` reproduces the read-only pull-request gates with an isolated,
checksummed Maven artifact, a clean standalone consumer, and both demo builds.
No emulator or API credential is required. Maintainers can follow the
[Maven Central release guide](maven_release.md).
