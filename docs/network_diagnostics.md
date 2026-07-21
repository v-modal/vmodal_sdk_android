# Redacted network diagnostics

Network diagnostics are disabled by default. Enable them only while troubleshooting and route the
already-sanitized events to an application-owned sink:

```kotlin
import com.vmodal.sdk.DiagnosticSink
import com.vmodal.sdk.SdkConfig
import com.vmodal.sdk.SdkDiagnostics

val events = DiagnosticSink { event -> println(event) }
val config = SdkConfig(apiKeyProvider = apiKeys).withDiagnostics(
    SdkDiagnostics.enabled(events)
)
val client = Client(config)
```

`SdkConfig` retains its existing constructor and generated `copy(...)` ABI. Consequently,
`copy(...)` copies the original configuration fields but not diagnostics. Apply
`withDiagnostics(...)` after the final `copy(...)` call.

Enabling diagnostics does not add a network header. Correlation IDs are local identifiers and are
never sent to the gateway, users API, or object-storage host.

## Events and retries

Every actual HTTP attempt emits `DiagnosticEvent.RequestStarted`, followed by exactly one
`DiagnosticEvent.ResponseReceived` or `DiagnosticEvent.RequestFailed`. Safe `GET` and `HEAD`
retries retain one correlation ID and increment their one-based attempt number. Mutation requests
remain single-attempt. Signed-upload calls use their own local correlation IDs; multipart retries
carry their actual one-based part-attempt number.

Elapsed time uses a monotonic clock and is never negative. A sink exception is ignored by the SDK,
so it cannot turn a successful request into a failure, change a typed `SdkError`, trigger a retry,
or interfere with cancellation.

The transport kind is one of `GATEWAY`, `USERS_API`, or `SIGNED_UPLOAD`. Gateway targets use fixed
labels. Signed-upload targets contain only the validated scheme, host, and non-default port; their
path, query, fragment, user information, object key, and signature are never included.

## Safe metadata and previews

Events are sanitized before the consumer sink receives them. They never contain SDK or OkHttp
request/response objects, raw headers, raw URLs, body objects, byte arrays, throwables, exception
messages, or stack traces.

Request metadata is limited to bounded, non-sensitive field-name presence and safe file metadata.
Filenames are basename-only, control-character-free, and bounded. Upload metadata can include the
content type, offset, and byte count already needed by the upload. Diagnostics never open an upload
source, calculate a preview from it, or retain uploaded bytes.

Text response previews require a separate explicit bound:

```kotlin
val diagnostics = SdkDiagnostics.enabled(
    sink = events,
    responsePreviewLimit = 512,
)
```

Zero disables previews. Nonzero limits must be between 64 and
`DIAGNOSTIC_PREVIEW_MAX_CHARS` (4096). Previews are available only for textual media types. Binary,
multipart, and signed-upload responses have no preview. The SDK parses structured JSON when
possible, recursively redacts sensitive fields, scrubs credential-like text and URLs, then truncates
by Unicode code point with a fixed marker. Redaction occurs before truncation.

Sensitive matching is case-insensitive and separator-insensitive for authorization, API key,
cookie, token, credential, password, secret, signature, policy, expiry, session, and provider
signing names. Header names and values are not part of the diagnostic event model.

## Android Logcat

The core artifact stays Kotlin/JVM-only. Bind the small adapter to Android in application code:

```kotlin
import android.util.Log
import com.vmodal.sdk.AndroidLogWriter
import com.vmodal.sdk.AndroidLogcatDiagnostics
import com.vmodal.sdk.SdkDiagnostics

val logcat = AndroidLogcatDiagnostics(
    AndroidLogWriter { priority, tag, message -> Log.println(priority, tag, message) }
)
val diagnostics = SdkDiagnostics.enabled(logcat)
```

Request starts use debug priority, successful responses use info, and failures use warning by
default. Applications can select different `DiagnosticLogLevel` values. Each rendered message is
bounded to at most 4000 Unicode code points, and no throwable or `SdkError.body` is passed to
Android logging.

Diagnostics are intended for local troubleshooting. The consuming application remains responsible
for log access, retention, export, and deletion policies. The SDK does not provide packet capture,
wire tracing, analytics delivery, OpenTelemetry export, or an unredacted mode.
