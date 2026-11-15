#!/usr/bin/env bash
#
# Starts JEditor, building it first if needed.
#
#   ./jeditor.sh                 open the current directory
#   ./jeditor.sh <folder|file>   open that folder, or that file and its folder
#   ./jeditor.sh --rebuild ...   force a rebuild before starting
#
# Needs a JDK 21+ (taken from JAVA_HOME if set, otherwise from PATH) and, to build, Maven.

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/jeditor-ui/target/jeditor.jar"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
else
    echo "jeditor: no Java found. Install a JDK 21 or newer, or set JAVA_HOME." >&2
    exit 1
fi

# e.g.  openjdk version "21.0.2" 2024-01-16  ->  21      (and "1.8.0_392" -> 1)
VERSION="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [ -z "$VERSION" ] || [ "$VERSION" -lt 21 ]; then
    echo "jeditor: Java 21 or newer is required, but $JAVA is version ${VERSION:-unknown}." >&2
    exit 1
fi

REBUILD=no
if [ "${1:-}" = "--rebuild" ]; then
    REBUILD=yes
    shift
fi

# Build when there is no jar yet, when asked to, or when any source is newer than the jar.
if [ "$REBUILD" = no ] && [ -f "$JAR" ]; then
    if [ -n "$(find "$DIR/pom.xml" "$DIR"/jeditor-*/pom.xml "$DIR"/jeditor-*/src -newer "$JAR" -print -quit 2>/dev/null)" ]; then
        REBUILD=yes
    fi
else
    REBUILD=yes
fi

if [ "$REBUILD" = yes ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "jeditor: Maven (mvn) is needed to build JEditor but was not found." >&2
        exit 1
    fi
    echo "jeditor: building..." >&2
    (cd "$DIR" && mvn -q -DskipTests package)
fi

exec "$JAVA" -jar "$JAR" "$@"
