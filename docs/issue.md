## Summary

A local implementation audit, downstream Android build, Gradle verification run, and read-only live gateway probe confirmed the issues below. Backend-dependent hypotheses such as duplicate index jobs and `reProcess` scope are intentionally excluded until they have mutation-safe reproductions.

## 1. Typed index responses accept missing required fields

`IndexationSubmitResponse` and `IndexationStatusResponse` map missing `job_id` and `status` fields to empty strings:

```kotlin
val jobId = raw["job_id"]?.toString().orEmpty()
val status = raw["status"]?.toString().orEmpty()
```

This allows a successful but malformed service response to appear as a valid typed result. Callers then fail later or implement duplicate blank-field validation.

### Expected

Required typed fields should be validated when the response is constructed. A missing/blank job ID or status should throw a sanitized `MalformedResponse`.

## 2. Upload cancellation does not interrupt all in-flight gateway calls

Cancelling `videoUpload()` or `videoUploadEvents()` cancels the shared `UploadHandle` and active signed-storage calls. However, single-upload presign and finalization use synchronous gateway calls:

```kotlin
http.request(/* presign */)
uploadAwait(/* signed storage call */)
uploadDone(/* gateway finalization */)
```

The synchronous presign/finalization requests are not registered with `UploadHandle`. Cancellation is observed at a later checkpoint or timeout, so an in-flight gateway request may continue after the coroutine/Flow is cancelled.

### Expected

All gateway and signed-storage requests participating in an upload should use cancellable transport calls linked to the same operation cancellation signal.

## 3. Root verification runs no SDK tests

`build.gradle.kts` explicitly disables the standard test task:

```kotlin
tasks.test { enabled = false }
```

The conditional deterministic test registrations also find no corresponding root test sources in the current checkout. Reproduction:

```bash
./gradlew --no-daemon test
```

Observed:

```text
compileTestKotlin NO-SOURCE
compileTestJava NO-SOURCE
test SKIPPED
BUILD SUCCESSFUL
```

This is a confirmed verification gap: a successful root test command does not execute SDK behavior tests.

### Expected

The normal verification entry point should execute the SDK contract suites, or fail clearly when required test sources are absent. At minimum cover configure/scope, response validation, cancellation, upload orchestration, index lifecycle, and search mapping.

## 4. Non-breaking OkHttp callback warning

Compilation reproducibly reports:

```text
The corresponding parameter in the supertype 'Callback' is named 'e'.
This may cause problems when calling this function with named arguments.
```

This does not break compilation, but the implementation parameter should match the supertype to remove ambiguity and keep clean verification output.

## Live verification performed

A read-only probe confirmed:

- authentication succeeds;
- collection listing succeeds with no blank group names;
- search requests complete successfully;
- no index jobs were returned for this credential, so status vocabulary and duplicate-job behavior could not be confirmed.

The probe and temporary credential file were removed after execution. No upload, index creation, deletion, or other backend mutation was performed.

## Acceptance criteria

- Missing required index response fields throw `MalformedResponse`.
- Cancelling upload work interrupts all active gateway and signed-storage calls promptly.
- Root verification executes discoverable SDK tests rather than reporting `test SKIPPED`.
- The OkHttp callback warning is removed.
- Tests cover cancellation during presign, signed upload, multipart status/sign/complete, and finalization.

## Related

Good-to-have scoped workflow and API enhancements remain in #19.
