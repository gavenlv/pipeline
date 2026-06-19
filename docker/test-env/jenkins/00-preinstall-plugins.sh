#!/usr/bin/env bash
# 00-preinstall-plugins.sh - runs from the Jenkins image's docker-entrypoint hook.
# Pre-stages the .hpi files for all required plugins by reading the
# update-center manifest for the running Jenkins version.
#
# Hook path: /usr/share/jenkins/ref/init.groovy.d/ (also runs after the
# entrypoint copies /usr/share/jenkins/ref to /var/jenkins_home).
# We place this in a pre-install hook via the docker-entrypoint mechanism.

set -e
JENKINS_VERSION="${JENKINS_VERSION:-2.504.1}"
PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"
UC_URL="https://updates.jenkins.io/update-center.json?version=${JENKINS_VERSION}"
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
)

mkdir -p "$PLUGINS_DIR"

# Fetch the update-center manifest once
echo "[preinstall] Fetching update-center manifest for Jenkins ${JENKINS_VERSION}..."
curl -fsSL "$UC_URL" -o "$TMPDIR/uc.json" || { echo "[preinstall] Failed to fetch UC manifest"; exit 0; }

# Strip the leading JS comment wrapper
sed -i '1s/^updateCenter.post(//; $s/);$//' "$TMPDIR/uc.json"

# For each plugin, find its version + URL and download the .hpi
for p in "${PLUGINS[@]}"; do
    if [ -f "$PLUGINS_DIR/${p}.jpi" ] || [ -f "$PLUGINS_DIR/${p}.hpi" ]; then
        echo "[preinstall] [skip] ${p} already present"
        continue
    fi
    info=$(python3 -c "
import json,sys
uc=json.load(open('$TMPDIR/uc.json'))
plugins=uc['plugins']
p=plugins.get('$p',{})
print(p.get('version',''), p.get('url',''))
" 2>/dev/null || echo "")
    ver=$(echo "$info" | awk '{print $1}')
    url=$(echo "$info" | awk '{print $2}')
    if [ -z "$ver" ] || [ -z "$url" ]; then
        echo "[preinstall] [skip] ${p}: not in UC (version=${ver})"
        continue
    fi
    echo "[preinstall] [get ] ${p} v${ver}"
    if curl -fsSL "$url" -o "$PLUGINS_DIR/${p}.jpi"; then
        echo "[preinstall] [ok  ] ${p} v${ver}"
    else
        echo "[preinstall] [FAIL] ${p} v${ver}"
    fi
done

echo "[preinstall] Plugin pre-staging complete. Listed:"
ls -1 "$PLUGINS_DIR" | sed 's/^/  /'
