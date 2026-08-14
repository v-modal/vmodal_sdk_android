# User and business index organization

These compile-checked examples show four ways an application can organize
searchable content with the immutable `VModal` project, collection, and stream
facade. The SDK does not require an end-user concept: the application chooses
collection names that match its product model.

Each case has its own source folder:

1. [`01_global_search_index`](01_global_search_index/) — one shared search
   index for all application content.
2. [`02_private_index_per_user`](02_private_index_per_user/) — one isolated
   collection for each application user.
3. [`03_multiple_streams_per_user`](03_multiple_streams_per_user/) — one user
   collection split into camera, favorites, and uploads streams.
4. [`04_product_catalog`](04_product_catalog/) — a business-domain collection
   that is independent of end users.

## Naming contract

`VModal` encodes the public `projectId` and `collectionName` into the backend
collection:

```text
<projectId>__<collectionName>
```

The double underscore is reserved for this encoding. Do not include `__` in
either public value. For example, an application user identifier `123` becomes
the public collection `user_123`, which the `food_app` project encodes as
`food_app__user_123`.

The application owns the mapping from its authenticated user identifier to a
valid collection name. The mapped value must contain only letters, digits, and
underscores and must remain stable for that user.

## Compile all four cases

From `uinterface/sdk_android`:

```bash
examples/02_search/gradlew -p examples/04_user --no-daemon \
  --dependency-verification off compileUserExamples
```

The snippets accept `UploadSource`, so Android applications can supply a file,
byte array, or a reopenable `content://` adapter. Obtain the API key at runtime;
never embed it in application source or resources.
