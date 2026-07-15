# VModal Android image-search app

The app in `examples/02_search` is a complete Jetpack Compose sample that
searches indexed video content, resolves matching frames to image URLs, and
displays them in an adaptive grid. Contributors use the Android SDK source
project directly; release verification can switch the sample to the locally
published Maven coordinate with `-PvmodalUseMavenLocal=true`.

## Run it

1. Install Android Studio with JDK 17 and Android SDK 34.
2. Build or install the app with the included wrapper. No API key is read by
   Gradle or compiled into the APK:

   ```bash
   cd uinterface/sdk_android/examples/02_search
   ./gradlew :app:installDebug
   ```

3. Run the `app` configuration. For this local debug sample only, paste a test
   key into the masked runtime field, then enter a collection, stream, and text
   query. The key field is deliberately not saveable, and **Forget API key**
   clears the SDK provider.

Do not distribute an app that asks users to paste a VModal key. Production apps
must obtain a user-scoped, revocable credential from their authenticated
backend and inject a `MutableApiKeyProvider`. Never put the credential in
source, `BuildConfig`, resources, `local.properties`, Gradle properties, the
manifest, or a deep link.

## Data flow

1. `SearchViewModel` accepts the debug credential at runtime and runs all
   blocking SDK work on `Dispatchers.IO`.
2. `searchVideo()` retrieves matching OCR, ASR, and visual records.
3. Each hit is converted to the image-coordinate contract used by the Python
   reference SDK: mode, group, stream, filename, and optional 13-digit time.
4. `images.getUrlBulk()` resolves all usable hits in one request.
5. Coil loads the presigned URLs into a responsive Compose grid.
6. The ViewModel clears its API-key provider when credentials are forgotten or
   when the ViewModel is destroyed.

The UI includes keyboard search, validation, loading feedback, API errors,
empty results, result counts, and per-image loading/error placeholders.
