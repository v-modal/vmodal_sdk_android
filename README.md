# VModal Android SDK reference

The canonical public URL for the Kotlin API reference is:

**https://v-modal.github.io/vmodal_sdk_android/**

This directory contains the generated [Dokka](https://kotlinlang.org/docs/dokka-introduction.html) HTML for the public API in `src/main/kotlin/com/vmodal/sdk`. Do not edit the generated HTML by hand; update the Kotlin KDoc or `DOC_REF.md`, then regenerate the site.

## Build the documentation

The documentation toolchain requires Java 17, Gradle 8.6, Python, and the Python package `fire`.

From the repository root:

```bash
source ./isetup_env.sh
export PYTHONPATH="$(pwd)"
cd uinterface/sdk_android

# Verify the local Java and Gradle toolchain.
bash install.sh check

# Regenerate docs_sdk and validate the public documentation contract.
python docs.py generate

# Rebuild into a temporary directory and confirm docs_sdk is current.
python docs.py check
```

If Java 17 or Gradle 8.6 is missing, run `bash install.sh install` before generating the documentation. The generator validates required public symbols, rejects internal route and environment details, and replaces `docs_sdk` only after a successful build.

To build Dokka HTML into another directory without replacing `docs_sdk`, pass an absolute output path:

```bash
bash build.sh docs "$PWD/build/dokka/html"
```

## Preview locally

After generation, serve the files over HTTP so navigation and assets behave like the published site:

```bash
python -m http.server 8000 --directory docs_sdk
```

Open http://localhost:8000/ in a browser.

## Publish the public site

Documentation publication is handled by `.github/workflows/sdk_android_test_release.yml`. To publish only the SDK reference from the `dev` branch:

```bash
gh workflow run .github/workflows/sdk_android_test_release.yml \
  --ref dev \
  -f publish_sdk_android=false \
  -f publish_maven_central=false \
  -f publish_github_packages=false \
  -f publish_sdk_docs_only=true
```

The workflow regenerates and validates the site, uploads an immutable build artifact, publishes it to the public repository's `gh-pages` branch, and verifies that the deployed `RELEASE_SHA` matches the source commit. Publication requires the repository's configured `GH_TOKEN`; never add release credentials to this directory.

If the public URL returns 404, run the docs-only publication above. The workflow creates or updates the `gh-pages` branch and configures GitHub Pages to serve it.

To follow the run:

```bash
gh run watch
```
