#!/bin/sh
# Maven Wrapper (script-only): downloads the configured Maven distribution on first use.
set -eu

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
[ -r "$PROPERTIES_FILE" ] || fail "Missing Maven Wrapper configuration: $PROPERTIES_FILE"

property() {
  awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "$PROPERTIES_FILE"
}

DISTRIBUTION_URL=$(property distributionUrl)
DISTRIBUTION_SHA256=$(property distributionSha256Sum)
[ -n "$DISTRIBUTION_URL" ] || fail "distributionUrl is not configured in $PROPERTIES_FILE"

if [ -n "${MVNW_REPOURL:-}" ]; then
  MAVEN_PATH=${DISTRIBUTION_URL#*org/apache/maven/}
  DISTRIBUTION_URL="${MVNW_REPOURL%/}/org/apache/maven/$MAVEN_PATH"
fi

case "$DISTRIBUTION_URL" in
  *-bin.zip) ;;
  *) fail "Maven Wrapper only supports a Maven *-bin.zip distribution URL" ;;
esac

url_hash() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  else
    fail "Maven Wrapper requires shasum or sha256sum to prepare its distribution directory"
  fi
}

verify_checksum() {
  [ -z "$DISTRIBUTION_SHA256" ] && return
  if command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$1" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$1" | awk '{print $1}')
  else
    fail "Cannot validate Maven distribution: shasum or sha256sum is required"
  fi
  [ "$actual" = "$DISTRIBUTION_SHA256" ] || fail "Maven distribution checksum validation failed"
}

ARCHIVE_NAME=${DISTRIBUTION_URL##*/}
DISTRIBUTION_NAME=${ARCHIVE_NAME%.zip}
DISTRIBUTION_NAME=${DISTRIBUTION_NAME%-bin}
MAVEN_USER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}
MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/$DISTRIBUTION_NAME/$(url_hash "$DISTRIBUTION_URL")/$DISTRIBUTION_NAME"
MAVEN_COMMAND="$MAVEN_HOME/bin/mvn"

if [ ! -x "$MAVEN_COMMAND" ]; then
  command -v unzip >/dev/null 2>&1 || fail "Maven Wrapper requires unzip to unpack Maven"
  TEMP_DIR=$(mktemp -d) || fail "Cannot create a temporary download directory"
  cleanup() { rm -rf "$TEMP_DIR"; }
  trap cleanup 0 HUP INT TERM
  ARCHIVE_PATH="$TEMP_DIR/$ARCHIVE_NAME"

  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error -o "$ARCHIVE_PATH" "$DISTRIBUTION_URL" \
      || fail "Unable to download Maven from $DISTRIBUTION_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet -O "$ARCHIVE_PATH" "$DISTRIBUTION_URL" \
      || fail "Unable to download Maven from $DISTRIBUTION_URL"
  else
    fail "Maven Wrapper requires curl or wget to download Maven"
  fi

  verify_checksum "$ARCHIVE_PATH"
  unzip -q "$ARCHIVE_PATH" -d "$TEMP_DIR" || fail "Unable to unpack Maven distribution"
  [ -x "$TEMP_DIR/$DISTRIBUTION_NAME/bin/mvn" ] \
    || fail "Downloaded archive does not contain $DISTRIBUTION_NAME/bin/mvn"
  mkdir -p "$(dirname "$MAVEN_HOME")"
  if ! mv "$TEMP_DIR/$DISTRIBUTION_NAME" "$MAVEN_HOME"; then
    [ -x "$MAVEN_COMMAND" ] || fail "Unable to install Maven distribution"
  fi
fi

exec "$MAVEN_COMMAND" "$@"
