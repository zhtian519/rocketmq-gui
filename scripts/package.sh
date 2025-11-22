#!/usr/bin/env bash
set -euo pipefail

command -v mvn >/dev/null 2>&1 || { echo "mvn command not found" >&2; exit 1; }
command -v jpackage >/dev/null 2>&1 || { echo "jpackage command not found (needs JDK 14+)" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 command not found" >&2; exit 1; }

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
APP_INPUT="$ROOT_DIR/target/app"
JPACKAGE_DIR="$ROOT_DIR/target/jpackage"
APP_NAME="RocketMQAdmin"
MAIN_CLASS="org.tzh.rocketmqgui.AppLauncher"
POM_PATH="$ROOT_DIR/pom.xml"

os_name="$(uname | tr '[:upper:]' '[:lower:]')"
case "$os_name" in
  darwin*) classifier="mac" ;;
  linux*) classifier="linux" ;;
  msys*|mingw*|cygwin*)
    echo "Windows packaging is handled by scripts/package-win.bat" >&2
    exit 1
    ;;
  *)
    echo "Unsupported platform: $os_name" >&2
    exit 1
    ;;
 esac

mkdir -p "$DIST_DIR"
rm -rf "$APP_INPUT" "$JPACKAGE_DIR"

pushd "$ROOT_DIR" >/dev/null
mvn -q -DskipTests clean package
read -r ARTIFACT_ID PROJECT_VERSION < <(POM_PATH="$POM_PATH" python3 <<'PY'
import os
import xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
tree = ET.parse(os.environ["POM_PATH"])
root = tree.getroot()
def val(tag):
    elem = root.find(f'{{{ns["m"]}}}{tag}')
    text = elem.text if elem is not None and elem.text is not None else ''
    return text.strip()
print(val('artifactId'), val('version'))
PY
)
FINAL_NAME="${ARTIFACT_ID}-${PROJECT_VERSION}"
MAIN_JAR="${FINAL_NAME}-app.jar"
APP_VERSION="${PROJECT_VERSION%%-*}"
if [[ -z "$APP_VERSION" ]]; then
  APP_VERSION="$PROJECT_VERSION"
fi
if [[ ! -f "target/$MAIN_JAR" ]]; then
  echo "Main jar target/$MAIN_JAR not found" >&2
  exit 1
fi
popd >/dev/null

mkdir -p "$APP_INPUT"
cp "$ROOT_DIR/target/$MAIN_JAR" "$APP_INPUT/"

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$APP_INPUT" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --dest "$JPACKAGE_DIR"

APP_BUNDLE="${JPACKAGE_DIR}/${APP_NAME}.app"
if [[ ! -d "$APP_BUNDLE" ]]; then
  APP_BUNDLE="${JPACKAGE_DIR}/${APP_NAME}"
fi
if [[ ! -d "$APP_BUNDLE" ]]; then
  echo "Could not locate jpackage output under $JPACKAGE_DIR" >&2
  exit 1
fi

ZIP_NAME="rocketmq-gui-${classifier}.zip"
ZIP_PATH="$DIST_DIR/$ZIP_NAME"
rm -f "$ZIP_PATH"
(
  cd "$JPACKAGE_DIR"
  zip -qr "$ZIP_PATH" "$(basename "$APP_BUNDLE")"
)

echo "Packaged runtime available at dist/${ZIP_NAME}"
