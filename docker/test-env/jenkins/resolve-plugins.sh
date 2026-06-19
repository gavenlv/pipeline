#!/bin/bash
# Pre-resolve plugin download URLs for a given Jenkins version by parsing
# plugin-versions.json (the canonical, complete per-version history of every
# Jenkins plugin) and emitting a TSV file:
#
#   <plugin>  <download-url>
#
# containing the highest version of each plugin whose `requiredCore` is <=
# the given Jenkins version. Used by apex-entrypoint.sh.
#
# Requires: node (host-side, runs once per Jenkins version).

set -e

JENKINS_VERSION="${1:-2.462.3}"
OUTPUT_FILE="${2:-/tmp/plugin-urls.tsv}"
PV_URL="https://updates.jenkins.io/plugin-versions.json"
PV_CACHE="${PV_CACHE:-/tmp/pv.json}"

if [ ! -s "$PV_CACHE" ]; then
    echo "[resolve] Fetching plugin-versions.json..." >&2
    curl -fsSL --max-time 180 "$PV_URL" -o "$PV_CACHE"
fi

node -e '
const fs = require("fs");
const target = process.argv[1].split(".").map(s => parseInt(s, 10));
const data = JSON.parse(fs.readFileSync(process.argv[2], "utf8")).plugins;
const out = fs.createWriteStream(process.argv[3]);

function cmpVer(a, b) {
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
        const x = a[i] || 0, y = b[i] || 0;
        if (x !== y) return x > y ? 1 : -1;
    }
    return 0;
}

for (const [pname, versions] of Object.entries(data)) {
    let best = null;
    for (const [vstr, meta] of Object.entries(versions)) {
        if (!meta.requiredCore || !meta.url) continue;
        const rc = meta.requiredCore.split(".").map(s => parseInt(s, 10));
        if (cmpVer(rc, target) > 0) continue;
        if (best === null) { best = { v: vstr, url: meta.url }; continue; }
        const varr = vstr.split(/[^\d]+/).filter(s => s !== "").map(s => parseInt(s, 10));
        if (cmpVer(varr, best.v.split(/[^\d]+/).filter(s => s !== "").map(s => parseInt(s, 10))) > 0) {
            best = { v: vstr, url: meta.url };
        }
    }
    if (best) out.write(pname + "\t" + best.url + "\n");
}
out.end();
' "$JENKINS_VERSION" "$PV_CACHE" "$OUTPUT_FILE"

echo "[resolve] Resolved $(wc -l < "$OUTPUT_FILE") plugin URLs to $OUTPUT_FILE" >&2
