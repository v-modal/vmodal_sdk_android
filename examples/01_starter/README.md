# Android SDK examples

These examples are small Kotlin building blocks you can copy into an Android
app. Start with one visible result, then add only the features your app needs.

Before using them, complete the root [quick start](../../README.md). It explains
how to add the SDK to Gradle, enable network access, and obtain a ready-to-use
`Client`.

## Recommended beginner path

### Stage 1: prove the connection

Use these examples first:

1. [Create a gateway client](01_create_gateway_client.kt) from an API token.
2. [Read identity and health](03_identity_and_health.kt) and print the result.
3. [List collection groups](06_list_groups.kt) so you have a real collection
   name for later examples.

At this point, installation, authentication, and basic data access should all
work.

### Stage 2: search existing data

4. [Run a text search](04_text_search.kt).
5. Add date, metadata, or image filters with the
   [filtered multimodal search](05_filtered_search.kt).

Replace sample collection names, stream names, queries, and IDs with values
from your VModal account.

### Stage 3: upload from Android

6. [Convert an Android content URI](08_content_uri_source.kt) to an
   `UploadSource`.
7. [Upload the video asynchronously](09_async_video_upload.kt).
8. [Cancel an upload](10_cancel_upload.kt) at a lifecycle boundary.

The URI source is reopenable, so the SDK can retry and upload multipart ranges
without loading the complete video into memory.

### Stage 4: make uploads production-ready

9. [Upload from a blocking worker](11_worker_video_upload.kt) when using
   WorkManager.
10. [Resume after process death](12_resumable_upload.kt) with a persistent
    checkpoint store.
11. [Select adaptive multipart settings](13_adaptive_upload.kt) from current
    network and memory conditions.
12. [Upload several videos](14_bulk_video_upload.kt).

Read the [upload guide](../../docs/sdk_doc.md) before changing multipart sizes,
retry counts, concurrency, or resume behavior.

## More task examples

| Task | Example |
|---|---|
| Create a client with an already trusted direct identity | [02 — Direct client](02_create_direct_client.kt) |
| Upload a small file through the legacy form endpoint | [07 — Small file upload](07_small_file_upload.kt) |
| Upload JSONL metadata | [15 — Metadata JSONL](15_metadata_jsonl_upload.kt) |
| Add assets, update metadata, or delete a collection | [17 — Collection mutations](17_collection_mutations.kt) |
| Create, inspect, or delete an index | [18 — Index lifecycle](18_index_lifecycle.kt) |
| Resolve and download images | [19 — Image access](19_image_access.kt) |
| Read admin usage or R2 information | [20 — Admin and R2](20_admin_and_r2.kt) |

## Threading rules

Client resource calls are blocking except `videoUploadAsync()` and
`videoUploadBulkAsync()`. Run blocking calls from `Dispatchers.IO`, WorkManager,
or another worker thread. Async upload callbacks also run off the main thread,
so switch to the main dispatcher before updating Android views.

The snippets intentionally contain no real credential. Obtain the API token
through the application's approved authentication flow and pass it to the
gateway client factory.
