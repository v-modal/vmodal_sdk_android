# Android SDK response validation, upload cancellation, and verification plan

Date: 2026-08-15
Version: 1.0

## User Specs

- Implement every confirmed issue in `docs/issue.md`.
- Validate required index response fields at typed-model construction time.
- Cancel every active upload request, including gateway presign, multipart lifecycle, and finalization calls.
- Make the root Gradle verification command execute the SDK contract suites and fail when a required suite is absent.
- Remove the OkHttp callback parameter warning.
- Run all Android SDK tests.

------------------------------------------------------------------------
## Scope

- `uinterface/sdk_android/src/main/kotlin/com/vmodal/sdk`
- `uinterface/sdk_android/src/test/kotlin/com/vmodal/sdk`
- `uinterface/sdk_android/build.gradle.kts`
- `uinterface/sdk_android/docs/issue_plan.md`

## Critical invariant

- Gateway response maps remain the source of truth for typed response models; typed models must reject missing or blank required fields immediately.
- One `UploadHandle` remains the operation-wide cancellation signal. Every gateway and signed-storage request in that upload must register its active transport handle with it and unregister on completion.
- Existing request encoding, authentication headers, retry rules, diagnostics, response bounds, and typed SDK errors remain shared through `VmodalHttp`; upload cancellation must not create a second HTTP contract.
- `src/test/kotlin/com/vmodal/sdk` remains the source of truth for deterministic SDK suites. The Gradle `test` entry point must run every declared suite and fail clearly if the complete test tree or a declared source file is absent. The production-only public source export intentionally omits the test tree, adds the `.vmodal-public-source` marker, and must remain buildable.
- Public API compatibility remains additive; implementation-only cancellation helpers must not change the published API surface.

## Data Contract

- `IndexationSubmitResponse.jobId`: required, trimmed, non-blank string from `job_id`.
- `IndexationSubmitResponse.status`: required, trimmed, non-blank string from `status`.
- `IndexationStatusResponse.jobId`: required, trimmed, non-blank string from `job_id`.
- `IndexationStatusResponse.status`: required, trimmed, non-blank string from `status`.
- Missing or blank required response values throw `MalformedResponse` with a sanitized field-only message.
- Upload cancellation cancels registered gateway and signed-storage transport handles promptly, prevents later upload phases, and leaves no active handle registered.
- Legacy injected transports that do not implement `CancellableVmodalTransport` retain their documented blocking fallback; built-in coroutine uploads and cancellable injected transports interrupt the underlying call.
- In the internal source tree, `./gradlew --no-daemon test` executes all declared deterministic suite main classes; a missing test tree or suite source fails during Gradle configuration. Only the production public export marked by `.vmodal-public-source` configures without internal test sources.

## List of potential failure issues

- A transport callback may complete before its cancellation handle is registered.
- Cancellation may race with a successful callback or occur during retry backoff.
- A canceled multipart worker may otherwise continue to status, complete, or finalization.
- Multipart reconciliation and refreshed signing calls may bypass the shared handle if helper signatures are not propagated completely.
- Enabling the Gradle `test` task may expose task dependency cycles or make compatibility tests run before generated artifacts exist.
- Test doubles that implement only blocking `VmodalTransport` cannot prove underlying call interruption and must not be mistaken for cancellable transports.

------------------------------------------------------------------------
## List of files to be changed

- `docs/issue_plan.md`: implementation plan, invariants, and verification matrix.
- `build.gradle.kts`: require deterministic suite sources, register all suite runners, and make `test` execute them.
- `src/main/kotlin/com/vmodal/sdk/Models.kt`: enforce required typed index response fields.
- `src/main/kotlin/com/vmodal/sdk/Upload.kt`: let the shared upload handle track gateway cancellation handles as well as OkHttp signed-upload calls.
- `src/main/kotlin/com/vmodal/sdk/CoroutineTransport.kt`: add a blocking orchestration bridge that links cancellable transport execution to an `UploadHandle`.
- `src/main/kotlin/com/vmodal/sdk/Http.kt`: route upload gateway requests through the existing request policy with the operation handle.
- `src/main/kotlin/com/vmodal/sdk/CollectionUploads.kt`: pass the operation handle through every single and multipart gateway phase.
- `src/main/kotlin/com/vmodal/sdk/OkHttpTransport.kt`: align the callback parameter name with OkHttp's `Callback` contract.
- `src/test/kotlin/com/vmodal/sdk/VmodalSdkRegressionTest.kt`: cover malformed required index fields and cancellation at each upload phase.
- `src/test/kotlin/com/vmodal/sdk/TransportIntegrationTest.kt`: assert root verification wiring and required-suite behavior.

## Implementation steps

### P0 — Reject malformed typed index responses

- Files to edit: `Models.kt`, `VmodalSdkRegressionTest.kt`.
- Existing tests touched: index lifecycle/model coverage in `VmodalSdkRegressionTest.kt`.
- New tests: missing, blank, and whitespace-only `job_id`/`status` for submit and status models; successful trimmed values.
- Implementation:
  1. Add a small response-only required-string decoder.
  2. Throw `MalformedResponse` without echoing response content.
  3. Use it for both required properties in both index response types.
- How to test: deterministic regression suite and root Gradle `test`.

### P0 — Link all upload requests to one cancellation signal

- Files to edit: `Upload.kt`, `CoroutineTransport.kt`, `Http.kt`, `CollectionUploads.kt`, `VmodalSdkRegressionTest.kt`.
- Existing tests touched: asynchronous upload, multipart retry/resume, signed cancellation, and coroutine Flow cancellation suites.
- New tests: cancellation during single presign, signed upload, multipart status, multipart sign, multipart complete, and upload finalization.
- Implementation:
  1. Extend `UploadHandle` internally to track `VmodalCancelHandle` instances.
  2. Add a transport bridge that registers a cancellable request before waiting, handles callback-registration races, polls the operation signal, and always unregisters.
  3. Add an internal `VmodalHttp` upload request path that reuses request preparation, retry, diagnostics, response decoding, and typed errors.
  4. Pass `UploadHandle` through presign, multipart create/status/sign/reconcile/complete/abort, URL refresh, and finalization helpers.
  5. Preserve the documented fallback for injected non-cancellable transports.
- How to test: fake cancellable gateway requests plus deterministic local signed-upload integration tests.

### P1 — Restore mandatory root verification

- Files to edit: `build.gradle.kts`, `TransportIntegrationTest.kt`.
- Existing tests touched: all deterministic suite runners.
- New tests: static contract assertions that root `test` is enabled, depends on all suite tasks, and required sources are not silently skipped.
- Implementation:
  1. Validate every declared deterministic source exists during Gradle configuration.
  2. Register every JavaExec suite unconditionally.
  3. Make `test` depend on the complete suite task list.
  4. Remove the explicit `test` disablement.
- How to test: `./gradlew --no-daemon test` and the SDK test wrapper.

### P2 — Remove OkHttp callback warning

- Files to edit: `OkHttpTransport.kt`.
- Existing tests touched: compilation and transport integration suite.
- Implementation: rename the `onFailure` exception parameter to OkHttp's declared `e` name and keep behavior unchanged.
- How to test: clean Kotlin compilation with no callback-name warning.

------------------------------------------------------------------------
## Test Critical List

- `src/test/kotlin/com/vmodal/sdk/VmodalSdkRegressionTest.kt`: configuration, response models, upload orchestration, multipart lifecycle, and phase-specific cancellation.
- `src/test/kotlin/com/vmodal/sdk/CoroutineApiRegressionTest.kt`: coroutine request and Flow cancellation behavior.
- `src/test/kotlin/com/vmodal/sdk/TransportIntegrationTest.kt`: real local HTTP transport, signed upload cancellation, and verification wiring.
- `src/test/kotlin/com/vmodal/sdk/CompatibilityBaselineTest.kt`: frozen API and published artifact compatibility.
- `src/test/kotlin/com/vmodal/sdk/P2HttpRegressionTest.kt`: HTTP contract regression.
- `src/test/kotlin/com/vmodal/sdk/VModalFacadeTest.kt`: public facade contract.
- `src/test/kotlin/com/vmodal/sdk/DiagnosticsRegressionTest.kt`: request and upload diagnostics.

## Test for Data Contract List

- `src/test/kotlin/com/vmodal/sdk/VmodalSdkRegressionTest.kt`: required index response fields and upload phase termination.
- `src/test/kotlin/com/vmodal/sdk/CoroutineApiRegressionTest.kt`: typed index mapping and cancellation propagation.
- `src/test/kotlin/com/vmodal/sdk/TransportIntegrationTest.kt`: cancellable underlying HTTP calls and mandatory root suite wiring.

------------------------------------------------------------------------
## Implementation Order

1. P0 response validation and its deterministic assertions.
2. P0 upload-handle transport bridge and complete helper propagation.
3. P1 mandatory Gradle suite registration and root test aggregation.
4. P2 warning cleanup.
5. Run targeted suites, root `test`, then the full SDK test wrapper and fix every regression.

## Acceptance checklist

- Missing/blank required index response fields throw sanitized `MalformedResponse`.
- Cancellation interrupts presign, signed upload, multipart status/sign/complete, and finalization calls.
- No canceled upload advances to a later gateway phase or emits a terminal success callback.
- Root Gradle `test` executes every deterministic SDK suite and cannot silently omit a missing suite.
- Kotlin compilation contains no OkHttp callback parameter-name warning.
- All Android SDK test commands pass.
