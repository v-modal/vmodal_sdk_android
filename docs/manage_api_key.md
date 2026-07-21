# Mobile credential lifecycle

Production Android applications must receive only a user-scoped, revocable,
short-lived V-Modal credential from an authenticated application backend. Never
ship a provider master key, token-minting secret, Maven credential, Cloudflare
token, or signing key in an APK, `BuildConfig`, resource, manifest, source file,
or example.

The backend owns authentication and authorization. It must derive the user,
tenant, role, and permissions from the bearer credential. Gateway-mode SDK
requests do not send caller-supplied `X-User-Id`, `X-Tenant-Id`, or
`X-User-Email` values; optional image/body identity overrides are also omitted.

## Application and SDK responsibilities

The application must:

- authenticate the signed-in user before requesting a V-Modal credential;
- fetch a credential whose scope and expiry match that user and tenant;
- store it using the application's reviewed mobile-storage policy;
- refresh it before expiry, revoke it on logout, and clear local state;
- treat `401` as a reason to refresh, without blindly replaying a mutation;
- create a new `Client` after an account or tenant change.

The SDK reads the current value synchronously through `ApiKeyProvider`. It does
not mint credentials, persist them, log them, or call the provider from
`SdkConfig.toString()`.

```kotlin
val keys = MutableApiKeyProvider(shortLivedUserToken)
val sdk = Client(
    SdkConfig(
        baseUrl = PUBLIC_GATEWAY_URL,
        apiKeyProvider = keys,
    )
)

// After an authenticated refresh:
keys.rotate(newShortLivedUserToken)

// On logout or account switch:
keys.clear()
```

`ApiKeyProvider.current()` must return an already-loaded value and must not do
network or disk I/O. A configured provider takes precedence over the legacy
static `token` field and never falls back to it after the provider is cleared.

Rotation with `rotate()` is only for a replacement credential belonging to the
same authenticated identity and tenant. The retained client reads the new value
on its next request. Account or tenant changes require a new session and a new
client; do not rotate one identity's provider into another identity.

## Ordered logout and account-switch cleanup

Use the same order from Compose, classic Views, and WorkManager integrations:

1. Increment an app-owned session generation so callbacks from the old account
   cannot publish new state.
2. Cancel ViewModel/repository jobs and every retained upload handle.
3. Cancel WorkManager work uniquely named or tagged for the old account and
   remove its resumable upload checkpoints.
4. Release persisted `content://` grants that the app should not retain across
   sessions.
5. Call `MutableApiKeyProvider.close()` or `clear()`. The next authenticated
   SDK request then fails with `AuthError` before transport.
6. Drop every retained `Client` and coroutine facade for the old session.
7. Clear selected URIs, collection/version choices, progress, search rows,
   image URLs, and account-scoped error/result state.
8. Acquire a new runtime credential and create the next client only after the
   new application identity is established.

Persist account/scope/URI identifiers for durable work, never the credential or
a signed URL. A worker must compare its persisted account identifier with the
current session before it reopens a URI or creates an SDK request. The
[compile-checked worker](../examples/01_starter/src/main/kotlin/com/vmodal/sdk/examples/VmodalUploadWorker.kt)
shows this boundary.

## Rotation and ambiguous operations

Use overlapping server-side rotation: issue the replacement credential, update
the application, observe the new credential in backend metrics, and then revoke
the old credential. Test expiry, revocation, logout, account switching, and a
stolen expired token before release.

The SDK automatically retries recognized transport failures and retryable
`5xx` responses only for `GET` and `HEAD`. It never automatically replays
`POST`, `PUT`, `PATCH`, or `DELETE`, including POST-based searches and presign
calls. If a mutation loses its response, reconcile its state before retrying.
Signed multipart part recovery is a separate protocol with status/ETag
reconciliation and does not authorize general mutation retries.

## Required production evidence

Before a public production release, attach integration evidence that:

- conflicting identity headers cannot alter authorization or audit identity;
- expiry, revocation, rotation, logout, and tenant isolation work;
- no privileged credential appears in the APK or published artifacts;
- presigned storage URLs are user-, object-, method-, and expiry-bound;
- storage requests receive no SDK bearer or identity headers and do not follow
  redirects.
