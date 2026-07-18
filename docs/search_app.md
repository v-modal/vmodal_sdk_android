# VModal Android upload, index, and search app

The app in `examples/02_search` is a complete Jetpack Compose sample that
uses Android's document picker to stream a `content://` video, uploads it,
creates and polls an index job, searches the same collection and stream,
resolves matching frames to image URLs, and displays them in an adaptive grid.
Contributors use the Android SDK source project directly; release verification
can switch the sample to the locally published Maven coordinate with
`-PvmodalUseMavenLocal=true`.

## Run it

1. Install Android Studio with JDK 17 and Android SDK 34.
2. Build or install the app with the included wrapper. No API key is read by
   Gradle or compiled into the APK:

   ```bash
   cd uinterface/sdk_android/examples/02_search
   ./gradlew :app:installDebug
   ```

3. Run the `app` configuration. For this local debug sample only, paste a test
   key into the masked runtime field and enter one collection and stream.
4. Choose a video through the system picker, then use **1. Upload**, **2. Create
   index**, and **3. Search** in order. Upload progress, the stored filename,
   index job ID, and current index state remain visible above the result cards.

The key and selected URI are deliberately session-only. **Forget API key**
clears both along with the SDK provider.

Do not distribute an app that asks users to paste a VModal key. Production apps
must obtain a user-scoped, revocable credential from their authenticated
backend and inject a `MutableApiKeyProvider`. Never put the credential in
source, `BuildConfig`, resources, `local.properties`, Gradle properties, the
manifest, or a deep link.

## Data flow

1. `SearchScreen` launches `ActivityResultContracts.OpenDocument` for a video.
2. `AndroidUploadSource` obtains its name and size and builds a reopenable
   `UploadSource` around `ContentResolver`.
3. `SearchViewModel` bridges `videoUploadAsync()` into its cancellable
   coroutine and publishes upload progress through `StateFlow`.
4. `createIndex()` starts `vid_img_emb` indexing for the same collection and
   stream; `indexStatus()` is polled until a terminal state is visible.
5. `listGroups("vid_file")` confirms that the collection belongs to the
   authenticated key and supplies its latest advertised LanceDB version.
6. `searchVideo()` sends that version and retrieves matching OCR, ASR, and
   visual records.
7. Each hit is converted to the image-coordinate contract used by the Python
   reference SDK: mode, group, stream, filename, and optional 13-digit time.
8. `images.getUrlBulk()` resolves all usable hits in one request.
9. Coil loads the URLs into a grid whose cards show available caption,
   filename, stream, timestamp, and score fields.
10. The ViewModel clears its API-key provider when credentials are forgotten or
   when the ViewModel is destroyed.

The picker avoids storage permissions and manual filesystem paths. Its provider
must report a byte size because the signed-upload contract requires a known
content length. The UI also supports direct search of an existing indexed
collection without selecting another video, but the collection must appear in
the authenticated key's group list with a LanceDB version.
