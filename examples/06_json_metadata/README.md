# JSON metadata upload, indexing, and tag-filtered search

This Kotlin/JVM example mirrors the Python `06_json_gps_metadata` flow. It:

1. reads and validates two JSONL metadata rows;
2. uploads each video and its row to the same derived stream;
3. starts the remote frame indexer and waits for completion;
4. searches by the first video's GPS and timestamp tags;
5. searches by the second video's `category_run` tag; and
6. joins each returned hit's stream to the source JSON row to print GPS, timestamp,
   category, and tags.

The metadata endpoint applies one request-level `stream_name` to every row. The
example therefore uploads one temporary JSONL file per video. Each upload uses
`writeMode = "overwrite"` so the supplied metadata replaces the baseline row
created by the video upload.

## Metadata format

`metadata.jsonl` contains one object per video. `video` must exactly match the
uploaded basename. The first eight extensionless filename characters must also
be unique because they are used as frame stream names.

```json
{"video":"tokyo_walk.mp4","gps":{"lat":35.6895,"lon":139.6917},"timestamp":"2023081123456","category":"walk","tags":["gps_lat_35_6895_lon_139_6917","timestamp_2023081123456","category_walk"]}
{"video":"tokyo_run.mp4","gps":{"lat":37.6895,"lon":131.6917},"timestamp":"2024081123456","category":"run","tags":["gps_lat_37_6895_lon_131_6917","timestamp_2024081123456","category_run"]}
```

The backend stores `gps`, `timestamp`, and `category` in `info_json`, but its
current search response does not return arbitrary `info_json` fields. It does
promote `tags` to the frame index's `cat5_json`. The example filters remote
frames with `queryJsonField = mapOf("tags" to "tag_a AND tag_b")`, then uses the
returned stream identity to retrieve the corresponding values from the source
JSON row.

## Run

From the repository root:

```bash
source ./isetup_env.sh
export PYTHONPATH="$PWD"
source ztmp/env_gitignore.sh
export VMODAL_API_KEY="${TEST_CLIENT_CLERK_USER_API_TOKEN}"
export VMODAL_BASE_URL="${TEST_CLIENT_SERVER_API_URL:-https://searchapi-test.v-modal.com}"

cp uinterface/sdk_android/examples/03_fullapp/asset/video_10frames.mp4 /tmp/tokyo_walk.mp4
cp uinterface/sdk_android/examples/03_fullapp/asset/video_10frames.mp4 /tmp/tokyo_run.mp4

cd uinterface/sdk_android/examples/06_json_metadata
../02_search/gradlew -p . --no-daemon --dependency-verification off run \
  --args="/tmp/tokyo_walk.mp4 /tmp/tokyo_run.mp4 gps_metadata_demo metadata.jsonl false"
```

Use a new group name for a repeated run, or change the last argument to `true`
when overlapping metadata identities are intentional.

## Compile without remote calls

```bash
cd uinterface/sdk_android/examples/06_json_metadata
../02_search/gradlew -p . --no-daemon --dependency-verification off classes
```
