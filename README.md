<div align="center">

<img src="assets/vmodal-banner.svg" alt="V-Modal Android SDK" width="760"/>

<br/><br/>

**Search video with plain text from your Kotlin Android app.**
No video-processing stack, no vector database — just a client, an API key, and coroutines.

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Coroutines](https://img.shields.io/badge/Coroutines-friendly-5B6CFF)](https://kotlinlang.org/docs/coroutines-overview.html)
[![Website](https://img.shields.io/badge/v--modal.com-visit-2EC5FF)](https://v-modal.com)

[Quick start](#-quick-start) •
[Runtime API keys](#-runtime-api-key-contract) •
[Search](#-search-a-collection) •
[Upload](#-upload-a-video) •
[Examples](examples/01_starter/) •
[Search app](docs/search_app.md) •
[API reference](DOC_REF.md) •
[Troubleshooting](#-troubleshooting)

</div>

---

## ✨ What you can do

```kotlin
val result = sdk.searches.searchVideo(
    queryText = "red car at night",   // describe the moment in plain words
    groupName = "traffic-cameras",    // your collection
    streamName = "astream",
    limit = 20,
)
```

That is the whole idea: upload videos into collections, then find moments in
them with natural language. The SDK also manages collections, uploads large
files with resumable multipart streaming, and plays nicely with
`Dispatchers.IO`, `lifecycleScope`, and WorkManager.

> The SDK is currently added from this repository's source code. It is not yet
> published to a Maven repository.

## 📋 What you need

| Requirement | Details |
|---|---|
| <img src="assets/kotlin-original.svg" width="16"/> Kotlin project | Android project using Gradle Kotlin DSL |
| ☕ Java 17 | `sourceCompatibility` / `jvmTarget = "17"` |
| 📦 This repository | Checked out next to, or inside, your Android project |
| 🔑 API key | Loaded at runtime from your authenticated application backend |

> ⚠️ Never bundle a real API key in source, `BuildConfig`, resources, or
> `AndroidManifest.xml`. The parent application must inject it at runtime.

## 🚀 Quick start

Three steps from zero to your first API response.

### 1️⃣ Add the SDK project

Open your Android project's `settings.gradle.kts` and add:

```kotlin
include(":vmodal-sdk-android")
project(":vmodal-sdk-android").projectDir =
    file("../vmx_api/uinterface/sdk_android")
```

The path passed to `file(...)` is relative to `settings.gradle.kts`. Change it
if your repository is in a different location.

In the same file, make sure `dependencyResolutionManagement.repositories`
contains `mavenCentral()`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

### 2️⃣ Configure your app module

In the app module's `build.gradle.kts`, use Java 17 and add the SDK dependency:

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":vmodal-sdk-android"))
}
```

Sync the Gradle project, then allow network access in
`app/src/main/AndroidManifest.xml` (directly inside `<manifest>`):

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 3️⃣ Connect and print the API status

V-Modal calls perform network I/O. Run them from `Dispatchers.IO`, WorkManager,
or another worker thread — never the Android main thread.

The following function authenticates the API key, creates the ready-to-use
client, and returns the first visible result:

```kotlin
import com.vmodal.sdk.Client
import com.vmodal.sdk.MutableApiKeyProvider
import com.vmodal.sdk.PUBLIC_GATEWAY_URL
import com.vmodal.sdk.SdkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun checkVmodal(apiKeys: MutableApiKeyProvider): Client = withContext(Dispatchers.IO) {
    val firstClient = Client(
        SdkConfig(
            baseUrl = PUBLIC_GATEWAY_URL,
            userId = "",
            mode = "gateway",
            apiKeyProvider = apiKeys,
        )
    )
    val me = firstClient.auth.me()

    val sdk = Client(
        firstClient.cfg.copy(
            userId = requireNotNull(me.userId),
            tenantId = me.tenantId.orEmpty(),
            email = me.email.orEmpty(),
        )
    )

    val health = sdk.health()
    println("VModal connected: ${health.status}")
    sdk
}
```

Call it from an Activity or Fragment lifecycle scope:

```kotlin
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

lifecycleScope.launch {
    val apiKeys = MutableApiKeyProvider(apiKeyLoadedByYourApp)
    val sdk = checkVmodal(apiKeys)
    // Keep or pass sdk to the code that needs V-Modal.
    // Retain apiKeys at application scope, then call apiKeys.rotate(freshKey).
    // On logout/account switch: clear persisted state, then apiKeys.clear().
}
```

Use `viewModelScope.launch { ... }` instead when the client belongs to a
ViewModel. A printed `VModal connected: ...` message means installation,
authentication, and network access are all working. 🎉

### How the connection works

```mermaid
flowchart LR
    A["🔑 Runtime API key"] --> B["MutableApiKeyProvider"]
    B --> C["Client(mode = gateway)"]
    C --> D["auth.me()"]
    D --> E["Client(cfg + userId)"]
    E --> F["health() / search / upload"]
```

## 🔄 Runtime API-key contract

The parent Android application and this SDK have separate responsibilities.
The app owns the credential lifecycle; the SDK only reads the injected current
value while building an authenticated request.

| Parent application owns | SDK owns |
|---|---|
| Authenticate the signed-in app user | `ApiKeyProvider` request-time contract |
| Fetch the API key from the app backend | Atomic swaps and fail-closed clearing in `MutableApiKeyProvider` |
| Choose secure, app-owned persistence | One key snapshot per authenticated request |
| Refresh, version, and serialize rotations | `Authorization: Bearer <key>` on existing authenticated routes |
| Decide whether a failed operation is safe to retry | Filtering auth headers from presigned R2 upload requests |

`ApiKeyProvider.current()` is synchronous. It must return an already-loaded
value and must not perform storage or network I/O. Keep the provider and
`Client` at application scope so Activities, coroutines, and WorkManager jobs
all observe the same rotations.

```mermaid
flowchart LR
    subgraph App["Parent Android application"]
        A["App-owned secure storage"] --> B["Load cached key on worker thread"]
        C["Authenticated app backend"] --> D["Fetch latest key"]
        B --> E["MutableApiKeyProvider"]
        D -->|"persist, then rotate(newKey)"| E
    end
    subgraph SDK["uinterface/sdk_android"]
        F["SdkConfig(apiKeyProvider)"] --> G["Client"]
        G --> H["Build authenticated request"]
        H --> I["Authorization: Bearer key snapshot"]
    end
    E --> F
```

At application startup:

1. On `Dispatchers.IO`, load the signed-in session and cached API key from
   app-owned storage. If no key exists, complete sign-in and fetch the first key
   before creating an authenticated client.
2. Create one `MutableApiKeyProvider`, inject it through `SdkConfig`, call
   `auth.me()`, and install the resolved `Client` in the app dependency graph.
3. Fetch a newer key in the background. Validate and persist it using the
   app's policy, then call `rotate(newKey)`.

Rotation changes the next request; a request already created keeps its original
header. A blank initial or rotated key raises `ValidationFailed`, and a failed
rotation leaves the last working key active. Rotation is only valid for another
key belonging to the same V-Modal identity. For a different user or tenant,
create a new `Client` and resolve `auth.me()` again.

On logout or account switch, first stop/cancel work that uses the client, clear
the app's persisted credential, and call `apiKeys.clear()` (or `close()`). The
operation is idempotent. Later authenticated requests fail closed with
`AuthError`; a configured provider never falls back to the legacy static token.
Clearing removes the SDK's live reference, but immutable JVM strings cannot be
guaranteed to be zeroized from every old heap copy.

Authenticated API calls require HTTPS except for literal loopback development
hosts, reject cross-origin absolute URLs, and do not follow redirects. Signed
uploads apply the same HTTPS policy, strip API identity headers, and do not
follow redirects.

On `401`, the app may refresh the key and retry one safe, idempotent operation
once. Do not treat `403` as proof of expiration, and do not automatically replay
uploads or mutating `POST` requests. See the framework-free
[rotation example](examples/01_starter/03_rotate_api_key.kt).

The legacy `token = "..."` constructors and `SdkConfig.fromEnv()` remain
supported for JVM tools, CI, and existing integrations. `apiKeyProvider` takes
precedence when both are supplied.

## 📁 List your collections

Once the quick start works, use the returned `sdk` client on the same worker
context:

```kotlin
val groups = sdk.collections.listGroups(mode = "vid_file")
println("Collections: ${groups.total}")
groups.data.forEach(::println)
```

This is a useful second check because it confirms that the authenticated user
can reach their V-Modal data.

## 🔍 Search a collection

Replace `traffic-cameras` with a collection returned by `listGroups()`:

```kotlin
val result = sdk.searches.searchVideo(
    queryText = "red car at night",
    groupName = "traffic-cameras",
    streamName = "astream",
    limit = 20,
)

println("Matches returned: ${result.cntActual}")
result.data.forEach(::println)
```

> 💡 If the call succeeds but returns no matches, first confirm the collection
> name, stream name, and query text. An empty result is different from an API
> error.

## 📤 Upload a video

After authentication and search work, continue with the upload examples. The
Android-safe path is:

```mermaid
flowchart LR
    A["🎬 User picks video<br/>(content:// URI)"] --> B["UploadSource<br/>example 08"]
    B --> C["videoUploadAsync()<br/>example 09"]
    C --> D["UploadHandle<br/>(progress / cancel)"]
```

1. Let the user select a video and obtain a `content://` URI.
2. Convert the URI to an `UploadSource` with
   [example 08](examples/01_starter/08_content_uri_source.kt).
3. Start the upload with
   [example 09](examples/01_starter/09_async_video_upload.kt).
4. Keep the returned `UploadHandle` if the UI needs a Cancel action.

The SDK streams the video instead of loading the whole file into memory. Files
of at least 100 MiB use multipart upload by default.

## 🛠️ Troubleshooting

| Symptom | Fix |
|---|---|
| `VMODAL_API_KEY is required` | `Client.fromEnv()` is intended for JVM tools and CI, where environment variables exist. In an Android app, inject a runtime API-key provider as shown in the quick start. |
| `auth/me returned no user_id` or auth error | Confirm the API key is current and belongs to the environment identified by `PUBLIC_GATEWAY_URL`. Do not invent or hard-code a user ID; `auth.me()` resolves the key owner. |
| `NetworkOnMainThreadException` or frozen UI | Move blocking calls (`auth.me()`, `health()`, `listGroups()`, `searchVideo()`) to `Dispatchers.IO` or WorkManager. `videoUploadAsync()` already runs off the main thread, but its callbacks do too — switch to `Dispatchers.Main` before updating views. |
| Gradle cannot find the SDK project | Check the path in `settings.gradle.kts`. It must point to this exact directory: `uinterface/sdk_android`. |

## ✅ Verify the SDK checkout

These commands test the SDK itself; they are not required each time the Android
app runs:

```bash
cd uinterface/sdk_android
bash install.sh check   # verifies Java and Gradle
bash test.sh all        # offline regression suite + simulated app
```

No emulator or API token required.

## 🗺️ Learn progressively

| Step | Where | What you get |
|---|---|---|
| 1 | This page | Working client, first API response |
| 2 | [Examples](examples/01_starter/) | Copy-paste building blocks, grouped by task |
| 3 | [Upload guide](docs/sdk_doc.md) | Android URI uploads, cancellation, WorkManager, process-death resume |
| 4 | [API quick reference](DOC_REF.md) | Every method and response type |

All typed response objects expose `raw: Map<String, Any?>` for server fields
that do not yet have a typed property. All SDK failures derive from `SdkError`;
applications can handle `AuthError`, `ValidationFailed`, `ApiError`, and
`FeatureDisabled` separately when needed.

---

<div align="center">

<img src="assets/kotlin-original.svg" width="36" alt="Kotlin"/>&nbsp;&nbsp;
<img src="assets/android-original.svg" width="36" alt="Android"/>&nbsp;&nbsp;
<img src="assets/androidstudio-original.svg" width="36" alt="Android Studio"/>&nbsp;&nbsp;
<img src="assets/gradle-original.svg" width="36" alt="Gradle"/>

Built for Kotlin developers, by [**v-modal.com**](https://v-modal.com) 💜

<sub>Logo attributions in [assets/README.md](assets/README.md).</sub>

</div>
