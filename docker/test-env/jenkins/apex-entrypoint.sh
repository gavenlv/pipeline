#!/bin/bash
# Custom entrypoint for apex-test-jenkins:
#  1) Pre-stage all required plugin .hpi files into /var/jenkins_home/plugins/
#     by reading a pre-resolved TSV file (plugin -> download URL) that was
#     generated on the host by resolve-plugins.sh using the canonical
#     plugin-versions.json history.
#  2) Hand off to the official jenkins docker-entrypoint.sh
#
# The TSV at /var/jenkins_home/casc/plugin-urls.tsv is mounted read-only
# from the host. This is more reliable than the UC's update-center.json,
# which is filtered for the current LTS and only returns versions
# compatible with the latest stable.

set +e

JENKINS_VERSION="${JENKINS_VERSION:-2.462.3}"
PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"
PLUGIN_URLS_FILE="${PLUGIN_URLS_FILE:-/var/jenkins_home/casc/plugin-urls.tsv}"

PLUGINS=(
    pipeline-groovy-lib
    workflow-aggregator
    workflow-cps
    workflow-job
    workflow-multibranch
    pipeline-stage-step
    pipeline-utility-steps
    git
    credentials
    credentials-binding
    plain-credentials
    script-security
    scm-api
    structs
    ssh-credentials
    display-url-api
    trilead-api
    sshd
    instance-identity
    workflow-step-api
    workflow-api
    workflow-scm-step
    workflow-support
    pipeline-input-step
    pipeline-model-definition
    pipeline-model-extensions
    token-macro
    command-launcher
    pipeline-graph-analysis
    git-client
    cloudbees-folder
    jackson2-api
    snakeyaml-api
)

mkdir -p "$PLUGINS_DIR"
mkdir -p /var/jenkins_home/casc

if [ ! -s "$PLUGIN_URLS_FILE" ]; then
    echo "[apex-entry] WARNING: $PLUGIN_URLS_FILE missing or empty; cannot pre-install plugins"
    echo "[apex-entry] Run 'bash docker/test-env/jenkins/resolve-plugins.sh' on the host first"
    exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
fi

echo "[apex-entry] Pre-installing ${#PLUGINS[@]} plugins into $PLUGINS_DIR (target Jenkins: $JENKINS_VERSION)..."

ok=0
fail=0
skip=0
fail_list=""
for p in "${PLUGINS[@]}"; do
    if [ -s "$PLUGINS_DIR/${p}.jpi" ]; then
        skip=$((skip+1))
        continue
    fi
    url=$(awk -F'\t' -v pn="$p" '$1 == pn { print $2; exit }' "$PLUGIN_URLS_FILE")
    if [ -z "$url" ]; then
        fail=$((fail+1))
        fail_list="$fail_list $p(no-url)"
        echo "[apex-entry] [FAIL] $p - not in plugin-urls.tsv"
        continue
    fi
    rm -f "$PLUGINS_DIR/${p}.jpi"
    download_ok=0
    for attempt in 1 2 3; do
        if curl -fsSL --connect-timeout 10 --max-time 10 -o "$PLUGINS_DIR/${p}.jpi" "$url" 2>/dev/null; then
            magic=$(head -c 2 "$PLUGINS_DIR/${p}.jpi" | od -An -c | tr -d ' \n')
            if [ "$magic" = "PK" ]; then
                download_ok=1
                break
            else
                rm -f "$PLUGINS_DIR/${p}.jpi"
            fi
        fi
        sleep 2
    done
    if [ $download_ok -eq 1 ]; then
        ok=$((ok+1))
        echo "[apex-entry] [ok  ] $p"
    else
        fail=$((fail+1))
        fail_list="$fail_list $p(dl-fail)"
        echo "[apex-entry] [FAIL] $p - download failed from $url (will start Jenkins anyway)"
    fi
done

echo "[apex-entry] Plugin pre-install complete: ok=$ok skip=$skip fail=$fail"
if [ -n "$fail_list" ]; then
    echo "[apex-entry] Failed: $fail_list"
fi
echo "[apex-entry] Files in $PLUGINS_DIR: $(ls -1 "$PLUGINS_DIR" | wc -l)"

exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
