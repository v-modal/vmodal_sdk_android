#!/usr/bin/env bash
help='
  Run Android SDK example checks.

  Examples:
    bash test.sh live_03_fullapp
'
set -euo pipefail
cd "$(dirname "$0")"

examples_live_03_fullapp() {
  local help='
    ## Usage:
      VMODAL_API_KEY="..." bash test.sh live_03_fullapp
  '
  : "${VMODAL_API_KEY:?VMODAL_API_KEY is required}"
  (
    cd 03_fullapp
    ./gradlew --no-daemon --dependency-verification off --rerun-tasks \
      :app:testDebugUnitTest \
      --tests com.vmodal.sdk.examples.fullapp.FullAppLiveRetrievalTest \
      -PvmodalLive03Fullapp=true
  )
}

cmd="${1:-help}"
case "$cmd" in
  live_03_fullapp) examples_live_03_fullapp ;;
  help|-h|--help) echo "$help" ;;
  *) echo "$help"; exit 1 ;;
esac
