#!/bin/bash
# Custom entrypoint for apex-test-jenkins:
#  1) Pre-stage all required plugin .hpi files into /var/jenkins_home/plugins/
#     by reading plugin-versions.json (the canonical, complete per-version
#     history of every Jenkins plugin) and resolving the highest version
#     whose `requiredCore` is <= our Jenkins version.
#  2) Hand off to the official jenkins docker-entrypoint.sh
#
# plugin-versions.json is ~22MB but is THE source of truth for the full
# history. The UC's update-center.json is filtered for the current LTS and
# does NOT include older versions, so we cannot use it to find compatible
# plugins for an older Jenkins line.

set +e

JENKINS_VERSION="${JENKINS_VERSION:-2.462.3}"
PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"
PV_URL="https://updates.jenkins.io/plugin-versions.json"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

PLUGINS=(
    configuration-as-code
    pipeline-groovy-lib
    workflow-aggregator
    workflow-cps
    workflow-job
    workflow-multibranch
    pipeline-stage-step
    pipeline-utility-step
    git
    credentials
    credentials-binding
    plain-credentials
    timestamper
    ws-cleanup
    ant
    mailer
    script-security
    scm-api
    structs
    ssh-credentials
    display-url-api
    trilead-api
    sshd
    bouncycastle-api
    instance-identity
    workflow-step-api
    workflow-api
    workflow-scm-step
    workflow-support
    pipeline-input-step
    pipeline-model-definition
    pipeline-model-extensions
    token-macro
    jdk-tool
    command-launcher
    pipeline-graph-analysis
    git-client
    apache-httpcomponents-client-4-api
    durable-task
    cloudbees-folder
    authorize-project
    jackson2-api
    # transitive deps surfaced by Failed-to-load errors
    commons-lang3-api
    commons-text-api
    json-api
    caffeine-api
    prism-api
    bootstrap5-api
    font-awesome-api
    echarts-api
    popper-api
    data-tables-api
    jquery3-api
    snakeyaml-api
)

mkdir -p "$PLUGINS_DIR"
mkdir -p /var/jenkins_home/casc

echo "[apex-entry] Resolving plugin versions for Jenkins ${JENKINS_VERSION}..."

# Fetch plugin-versions.json (the canonical history)
if ! curl -fsSL --max-time 180 "$PV_URL" -o "$TMPDIR/pv.json"; then
    echo "[apex-entry] WARNING: failed to fetch plugin-versions.json; continuing without plugin pre-install"
    exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
fi

# Pre-resolve download URLs for every plugin we want. We do this in a
# single awk pass over plugin-versions.json for speed.
# awk program: for each plugin entry, find the highest "version" key whose
# value's "requiredCore" is <= our Jenkins version, then print
# "<plugin>  <url>".
#
# Input structure (per plugin):
#   "plugin-name": { "v1": { "requiredCore": "X", "url": "..." },
#                    "v2": { ... }, ... }
#
# We compare version strings lexicographically (good enough - Jenkins plugin
# versions sort lexically because they always start with a major digit).
echo "[apex-entry] Building plugin->URL map..."
awk -v target="$JENKINS_VERSION" '
function cmpver(a, b,    i) {
    # returns 1 if a > b, -1 if a < b, 0 if equal
    n = split(a, A, "."); m = split(b, B, ".");
    for (i = 1; i <= (n < m ? n : m); i++) {
        if (A[i] + 0 > B[i] + 0) return 1;
        if (A[i] + 0 < B[i] + 0) return -1;
    }
    if (n > m) return 1;
    if (n < m) return -1;
    return 0;
}
function cmpcore(a, b,    i) {
    n = split(a, A, "."); m = split(b, B, ".");
    for (i = 1; i <= (n < m ? n : m); i++) {
        if (A[i] + 0 > B[i] + 0) return 1;
        if (A[i] + 0 < B[i] + 0) return -1;
    }
    if (n > m) return 1;
    if (n < m) return -1;
    return 0;
}
BEGIN { FS = "" }
/^[ \t]*"[^"]+": \{/ {
    # start of a plugin entry. Extract plugin name.
    match($0, /"[^"]+": \{/);
    pname = substr($0, RSTART + 1, RLENGTH - 5);
    # collect the entire entry across potentially-multi-line JSON
    rest = $0;
    while (depth_in_value == 0 && rest ~ /\{/) {
        # count open - close braces
        open_count = gsub(/\{/, "{", rest);
        close_count = gsub(/\}/, "}", rest);
        if (open_count <= close_count) break;
        if ((getline nextline) <= 0) break;
        rest = rest "\n" nextline;
    }
    # Now rest is the full plugin object. We need to find each
    # "<version>": { ... "requiredCore": "X", "url": "..." }
    # Best version wins.
    bestv = "";
    besturl = "";
    line = rest;
    while (match(line, /"[^"]+": \{[^{}]*"requiredCore"[^{}]*"url"[^{}]*\}/)) {
        block = substr(line, RSTART, RLENGTH);
        # extract version (the key of this entry)
        if (match(block, /^"[^"]+":/)) {
            v = substr(block, RSTART + 1, RLENGTH - 3);
        }
        # extract requiredCore
        if (match(block, /"requiredCore"[ \t]*:[ \t]*"[^"]+"/)) {
            rc = substr(block, RSTART, RLENGTH);
            sub(/^"requiredCore"[ \t]*:[ \t]*"/, "", rc);
            sub(/"$/, "", rc);
        } else {
            rc = "0";
        }
        # extract url
        if (match(block, /"url"[ \t]*:[ \t]*"[^"]+"/)) {
            u = substr(block, RSTART, RLENGTH);
            sub(/^"url"[ \t]*:[ \t]*"/, "", u);
            sub(/"$/, "", u);
        } else {
            u = "";
        }
        if (cmpcore(rc, target) <= 0 && (bestv == "" || cmpver(v, bestv) > 0)) {
            bestv = v;
            besturl = u;
        }
        line = substr(line, RSTART + RLENGTH);
    }
    if (besturl != "") {
        printf "%s\t%s\n", pname, besturl;
    }
    next
}
' "$TMPDIR/pv.json" > "$TMPDIR/plugin-urls.tsv"

if [ ! -s "$TMPDIR/plugin-urls.tsv" ]; then
    echo "[apex-entry] WARNING: failed to extract plugin URLs"
    exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
fi

ok=0
fail=0
skip=0
fail_list=""
for p in "${PLUGINS[@]}"; do
    if [ -s "$PLUGINS_DIR/${p}.jpi" ]; then
        skip=$((skip+1))
        continue
    fi
    url=$(grep -F -m1 -e "$(printf '\t')" "$TMPDIR/plugin-urls.tsv" | grep -F "^${p}" | cut -f2)
    # Fallback: look up the line and take the second tab-separated field
    if [ -z "$url" ]; then
        url=$(awk -F'\t' -v pn="$p" '$1 == pn { print $2; exit }' "$TMPDIR/plugin-urls.tsv")
    fi
    if [ -z "$url" ]; then
        fail=$((fail+1))
        fail_list="$fail_list $p(no-url)"
        echo "[apex-entry] [FAIL] $p - no compatible version in plugin-versions.json"
        continue
    fi
    rm -f "$PLUGINS_DIR/${p}.jpi"
    if curl -fsSL --max-time 180 -o "$PLUGINS_DIR/${p}.jpi" "$url" 2>/dev/null; then
        magic=$(head -c 2 "$PLUGINS_DIR/${p}.jpi" | od -An -c | tr -d ' \n')
        if [ "$magic" = "PK" ]; then
            ok=$((ok+1))
            echo "[apex-entry] [ok  ] $p"
        else
            fail=$((fail+1))
            fail_list="$fail_list $p(bad-magic)"
            echo "[apex-entry] [BAD ] $p - magic='$magic'"
            rm -f "$PLUGINS_DIR/${p}.jpi"
        fi
    else
        fail=$((fail+1))
        fail_list="$fail_list $p(dl-fail)"
        echo "[apex-entry] [FAIL] $p - download failed from $url"
        rm -f "$PLUGINS_DIR/${p}.jpi"
    fi
done

echo "[apex-entry] Plugin pre-install complete: ok=$ok skip=$skip fail=$fail"
if [ -n "$fail_list" ]; then
    echo "[apex-entry] Failed: $fail_list"
fi
echo "[apex-entry] Files in $PLUGINS_DIR: $(ls -1 "$PLUGINS_DIR" | wc -l)"

exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
