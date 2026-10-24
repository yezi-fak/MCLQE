#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper bootstrap script (Linux / macOS)
# Downloads maven-wrapper.jar on first run; the jar then fetches and runs
# the Maven distribution declared in .mvn/wrapper/maven-wrapper.properties.
# ----------------------------------------------------------------------------
set -e

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"

if [ ! -f "$PROPERTIES" ]; then
    echo "Error: cannot find $PROPERTIES" >&2
    exit 1
fi

distributionUrl=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES" | tr -d '\r')
wrapperUrl=$(sed -n 's/^wrapperUrl=//p' "$PROPERTIES" | tr -d '\r')

WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper bootstrap ..."
    mkdir -p "$WRAPPER_DIR"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$wrapperUrl" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$wrapperUrl" -O "$WRAPPER_JAR"
    else
        echo "Error: curl or wget is required to bootstrap Maven Wrapper" >&2
        exit 1
    fi
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" \
    -classpath "$WRAPPER_JAR" \
    -Dmaven.multiModuleProjectDirectory="$BASE_DIR" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
