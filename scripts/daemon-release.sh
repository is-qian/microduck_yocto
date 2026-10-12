#!/bin/sh
# Resolve a microduck_daemon release to what the image recipe needs.
#
#   scripts/daemon-release.sh            # the newest stable release
#   scripts/daemon-release.sh 0.16.1     # that release
#
# Prints, on stdout and nothing else,
#
#   MICRODUCK_DAEMON_VERSION=0.16.1
#   MICRODUCK_DAEMON_SRCREV=d818af5d91fce0e3f5f8c22d47d47caf7b3f17a1
#
# which microduck-daemon.bb takes as PV and SRCREV_daemon (README.md, "Which
# daemon the image seeds"). Both come from the release's manifest.json, never the
# tag or main; the recipe says why.
set -eu

cd "$(dirname "$0")/.."

want=${1:-latest}
releases=https://github.com/pollen-robotics/microduck_daemon/releases
case "$want" in
latest) url=$releases/latest/download/manifest.json ;;
*) url=$releases/download/daemon-v${want#v}/manifest.json ;;
esac
manifest=$(curl -fsSL "$url") || {
	echo "$0: no daemon release manifest at $url" >&2
	exit 1
}
read -r version srcrev <<EOF
$(printf '%s' "$manifest" | python3 -c 'import json, sys; m = json.load(sys.stdin); print(m["version"], m["source_revision"])')
EOF
# A failing $(...) inside a heredoc trips neither set -e nor read, so an
# unparseable manifest would otherwise print empty values and exit 0.
[ -n "$version" ] && [ -n "$srcrev" ] || {
	echo "$0: no version/source_revision in $url" >&2
	exit 1
}

# The image seeds one policy set (microduck-policies_<v>.bb), and a release names
# the one it needs in scripts/seed-policies.sh. A mismatch builds fine and gives a
# robot that reports unhealthy and rolls back every update (the
# microduck-policies recipe has the story), so refuse it before anything builds.
needs=$(curl -fsSL "https://raw.githubusercontent.com/pollen-robotics/microduck_daemon/$srcrev/scripts/seed-policies.sh" \
	| sed -n 's/^POLICY_VERSION="\${POLICY_VERSION:-\([^}]*\)}"$/\1/p') || needs=
seeds=$(ls meta-microduck/recipes-microduck/microduck-policies/ | sed -n 's/^microduck-policies_\(.*\)\.bb$/\1/p')
if [ -z "$needs" ]; then
	echo "$0: WARNING: could not read POLICY_VERSION from daemon $version; not checking the policy set" >&2
elif [ "$needs" != "$seeds" ]; then
	echo "$0: daemon $version needs policy set $needs, the image seeds $seeds." >&2
	echo "  Bump microduck-policies, or name another daemon release." >&2
	exit 1
fi

echo "daemon: $version at $srcrev ($want)" >&2
echo "MICRODUCK_DAEMON_VERSION=$version"
echo "MICRODUCK_DAEMON_SRCREV=$srcrev"
