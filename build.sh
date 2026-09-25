#!/usr/bin/env bash
# Builds BspImport-<version>.jar against a Paper server's own libraries.
#
#   PAPER_DIR=/path/to/paper-server ./build.sh
#
# PAPER_DIR is a Paper server folder that has been started once (it holds libraries/ and versions/).
# The JDK comes from JAVA_HOME, or from PATH when JAVA_HOME isn't set.
set -euo pipefail
cd "$(dirname "$0")"

: "${PAPER_DIR:?Set PAPER_DIR to a Paper server folder that has been started once}"
JDK="${JAVA_HOME:+$JAVA_HOME/bin/}"
VERSION=$(grep -m1 '^version:' res/plugin.yml | sed "s/version: *'\(.*\)'/\1/")

# javac on Windows (Git Bash) wants Windows paths joined with ';'
if command -v cygpath > /dev/null; then
  SEP=';'
  native() { cygpath -w "$1"; }
else
  SEP=':'
  native() { printf '%s\n' "$1"; }
fi

rm -rf out && mkdir -p out
# The classpath goes through an argfile: 100+ jars overflow the command line on Windows.
{
  printf -- '-cp\n"'
  find "$PAPER_DIR/libraries" "$PAPER_DIR/versions" -name '*.jar' | while read -r j; do native "$j"; done \
    | sed 's/\\/\\\\/g' | paste -sd "$SEP" | tr -d '\n'
  printf '"\n'
} > out/cp.txt
find src -name '*.java' > out/sources.txt

"${JDK}javac" --release 25 -Xlint:deprecation -Xlint:unchecked -d out/classes @out/cp.txt @out/sources.txt
cp res/plugin.yml res/config.yml out/classes/
"${JDK}jar" --create --file "BspImport-$VERSION.jar" -C out/classes .
echo "built BspImport-$VERSION.jar"
