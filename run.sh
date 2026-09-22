#!/usr/bin/env bash
# Launches NeoNorton's shaded jar (built via `mvn package`), forwarding any arguments
# (+LINE, INPUT [OUTPUT], /DA|/DB|/DC, or the long-form --line/--input/--output/--display/
# --safe/--encoding) straight through to the app — see README.md's "Command-line arguments".
#
# Runs detached (like run.bat's javaw does on Windows), so this script returns immediately
# instead of blocking the shell that invoked it.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/target/retro-text-editor-1.0-SNAPSHOT-shaded.jar"

if [ ! -f "$JAR" ]; then
    echo "NeoNorton jar not found at $JAR" >&2
    echo "Build it first with: mvn package" >&2
    exit 1
fi

nohup java -jar "$JAR" "$@" > /dev/null 2>&1 &
disown
