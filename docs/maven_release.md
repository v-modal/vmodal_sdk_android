# Android SDK release runbook

Published coordinates are:

```text
com.vmodal:vmodal-sdk-android:<version>
```

The SDK remains a production-release NO-GO until the security plan's runtime,
backend, storage, endpoint-ownership, credential-rotation, and artifact gates
have evidence. The workflow is manual and all publishing jobs require approval
from the protected `sdk-android-production` environment.

## Pull-request CI authority boundary

`.github/workflows/sdk_android_ci.yml` is the credential-free pull-request
gate. It has only `contents: read`, disables persisted checkout credentials,
uses immutable action commits, and never enters the production environment or
references live/release secrets. The separate release workflow remains manual
and protected.

The `sdk_core` check runs route and policy checks, the normal SDK test
lifecycle, strict dependency verification, Dokka, checked-in documentation
validation, and publication to a run-specific Maven repository under the
runner temporary directory. Only after that succeeds do `clean_consumer`,
`search_example`, and `fullapp_example` download and checksum the exact
run-ID/source-SHA artifact. These four check names are the branch-protection
handoff; renaming one requires updating branch protection in the same rollout.

No pull-request check requires an API key, release credential, emulator, or
Android device. Fork pull requests run the same offline jobs with read-only
caches. Only a successful push to `dev` may write the Gradle action cache.

## Credential boundaries

Configure separate credentials; never reuse one credential across boundaries:

- `ANDROID_SDK_APP_ID` and `ANDROID_SDK_APP_PRIVATE_KEY`: a GitHub App installed
  only on `v-modal/vmodal_sdk_android`, with `contents: write` only. The workflow
  creates its short-lived token after the exported source passes inspection.
- `GH_PACKAGES_TOKEN`: package publication only, with no source-repository write
  access. Rotate it on the package-token schedule.
- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`: Central Portal token.
- `MAVEN_SIGNING_KEY` and `MAVEN_SIGNING_PASSWORD`: in-memory artifact signing.
- `TEST_CLIENT_CLERK_USER_API_TOKEN`: live-test bearer credential only. This
  compatibility secret name does not describe or select the active auth
  provider.

The variable names are centralized in the sub-repository `env.sh`; it contains
no credential values. Production values remain GitHub environment secrets.

The workflow has `contents: read` by default, uses `persist-credentials: false`
for every checkout, and introduces each release credential only in its final
operation. The source push uses an environment-only Git extra header, never a
token-bearing remote URL or credential helper. Cloudflare protection is never
disabled by this workflow; the live endpoint must have a permanent narrow CI
rule or an authenticated service-token path.

After the hardened dry run succeeds, revoke the previous combined classic PAT
and broad Cloudflare token. Review audit logs before revocation and record the
owner, time, and replacement credential scope in the release evidence.

## Immutable actions and dependency provenance

Every remote `uses:` reference must be a reviewed full 40-character commit SHA
with its human-readable version in a comment. Before changing a SHA:

1. resolve and peel the intended upstream tag;
2. review the action source, install/network behavior, inputs, post-step
   cleanup, and token handling at that commit;
3. update the SHA and review record together;
4. run `bash security_check.sh workflow` and a clean-cache workflow run.

Current immutable inventory (source-review signoff is still required before the
first production release):

| Action | Version | Commit |
|---|---:|---|
| `actions/checkout` | v5 | `93cb6efe18208431cddfb8368fd83d5badbf9bfd` |
| `actions/setup-java` | v4 | `c1e323688fd81a25caa38c78aa6df2d33d3e20d9` |
| `gradle/actions/setup-gradle` | v4 | `ed408507eac070d1f99cc633dbcf757c94c7933a` |
| `actions/upload-artifact` | v4 | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `actions/download-artifact` | v4 | `d3f86a106a0bac45b974a628896c90dbdf5c8093` |
| `actions/create-github-app-token` | v2 | `fee1f7d63c2ff003460e3d139729b119787bc349` |

Secret detection uses the TruffleHog 3.95.9 Linux amd64 release archive rather
than its composite action, because that action defaults to a floating container
tag. The workflow verifies archive SHA-256
`f6d1106b85107d79527ed7a5b98b592beadd8b770dc3c9e8c1ad99e1b2cf127e`
before execution.

The workflow also downloads OSV-Scanner 2.3.8 directly from its official
release, verifies SHA-256
`bc98e15319ed0d515e3f9235287ba53cdc5535d576d24fd573978ecfe9ab92dc`,
scans both strict Gradle verification graphs, generates a CycloneDX 1.5 SBOM,
and archives the scan, SBOM, and checksums for 90 days.

`osv-scanner.toml` records short-lived, owner-named exceptions only for known
build-tool transitive findings that are absent from the SDK runtime artifact.
The current AGP exceptions expire on 2026-08-15; an expired exception makes the
release scan fail and must be removed by upgrading AGP/Gradle, not extended
without a new reviewed risk decision.

The SDK root, standalone consumer, search example, and full-app example retain
reviewed `gradle/verification-metadata.xml` files. CI always passes
`--dependency-verification strict`; it never generates metadata or falls back
to `off` or `lenient`. The root and search example own the reviewed Gradle 8.6
wrapper. The full app delegates to that search wrapper, and the standalone
consumer uses the root wrapper.

The standalone consumer and full-app gates use complete SHA-256 inventories
with signature lookup disabled so fork CI does not depend on external keyserver
availability. This does not relax artifact verification: every resolved
third-party file is checksummed, while the commit-specific SDK candidate is
verified by the downloaded Maven manifest and source-SHA record. Root and
search-example signature policy remains unchanged.

To update metadata after an intentional dependency change, run the narrowest
affected task locally with `--write-verification-metadata sha256,pgp` (or
`sha256` when the repository does not publish signatures), then rerun it in
strict mode. For example:

```bash
./gradlew --write-verification-metadata sha256,pgp help
./gradlew --no-daemon --dependency-verification strict help
```

Run the command from the affected Gradle root. Generation is bootstrap only:
manually review repository origins, plugin markers, transitive artifacts,
signing keys, ignored keys, and every new checksum before committing. The
`com.vmodal:vmodal-sdk-android:1.0.0` trust entry is intentionally coordinate
exact because candidate bytes change between commits; the CI SHA-256 manifest
provides their provenance and is rechecked after download. Never generate
metadata in CI. Wrapper distribution, distribution hash, JAR hash, executable
bits, and full-app delegation remain enforced by `security_check.sh`.

After review, run `./gradlew --no-daemon verifyIdeSources` from the SDK root to
confirm Android Studio can attach runtime dependency sources.

Generate the resolved dependency report before approval:

```bash
./gradlew --no-daemon --dependency-verification off dependencyReport
```

Archive the report, verification metadata checksums, secret-scan result, and
advisory-scan result with the release evidence.

## Local release candidate verification

From `uinterface/sdk_android`:

```bash
bash test.sh ci
bash test.sh all
```

`bash test.sh ci /absolute/empty/maven/repository` preserves the isolated Maven
repository for manual inspection; the directory must be new or empty. The
dispatcher confirms the requested version agrees in `build.gradle.kts`,
`VMODAL_SDK_VERSION`, POM, and repository path; checksums every Maven file;
checks `META-INF/LICENSE` in the binary and source JARs; compiles the standalone
consumer; and tests/builds both demos without live credentials.

## Exact-commit publication

1. Create the release-candidate commit only after all code, docs, metadata, and
   version changes are present. Do not add a version-only commit afterward.
2. Dispatch `.github/workflows/sdk_android_test_release.yml` from that exact
   commit. First run all non-publishing jobs with every publish input false.
3. Record the monorepo SHA, workflow run ID, JDK/Gradle versions, dependency
   report, verification metadata checksums, OSV result, CycloneDX SBOM,
   artifact checksums, and approvals.
4. Re-run/approve the exact SHA with only the intended publication inputs.
   Either package destination requires `publish_sdk_android=true`; selecting
   only that source input is the explicit source-only mode. The
   public source tag is `v<version>` and its annotated message records the
   monorepo SHA. If the canonical tag already exists for a source-only refresh,
   the workflow creates `v<version>_run<workflow-run-id>` without moving the
   existing tag. An unchanged export still fails closed. The workflow verifies
   the remote `main` and annotated tag targets before reporting success.
   To refresh only the generated Kotlin SDK reference on Pages, set
   `publish_sdk_docs_only=true`; this skips SDK tests, live tests, source export,
   and Maven publication while retaining secret detection, Dokka generation,
   endpoint-free validation, and immutable artifact checks.
5. Download the published coordinate in a clean consumer with no local Maven
   cache. Verify signature, checksum, POM, version, license, and compilation.
6. Confirm the public tag/export mapping and inspect audit logs. Revoke any
   superseded credential and check that no Git remote retained authentication.

The Central and GitHub Packages steps run only after the matching public source
and tag are pushed. Before either publish call, an offline rebuild is compared
file-for-file by SHA-256 with the Maven consumable already tested by the Android
example. Release credentials therefore cannot authorize fetching new build
logic or publishing untested package bytes.

All published JAR tasks disable file timestamps and use reproducible file order;
`security_check.sh` fails if either deterministic-archive setting is removed.

## Failure and rollback

Do not replace a public tag or immutable package version. If a gate fails,
leave publishing frozen and correct the candidate on a new commit. If partial
publication occurred, stop remaining targets, mark/yank the version where the
registry permits it, rotate any exposed credential, investigate, and publish a
new version. Roll an action back only to a previously reviewed full SHA; never
restore a mutable tag, combined PAT, or broad Cloudflare bypass. Re-enable
dependency verification after the fast-release period and validate it from a
clean Gradle cache before making it a release gate again.

Attach closure evidence to `docs/todo/security.md` only after the corresponding
local, workflow, backend, storage, and post-publication checks actually pass.
