# Android SDK release runbook

Published coordinates are:

```text
com.vmodal:vmodal-sdk-android:<version>
```

The workflow uses a **minimal release security** profile for fast publication.
Its only blocking security job is `secret_detection`. The workflow remains
manual, and publication jobs require approval from the protected
`sdk-android-production` environment. SDK, live API, consumer, version,
license, source-SHA, and artifact-checksum failures remain blocking correctness
failures; this profile is not a complete supply-chain security audit.

## Pull-request CI authority boundary

`.github/workflows/sdk_android_ci.yml` is the credential-free pull-request
gate. It has only `contents: read`, disables persisted checkout credentials,
uses immutable action commits, and never enters the production environment or
references live/release secrets. The separate release workflow remains manual
and protected.

The `sdk_core` check runs route-contract checks, the five-invariant minimal
policy, the normal SDK test lifecycle, Dokka, checked-in documentation
validation, and publication to a run-specific Maven repository under the
runner temporary directory. Strict dependency-verification metadata is not
enforced in this fast path. Only after `sdk_core` succeeds do `clean_consumer`,
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

After the dry run succeeds, revoke the previous combined classic PAT
and broad Cloudflare token. Review audit logs before revocation and record the
owner, time, and replacement credential scope in the release evidence.

## Minimal release security and immutable actions

Every remote `uses:` reference must be a reviewed full 40-character commit SHA
with its human-readable version in a comment. Before changing a SHA:

1. resolve and peel the intended upstream tag;
2. review the action source, install/network behavior, inputs, post-step
   cleanup, and token handling at that commit;
3. update the SHA and review record together;
4. run `bash security_check.sh minimal` and a clean-cache workflow run.

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

`secret_detection` checks out exactly `${{ github.sha }}` with depth 1 and
persisted credentials disabled. It scans only the checked-out
`uinterface/sdk_android` filesystem tree with verified findings enabled; it
does not scan the private monorepo's Git history. The job uses the TruffleHog
3.95.9 Linux amd64 archive and verifies archive SHA-256
`f6d1106b85107d79527ed7a5b98b592beadd8b770dc3c9e8c1ad99e1b2cf127e`
before execution. Download or checksum failure fails closed. The scanner uses
bounded retries, emits no uploaded match report, and receives no production
credential.

`release_gate` explicitly depends on `secret_detection` and accepts only a
`success` result. Documentation-only publication also depends on the same scan.
A failed, skipped, cancelled, or timed-out scan cannot publish. To rerun after
a scanner download outage, rerun the unchanged candidate SHA; do not bypass or
convert the scan to a warning.

The former aggregate policy, OSV scan, CycloneDX SBOM generation, wrapper-JAR
shell hashing, plaintext source/JAR/APK/D8 route scans, diagnostic generated-
output scans, and strict verification commands remain beside their former call
sites under `DISABLED_FAST_RELEASE` comments. They are inactive and provide no
blocking evidence in this profile. Route generation and source-of-truth route
comparison remain active correctness tests.

The SDK root, standalone consumer, search example, and full-app example retain
reviewed `gradle/verification-metadata.xml` files for optional audit use. Fast
pull-request and release builds use dependency verification `off`; CI does not
regenerate metadata. The root and search example own the reviewed Gradle 8.6
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
metadata in CI. These manual steps do not become release gates unless a later
reviewed change deliberately restores them.

After review, run `./gradlew --no-daemon verifyIdeSources` from the SDK root to
confirm Android Studio can attach runtime dependency sources.

Generate the resolved dependency report before approval:

```bash
./gradlew --no-daemon --dependency-verification off dependencyReport
```

Archive the dependency report, optional verification-metadata review, secret-
scan result, and tested artifact checksums with the release evidence.

## Blocking correctness gates

The reduced security profile does not weaken these release gates:

- SDK compilation, unit/regression tests, and route-contract comparison;
- authenticated live SDK and full-app tests;
- Maven package and clean consumer/example builds;
- source SHA and SDK/package version agreement;
- binary/source JAR presence and packaged Apache license;
- SHA-256 verification of the tested Maven artifact and publish rebuild; and
- publication from the approved gate at the exact candidate commit.

## Accepted residual risks

The release owner accepts that this profile has no blocking evidence for
vulnerable transitive dependencies or a current SBOM, old secrets elsewhere in
private monorepo history, strict Gradle dependency checksum/signature
verification, wrapper-JAR provenance beyond reviewed configuration and action
pins, hidden route/config strings in compiled outputs, or a complete supply-
chain audit. Preserved disabled code must not be described as a successful
check.

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
   report, secret-scan result, artifact checksums, and approvals. Record any
   optional metadata, OSV, or SBOM review explicitly as non-blocking evidence.
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

All published JAR tasks disable file timestamps and use reproducible file order.
The Maven package job still verifies the packaged license and tested bytes.

## Failure and rollback

Do not replace a public tag or immutable package version. If a gate fails,
leave publishing frozen and correct the candidate on a new commit. If partial
publication occurred, stop remaining targets, mark/yank the version where the
registry permits it, rotate any exposed credential, investigate, and publish a
new version. Roll an action back only to a previously reviewed full SHA; never
restore a mutable tag, combined PAT, or broad Cloudflare bypass. Any proposal to
restore heavy checks must be reviewed separately and validated from a clean
cache before becoming a release gate.
