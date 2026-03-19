#!/bin/sh
# Maven Wrapper script

set -e

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(cd "$(dirname "$0")" && pwd)}"
MAVEN_WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"

# Download Maven if not present
if [ ! -d "$HOME/.m2/wrapper/dists" ]; then
    mkdir -p "$HOME/.m2/wrapper/dists"
fi

# Read distribution URL from properties
if [ -f "$MAVEN_WRAPPER_PROPERTIES" ]; then
    DISTRIBUTION_URL=$(grep "distributionUrl" "$MAVEN_WRAPPER_PROPERTIES" | cut -d'=' -f2)
fi

DISTRIBUTION_URL="${DISTRIBUTION_URL:-https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip}"
MAVEN_VERSION=$(echo "$DISTRIBUTION_URL" | sed 's/.*apache-maven-\([0-9.]*\).*/\1/')
MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-$MAVEN_VERSION"

if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven $MAVEN_VERSION..."
    mkdir -p "$MAVEN_HOME"
    curl -sL "$DISTRIBUTION_URL" -o "$MAVEN_HOME/maven.zip"
    unzip -q "$MAVEN_HOME/maven.zip" -d "$MAVEN_HOME"
    mv "$MAVEN_HOME/apache-maven-$MAVEN_VERSION"/* "$MAVEN_HOME/"
    rm -rf "$MAVEN_HOME/apache-maven-$MAVEN_VERSION" "$MAVEN_HOME/maven.zip"
    echo "Maven $MAVEN_VERSION installed"
fi

export M2_HOME="$MAVEN_HOME"
export PATH="$MAVEN_HOME/bin:$PATH"

exec mvn "$@"
