# Framebase Android example

Framebase is a standalone native Kotlin/Jetpack Compose application for
searching moments in a small local street-video archive. It is the Android
implementation of the Framebase product flow; the Flutter application in
`uinterface/sdk_flutter/example/05_framebase` is its visual and behavior
reference. This project does not depend on Flutter at build or runtime.

The app demonstrates runtime authentication, local media ownership, streamed
video upload, asynchronous image indexing, grouped frame search, and Media3
playback of a local source video at a returned timestamp.

## Requirements

- Android Studio with Android SDK 34 installed
- JDK 17
- Gradle 8.6 through the checked-in wrapper
- An API 24+ emulator or device for manual playback, picker, and UI checks
- A VModal API key only when exercising the authenticated workflow

The app uses the same source-SDK/Maven-consumer switch as `examples/03_fullapp`.
By default it includes the SDK project at `uinterface/sdk_android`; Maven mode
is opt-in and never falls back to an unrelated global artifact.

## Demo

<img width="271" height="537" alt="image" src="https://github.com/user-attachments/assets/8a7040b9-02b7-49de-948f-953ea59c1bb6" />

<img width="275" height="533" alt="image" src="https://github.com/user-attachments/assets/bc2cb863-77f5-4aa1-95d6-0879bad6af74" />


## Build and run

From this directory, build without credentials or network API calls:

```bash
./gradlew --no-daemon --dependency-verification off :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Open this directory
in Android Studio, select the `app` configuration, and run it on an API 24+
device or emulator. Check device availability first with:

```bash
adb devices
./gradlew --no-daemon :app:installDebug
```

The isolated Maven path used by release validation is:

```bash
./gradlew --no-daemon --dependency-verification off \
  -PvmodalUseMavenLocal=true \
  -PvmodalMavenRepo=/absolute/path/to/maven-repository \
  -PvmodalSdkVersion=SDK_VERSION \
  :app:testDebugUnitTest :app:assembleDebug
```

`SDK_VERSION` must match the version exported by the SDK build. The app targets
API 34, supports API 24+, and uses Java/Kotlin 17, Compose, Coil 2.6.0, and
Media3 1.3.1.

## User workflow

1. The library opens with the three bundled street videos. Bundled MP4s are
   copied lazily into app-private storage when they are first uploaded or
   played.
2. Use the add action to choose one `video/mp4` through Android's document
   picker. The app rejects unreadable, non-MP4, empty, or over-100 MiB files,
   copies an accepted file into `filesDir/framebase/media/`, reads its duration,
   and never stores the external document URI.
3. Open **Search settings**, enter an API key, and press **Connect**. The
   field is cleared before authentication. The gateway calls `auth.me()` and
   discovers the latest LanceDB version for the fixed collection. The key is
   held only by `MutableApiKeyProvider` in memory.
4. From the library overflow menu choose **Prepare videos for search**. Pending
   clips upload sequentially with progress, then one image index is created.
   Polling is bounded to 120 attempts at four-second intervals. **Cancel**
   stops upload work while retaining completed uploads; **Stop waiting** leaves
   a server job ID for **Resume**. A successful job refreshes the collection
   version before search is enabled.
5. Open **Search your videos**, choose a suggestion or enter a description, and
   submit. Focused mode uses distance `0.85`; **Include looser matches** uses
   `1.5`. Smaller distances are more similar, not confidence percentages.
6. Results preserve backend ranking, retain accepted rows even when image bytes
   are missing, join bulk image responses by validated `input_index`, group by
   source filename, and collapse moments less than six seconds apart. Unknown
   source videos remain visible but report that playback is unavailable locally.
7. A result for a stored clip opens a Media3 bottom sheet at its safe timestamp.
   The sheet supports play/pause, seeking, and switching among retained moments;
   the player is released when the sheet is dismissed.
8. **Sync history** shows newest-first bounded operation events. A different
   authenticated account clears uploaded flags, pending index state, and history
   before saving the new account identity.

## Architecture and data boundaries

```text
Compose screens
    -> FramebaseViewModel (immutable StateFlow, preparation/search generations)
       -> ArchiveStore (AtomicFile archive + app-private MP4 copies)
       -> SdkFramebaseGateway (Client.coroutines(), fixed scope)
          -> auth.me -> listGroups -> videoUploadEvents
          -> createIndex -> indexStatus -> searchVideo
          -> getUrlBulk -> getImageBulkFromUrls
       -> PlaybackSheet (Media3 ExoPlayer, local file only)
```

Every remote call uses `mode=vid_file`, collection `framebase_streets`, and
stream `street_study`. The SDK owns request construction and authentication;
the example does not construct HTTP requests. Search rows, temporary image
locators, image bytes, upload handles, player state, and active jobs are
ephemeral. `filesDir/framebase/archive.json` stores only clips, account ID,
pending job ID, and at most 40 sanitized history events. It never stores an API
key, bearer token, signed locator, search response, image bytes, or external
absolute path for a remote resource.

## Media and provenance

The exact videos, stills, Instrument Sans font, and license notice are copied
from the Flutter reference without transcoding or renaming. Relative asset
paths remain `videos/` and `stills/`; Android packages the font as
`res/font/instrument_sans.ttf`. See [MEDIA_SOURCES.md](MEDIA_SOURCES.md) for
the synchronized Pexels links, authors, edits, and license context.

## Validation

From the SDK repository root, the repository-wide regression command is:

```bash
cd uinterface/sdk_android
./gradlew --no-daemon test
```

From this example directory, the credential-free JVM, debug, and instrumentation
APK checks are:

```bash
./gradlew --no-daemon --dependency-verification off \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

With an unlocked API 24+ emulator or device, also run:

```bash
./gradlew --no-daemon --dependency-verification off :app:connectedDebugAndroidTest
```

The unit and Compose tests use fakes and local data; they do not need an API
key. An authenticated manual run is required to validate real upload,
indexing, search, document-picker, codec, and visual-parity behavior. The
release workflow runs secret detection, the SDK regression gate, and both the
Framebase source-SDK and exported public-source credential-free builds. It does
not run a live Framebase dispatcher because this example has no deterministic
existing live-test environment.

## Troubleshooting

- **Gradle cannot find Java:** install/select JDK 17 and verify `java -version`
  before invoking the wrapper.
- **`adb devices` is empty:** start an unlocked API 24+ emulator or connect an
  authorized device; JVM/debug builds remain runnable without one.
- **Authentication failed:** use a current runtime key with beta access. The
  app deliberately reports a bounded message and never echoes the key or raw
  server response.
- **No index is available:** connect first, then prepare the fixed archive.
  Index submission is asynchronous; use **Resume** after stopping polling.
- **A result has no picture:** malformed or partial bulk-image records retain a
  ranked placeholder by design; search details still show backend and filter
  counts separately.
- **Playback cannot open:** re-import a valid local MP4 or restore the bundled
  copy. Remote result URLs are not playback sources.
- **Maven-mode dependency resolution fails:** use an absolute repository path and
  an SDK version produced by the same candidate SDK build.

Do not put credentials in source, resources, Gradle properties, `local.properties`,
test snapshots, logs, or APK assets.
