#!/bin/bash

# --- Input arguments ---
INPUT_FILE="$1"
SEGMENT_DURATION="$2"
KEY_ROTATION_INTERVAL="$3"

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <input_file> <segment_duration_seconds> <how_many_key_changes>"
  exit 1
fi

if ! [[ -f "$INPUT_FILE" ]]; then
  echo "Input file does not exist: $INPUT_FILE"
  exit 1
fi

BASENAME=$(basename "$INPUT_FILE" | cut -d. -f1)
WORKDIR="./${BASENAME}_hls"
mkdir -p "$WORKDIR"/keys "$WORKDIR"/segments
cd "$WORKDIR" || exit 1

echo "Splitting input into segments..."
ffmpeg -i "../$INPUT_FILE" -c copy -map 0 -f segment -segment_time "$SEGMENT_DURATION" -reset_timestamps 1 "segments/seg%d.ts" || exit 1

SEGMENTS=(segments/seg*.ts)
SEGMENT_COUNT=${#SEGMENTS[@]}
echo "Created $SEGMENT_COUNT segments."

KEY_COUNT=$(( SEGMENT_COUNT / KEY_ROTATION_INTERVAL + 1 ))
echo "Generating $KEY_COUNT encryption keys..."
for (( i=0; i<KEY_COUNT; i++ )); do
  openssl rand 16 > "keys/key${i}.key"
done

PLAYLIST="playlist.m3u8"
echo "#EXTM3U" > "$PLAYLIST"
echo "#EXT-X-VERSION:3" >> "$PLAYLIST"
echo "#EXT-X-TARGETDURATION:$SEGMENT_DURATION" >> "$PLAYLIST"
echo "#EXT-X-MEDIA-SEQUENCE:0" >> "$PLAYLIST"

echo "Encrypting segments and generating playlist..."


echo SEGMENT_COUNT $SEGMENT_COUNT
echo KEY_ROTATION_INTERVAL $KEY_ROTATION_INTERVAL
echo KEY_COUNT $KEY_COUNT
what=$(( SEGMENT_COUNT / KEY_COUNT ))
echo $what
LAST_INDEX=-1
for (( i=0; i<SEGMENT_COUNT; i++ )); do
  SEGMENT="${SEGMENTS[$i]}"
  OUTSEGMENT="segments/seg${i}_enc.ts"
  SEGMENT_NAME=$(basename "$OUTSEGMENT")

  # Get actual segment duration using ffprobe
  ACTUAL_DURATION=$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$SEGMENT")
  ACTUAL_DURATION=$(LC_ALL=C printf "%.3f" "$ACTUAL_DURATION")

  KEY_INDEX=$(( i / KEY_ROTATION_INTERVAL ))
  echo "next key $(( i % what )) KEY_INDEX $KEY_INDEX"
  KEY_PATH="keys/key${KEY_INDEX}.key"
  KEY_URI="key${KEY_INDEX}.key"
  IV_HEX=$(printf "%016x" "$i")
  KEY_HEX=$(xxd -p "$KEY_PATH" | tr -d '\n')

  echo openssl aes-128-cbc -e -in "$SEGMENT" -out "$OUTSEGMENT" -nosalt -iv $IV_HEX -K "$KEY_HEX"
  openssl aes-128-cbc -e -in "$SEGMENT" -out "$OUTSEGMENT" -nosalt -iv 0 -K "$KEY_HEX" || exit 1

  #if [[ $LAST_INDEX -ne $KEY_INDEX ]] ; then
    echo "#EXT-X-KEY:METHOD=AES-128,URI=\"$KEY_URI\",IV=0x$IV_HEX" >> "$PLAYLIST"
  #fi
  LAST_INDEX=$KEY_INDEX
  echo "#EXTINF:$ACTUAL_DURATION," >> "$PLAYLIST"
  echo "$SEGMENT_NAME" >> "$PLAYLIST"
done

echo "#EXT-X-ENDLIST" >> "$PLAYLIST"
echo "Done. Playlist at: $WORKDIR/$PLAYLIST"

