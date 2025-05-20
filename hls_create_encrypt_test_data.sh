#!/bin/bash

# --- Input arguments ---
INPUT_FILE="$1"
SEGMENT_DURATION="$2"
KEY_ROTATION_INTERVAL="$3"

# --- Validations ---
if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <input_file> <segment_duration_seconds> <how_many_key_changes>"
  exit 1
fi

if ! [[ -f "$INPUT_FILE" ]]; then
  echo "Input file does not exist: $INPUT_FILE"
  exit 1
fi

# --- Setup ---
BASENAME=$(basename "$INPUT_FILE" | cut -d. -f1)
WORKDIR="./${BASENAME}_hls"
mkdir -p "$WORKDIR"/keys "$WORKDIR"/segments
cd "$WORKDIR" || exit 1

# --- Step 1: Split input into segments ---
echo "Splitting input into segments..."
ffmpeg -i "../$INPUT_FILE" -c copy -map 0 -f segment -segment_time "$SEGMENT_DURATION" -reset_timestamps 1 "segments/seg%d.ts" || exit 1

SEGMENT_COUNT=$(ls segments/seg*.ts | wc -l)
echo "Created $SEGMENT_COUNT segments."

# --- Step 2: Create AES keys ---
KEY_COUNT=$(( SEGMENT_COUNT / KEY_ROTATION_INTERVAL + 1 ))
echo "Generating $KEY_COUNT encryption keys..."

for (( i=0; i<KEY_COUNT; i++ )); do
  openssl rand 16 > "keys/key${i}.key"
done

# --- Step 3: Encrypt segments ---
echo "Encrypting segments..."
PLAYLIST="playlist.m3u8"
echo "#EXTM3U" > "$PLAYLIST"
echo "#EXT-X-VERSION:3" >> "$PLAYLIST"
echo "#EXT-X-TARGETDURATION:$SEGMENT_DURATION" >> "$PLAYLIST"
echo "#EXT-X-MEDIA-SEQUENCE:0" >> "$PLAYLIST"

for (( i=0; i<SEGMENT_COUNT; i++ )); do
  SEGMENT="segments/seg${i}.ts"
  OUTSEGMENT="segments/seg${i}_enc.ts"

  KEY_INDEX=$(( i % KEY_ROTATION_INTERVAL ))
  echo "THE INDEX $KEY_INDEX"
  KEY_PATH="keys/key${KEY_INDEX}.key"
  KEY_URI="key${KEY_INDEX}.key"
  IV_HEX=$(printf "%032x" "$i")

  openssl aes-128-cbc -e -in "$SEGMENT" -out "$OUTSEGMENT" -nosalt -iv 0 -K $(xxd -p "$KEY_PATH") || exit 1

  echo "#EXT-X-KEY:METHOD=AES-128,URI=\"$KEY_URI\",IV=0x$IV_HEX" >> "$PLAYLIST"
  echo "#EXTINF:$SEGMENT_DURATION," >> "$PLAYLIST"
  echo "${OUTSEGMENT}" >> "$PLAYLIST"
done

echo "#EXT-X-ENDLIST" >> "$PLAYLIST"

echo "Done."
echo "Playlist: $WORKDIR/$PLAYLIST"

