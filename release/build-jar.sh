#!/usr/bin/env bash
#
# semantic-release `prepare` step: stamp the version semantic-release chose into
# plugin/build.gradle, then build the jar that gets attached to the release.
#
# plugin/build.gradle is the single source of the version — the RuneLite plugin
# manifest and the Java build stamp that client_status reports are both generated
# from it — so it has to be written before the jar is built, not after.
#
#   release/build-jar.sh 0.2.0
#
set -euo pipefail

version="${1:?usage: build-jar.sh <version>}"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_name="runelite-mcp-server-plugin-${version}.jar"
libs="${repo}/plugin/build/libs"

cd "$repo"

sed -i.bak "s/^version = '.*'\$/version = '${version}'/" plugin/build.gradle
rm -f plugin/build.gradle.bak
grep -qx "version = '${version}'" plugin/build.gradle || {
	echo "failed to write version ${version} into plugin/build.gradle" >&2
	exit 1
}

# Tests run here too: a release must never ship a jar that main's CI would have
# rejected, and this is the only build whose artifact reaches users.
(cd plugin && ./gradlew --no-daemon clean test jar)

# The release asset must be exactly this filename, so check it rather than
# globbing and hoping: a stale jar in build/libs would otherwise be attachable.
built=$(find "$libs" -maxdepth 1 -name 'runelite-mcp-server-plugin-*.jar' -printf '%f\n' | sort)
if [ "$built" != "$jar_name" ]; then
	echo "expected exactly one jar named ${jar_name} in ${libs}, found:" >&2
	echo "${built:-(none)}" >&2
	exit 1
fi

# Checksum with a bare filename inside it, so `sha256sum -c` works wherever the
# file is downloaded to.
(cd "$libs" && sha256sum "$jar_name" > "${jar_name}.sha256")

echo "built ${jar_name}"
cat "${libs}/${jar_name}.sha256"
