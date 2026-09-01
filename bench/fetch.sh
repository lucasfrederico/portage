#!/usr/bin/env bash
# Downloads the server jars the bench needs and drops the built plugin into both backends.
# Usage: ./fetch.sh [minecraft-version]   (default 26.2)
set -euo pipefail

cd "$(dirname "$0")"
MC="${1:-26.2}"
FILL="https://fill.papermc.io/v3/projects"

# The server directories are generated: seed/ holds the hand-written configs,
# the servers fill in the rest on first boot.
mkdir -p a b velocity
cp -R seed/a/. a/
cp -R seed/b/. b/
cp -R seed/velocity/. velocity/

download_url() {
  python3 -c '
import json, sys
build = json.load(sys.stdin)
downloads = build.get("downloads") or {}
first = next(iter(downloads.values()), None)
if not first:
    sys.exit("no download in build " + str(build.get("id")))
print(first["url"])'
}

latest_version() {
  python3 -c '
import json, sys
data = json.load(sys.stdin)
print(data["versions"][0]["version"]["id"])'
}

fetch_build() {
  local project="$1" version="$2" target="$3"
  echo "fetching $project $version -> $target"
  local url
  url="$(curl -fsS "$FILL/$project/versions/$version/builds/latest" | download_url)"
  curl -fsSL -o "$target" "$url"
}

fetch_build paper "$MC" a/paper.jar
if ! fetch_build folia "$MC" b/folia.jar; then
  echo "no Folia build for $MC yet; backend b will run Paper instead"
  cp a/paper.jar b/folia.jar
fi

VELOCITY_VERSION="$(curl -fsS "$FILL/velocity/versions" | latest_version)"
fetch_build velocity "$VELOCITY_VERSION" velocity/velocity.jar

echo "eula=true" > a/eula.txt
echo "eula=true" > b/eula.txt

PLUGIN="$(ls ../portage-paper/build/libs/portage-paper-*.jar 2>/dev/null | grep -v -- '-javadoc' | head -1 || true)"
if [ -z "$PLUGIN" ]; then
  echo "plugin jar not built yet; running ./gradlew build"
  (cd .. && ./gradlew build -q)
  PLUGIN="$(ls ../portage-paper/build/libs/portage-paper-*.jar | grep -v -- '-javadoc' | head -1)"
fi
mkdir -p a/plugins b/plugins
cp "$PLUGIN" a/plugins/
cp "$PLUGIN" b/plugins/
echo "ready: docker compose up -d"
