# Maven Central release

The Android/Kotlin SDK is published with these coordinates:

```text
com.vmodal:vmodal-sdk-android:<version>
```

Maven Central publication is intentionally separate from ordinary CI and from
the public GitHub source export. The workflow always builds the Maven
publication and compiles the Android example against it. Uploading is enabled
only by the explicit `publish_maven_central` workflow input.

Do not enable either public release input unless the authenticated live job is
green. The live upload coverage intentionally matches the default `sdk_python`
suite: small `videoUpload` and `videoUploadBulk` calls use the supported single
signed-URL flow.

**TODO: production does not expose
`/api/external/v1/collections/external_upload_multipart/*`. Restore forced
multipart live coverage when those routes become available. Until then,
multipart behavior is validated only by the offline regression suite.**

## One-time Maven Central setup

1. Create a Central Portal account and register the `com.vmodal` namespace.
2. Create a Central Portal user token.
3. Create a GPG signing key and distribute its public key.
4. Add these GitHub Actions repository secrets:

   - `MAVEN_CENTRAL_USERNAME`
   - `MAVEN_CENTRAL_PASSWORD`
   - `MAVEN_SIGNING_KEY` — ASCII-armored private key
   - `MAVEN_SIGNING_PASSWORD`

Do not add registry credentials or signing keys to project files.

## Build without uploading

```bash
cd uinterface/sdk_android
gradle --no-daemon clean test publishToMavenLocal
cd examples/02_search
./gradlew --no-daemon :app:assembleDebug \
  -PvmodalUseMavenLocal=true -PvmodalSdkVersion=1.0.0
```

The second command compiles the Android example against the locally published
coordinate rather than against the SDK source project.

## Publish

Run `.github/workflows/sdk_android_test_release.yml` from the versioned commit
with `publish_maven_central=true`. The workflow performs the secret scan,
offline suite, authenticated live suite, Maven consumer build, GPG signing,
Central Portal upload, validation, and automatic release.

Maven Central versions are immutable. Increment both `build.gradle.kts` and
`VMODAL_SDK_VERSION` in `Client.kt` before publishing a later release.
