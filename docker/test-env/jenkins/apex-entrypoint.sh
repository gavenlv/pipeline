#!/bin/bash
# Custom entrypoint for apex-test-jenkins.
# Plugins are pre-installed in the volume. Just start Jenkins directly.

set -e

PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"
PLUGIN_COUNT=$(ls -1 "$PLUGINS_DIR"/*.jpi 2>/dev/null | wc -l)
echo "[apex-entry] Found $PLUGIN_COUNT pre-installed plugins in $PLUGINS_DIR"

if [ "$PLUGIN_COUNT" -eq 0 ]; then
    echo "[apex-entry] WARNING: No plugins found! Jenkins will start without workflow plugins."
    echo "[apex-entry] Pre-install plugins into the volume before starting."
fi

echo "[apex-entry] Starting Jenkins..."
exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
