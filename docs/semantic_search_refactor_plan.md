# Android SDK semantic-search-only surface

Date: 2026-07-26
Version: 1.1
Status: implementation plan only

## User specs

- Android SDK users must see only semantic-search concepts.
- Remove the task-supplied backend terms from documentation, comments,
  identifiers, generated reference pages, API dumps, examples, and test names.
- Do not preserve removed public names through aliases or deprecated wrappers.
- Preserve unrelated user edits already present in `README.md`.
- Do not change files outside `uinterface/sdk_android/`.
- Do not modify, redeploy, or require changes to the backend server.
- Preserve the existing server request contract through one private, fixed
  compatibility adapter inside the Android SDK.

## Scope

- Public and internal Kotlin source under `src/`.
- Route contract and generated route source under `tool/` and `src/main/`.
- Markdown, examples, API compatibility files, and generated reference pages.
- Tests and build gates that define or inspect the public SDK surface.

## Critical invariants

1. The operation-facing public API contains only configuration, immutable
   search scope, semantic query options, typed search results, credentials, and
   typed errors.
2. The Android route contract contains only the semantic-search operation.
3. A semantic query never exposes source-selection controls or processing
   categories. The SDK privately supplies the fixed server-required value;
   callers cannot inspect, choose, or override it.
4. A search result may expose a neutral `assetUrl`, but must not expose how that
   URL is created, authorized, refreshed, or stored.
5. Unknown response fields are discarded and cannot escape through a public raw
   map, exception, log, or `toString()`.
6. Generated documentation is rebuilt into an empty directory so removed pages
   cannot survive regeneration.
7. The backend server and its deployed request model remain unchanged.
8. Backend compatibility fields are confined to the private wire adapter and
   narrow wire-contract tests. They must not appear in public symbols, public
   documentation, examples, logs, exceptions, or generated reference pages.

## Public data contract

### Request

`VModalScope.search()` accepts:

- `query`
- `metadata`
- `startDate`
- `endDate`
- `offset`
- `limit`

The public request model contains only:

- `query_text`
- `query_metadata`, when present
- `group_name`
- `stream_name`
- `start_date`, when present
- `end_date`, when present
- `offset`
- `limit`

The private wire adapter sends those fields plus the existing server-required
compatibility field:

- `search_sources`: always `["image"]`

This fixed value is an implementation detail, not an SDK option. Remove
`searchSources` and every source-category default from public models,
overloads, scoped options, examples, documentation, and public API dumps.
There must be no public or internal parameter that lets a caller replace the
fixed compatibility value.

Do not include the fixed value in logs, errors, `toString()`, diagnostics, or
generated documentation. Do not change the server default, schema, route, or
deployment.

### Response

Expose only:

- `SemanticSearchPage.results`
- `SemanticSearchPage.returnedCount`
- `SemanticSearchPage.totalCount`
- `SemanticSearchPage.elapsedMillis`
- `SemanticSearchResult.id`
- `SemanticSearchResult.title`
- `SemanticSearchResult.text`
- `SemanticSearchResult.timestampMillis`
- `SemanticSearchResult.relevance`
- `SemanticSearchResult.assetUrl`, when the service returns a displayable asset

Do not expose raw response maps.

## Files to change

### Runtime source

- `src/main/kotlin/com/vmodal/sdk/VModal.kt`
  - Keep configuration, scope creation, and semantic search.
  - Remove source-selection options and all non-search operations.
- `src/main/kotlin/com/vmodal/sdk/Models.kt`
  - Replace broad search models with `SemanticSearchOptions`,
    `SemanticSearchPage`, and `SemanticSearchResult`.
  - Remove source-category fields and non-search response models.
  - Use `assetUrl` for the optional displayable result link.
- `src/main/kotlin/com/vmodal/sdk/Client.kt`
  - Keep one internal semantic-search client.
  - Add the private compatibility adapter that appends the fixed
    server-required wire field.
  - Remove public resource containers and raw request access.
- `src/main/kotlin/com/vmodal/sdk/Resources.kt`
  - Move the one search request into the internal client, then delete this file.
- `src/main/kotlin/com/vmodal/sdk/CoroutineClient.kt`
  - Move the one suspending search request into `VModalScope`, then delete this
    file.
- `src/main/kotlin/com/vmodal/sdk/Config.kt`
  - Keep service URL, credential provider, timeout, and retry configuration.
  - Normalize the optional result link to `assetUrl` without exposing protocol
    vocabulary.
- `src/main/kotlin/com/vmodal/sdk/Routes.kt`
  - Keep one internal semantic-search route.
- `src/main/kotlin/com/vmodal/sdk/RoutesGenerated.kt`
  - Regenerate from the reduced route contract.
- `src/main/kotlin/com/vmodal/sdk/AdaptiveUpload.kt`
- `src/main/kotlin/com/vmodal/sdk/CollectionUploads.kt`
- `src/main/kotlin/com/vmodal/sdk/CoroutineUploads.kt`
- `src/main/kotlin/com/vmodal/sdk/Upload.kt`
  - Delete these non-search runtime files.

### Route source

- `tool/routes_contract.json`
  - Retain only `searches.search_video`.
- `tool/gen_routes.py`
  - Generate one internal route constant.
- `tool/check_route_sync.py`
  - Fail when the Android contract contains any second operation.

### Tests and API surface

- `src/live/kotlin/com/vmodal/sdk/LiveTest.kt`
  - Keep one semantic-query live test.
  - Assert the private compatibility request returns both populated and empty
    typed pages before deleting existing fields.
- `src/test/kotlin/com/vmodal/sdk/VmodalSdkRegressionTest.kt`
- `src/test/kotlin/com/vmodal/sdk/CoroutineApiRegressionTest.kt`
  - Replace broad resource checks with semantic-search contract checks.
- `src/test/kotlin/com/vmodal/sdk/SemanticSurfaceTest.kt`
  - Add checks for the public-symbol allowlist, blocked terminology, and absence
    of raw response exposure.
  - Restrict terminology scanning to public declarations, comments,
    documentation, examples, API dumps, artifacts, and generated reference
    pages. Permit exact server field/value literals only in the private wire
    adapter and its wire-contract assertions.
- `api/vmodal-sdk-android.api`
  - Regenerate from the semantic-search-only public API.
- `api/compat/`
  - Remove compatibility dumps that retain withdrawn public names.
- `build.gradle.kts`
  - Register the semantic contract and surface checks in the normal test and
    publication gates.

### Documentation and examples

- `README.md`
  - Preserve the current user-authored layout.
  - Describe installation, configuration, scope creation, and one semantic
    query only.
- `DOC_REF.md`
- `docs/introduction.md`
- `docs/coroutines.md`
- `docs/manage_api_key.md`
- `docs/search_app.md`
  - Rewrite retained guidance using only the public data contract above.
- `docs/sdk_doc.md`
- `docs/android_integration_cookbook.md`
- `docs/todo/`
  - Remove these broad or historical documents.
- `examples/01_starter/`
  - Keep one semantic-search example and remove non-search examples.
- `examples/02_search/`
  - Remove source selectors and map results through the typed semantic result.
- `examples/03_fullapp/`
- `examples/04_user/`
  - Remove these broad examples.
- `docs_sdk/`
  - Regenerate from an empty directory after the Kotlin surface is reduced.

## Implementation steps

### P0 — Prove the private compatibility request

1. Add a direct live probe containing the public semantic fields plus the fixed
   private compatibility field required by the existing server.
2. Verify a normal result page.
3. Verify an empty result page against the same valid indexed scope by using a
   date range with no matches. Do not use a nonexistent scope because a missing
   server-side index may correctly return `404`.
4. Assert that the fixed compatibility field is present exactly once and cannot
   be supplied or overridden by the caller.
5. Do not modify the backend server if the probe fails. Diagnose and fix the
   Android-side request adapter while keeping the public API semantic-only.

### P1 — Reduce runtime and routes

1. Add the typed semantic request and response models.
2. Move search execution behind `VModalScope.search()`.
3. Remove source selection from every blocking and suspending signature.
4. Encode the fixed server compatibility value only inside the internal client.
5. Remove all non-search resource families and models.
6. Reduce the route contract to the single semantic-search operation and
   regenerate route source.

### P2 — Replace tests and generated surfaces

1. Test exact request keys, validation, successful decoding, empty results,
   unknown-field removal, authentication errors, malformed responses, and
   coroutine cancellation.
2. Regenerate the API dump.
3. Remove stale compatibility dumps.
4. Regenerate `docs_sdk/` from an empty output directory.

### P3 — Rewrite user material

1. Apply narrow edits to the dirty `README.md`; do not replace its layout.
2. Rewrite retained docs around configuration, scope, query, result, and error
   handling only.
3. Remove historical docs and examples that describe withdrawn operations.
4. Compile the retained starter and Android search examples.

## Test cases

1. A blank query fails before network access.
2. Invalid offset or limit fails before network access.
3. The encoded request contains exactly the public request fields plus the one
   fixed private compatibility field.
4. A caller cannot supply, replace, or add source-selection fields.
5. A successful response returns only typed semantic fields.
6. Unknown top-level and result fields are discarded.
7. An empty result set returns a successful empty page.
8. Invalid credentials return the typed authentication error.
9. Malformed responses return the typed malformed-response error.
10. Cancellation closes the active HTTP call.
11. API dump, sources JAR, binary JAR, Markdown, examples, and generated
    reference expose only the semantic-search surface.
12. A case-insensitive scan finds none of the task-supplied backend terms or
    their hyphenated, underscored, or camel-case identifier variants in public
    declarations, comments, docs, examples, API dumps, artifacts, or generated
    reference pages.
13. A narrow wire-contract check confirms that the private adapter still sends
    the fixed field expected by the unchanged server.

## Verification

Run from `uinterface/sdk_android/` after loading the repository environment:

```bash
source ../../isetup_env.sh
export PYTHONPATH="$(cd ../.. && pwd)"

python tool/gen_routes.py check
python tool/check_route_sync.py check
./gradlew --no-daemon --dependency-verification off clean apiCheck test
./gradlew --no-daemon --dependency-verification off dokkaGeneratePublicationHtml
python docs.py generate
python docs.py check
python docs.py check_links
bash examples/test.sh
bash test.sh test
git diff --check
```

## Acceptance criteria

- Public Kotlin symbols and generated reference expose semantic search only.
- The public request contains no source-selection control.
- The private request adapter supplies one fixed server compatibility value
  that callers cannot inspect or override.
- Search results contain no raw map and use only neutral, typed fields.
- Documentation, comments, identifiers, examples, tests, API dumps, and
  generated pages contain none of the task-supplied backend terms, except exact
  wire literals inside the private adapter and narrow wire-contract assertions.
- All deterministic, example, documentation, API, and live semantic-search
  checks pass.
- The existing unrelated `README.md` edits remain intact.
- No file outside `uinterface/sdk_android/` changes.
- No backend source, configuration, deployment, route, or schema changes are
  required.

## Implementation order

1. P0: prove the private Android compatibility request against the unchanged
   service contract.
2. P1: reduce runtime source and routes.
3. P2: replace tests and generated surfaces.
4. P3: rewrite user documentation and examples.
