#!/usr/bin/env bash
# Builds the synthetic test maps and voxelizes them (no server needed). Run build.sh first.
#
#   PAPER_DIR=/path/to/paper-server test/run.sh [source]
set -euo pipefail
cd "$(dirname "$0")/.."

: "${PAPER_DIR:?Set PAPER_DIR to a Paper server folder that has been started once}"
JDK="${JAVA_HOME:+$JAVA_HOME/bin/}"
if command -v cygpath > /dev/null; then
  SEP=';'
  native() { cygpath -w "$1"; }
else
  SEP=':'
  native() { printf '%s\n' "$1"; }
fi

mkdir -p testdata/cls
{
  printf -- '-cp\n"'
  { find "$PAPER_DIR/libraries" "$PAPER_DIR/versions" -name '*.jar'; echo out/classes; echo testdata/cls; } \
    | while read -r j; do native "$j"; done | sed 's/\\/\\\\/g' | paste -sd "$SEP" | tr -d '\n'
  printf '"\n'
} > testdata/cp.txt
"${JDK}javac" -d testdata/cls @testdata/cp.txt test/*.java
"${JDK}java" @testdata/cp.txt MakeTestBsp testdata/room.bsp
"${JDK}java" @testdata/cp.txt MakeTestVbsp testdata/room_source.bsp
if [ "${1:-}" = source ]; then
  "${JDK}java" @testdata/cp.txt SourceTest testdata/room_source.bsp
else
  "${JDK}java" @testdata/cp.txt VoxelTest testdata/room.bsp
fi
