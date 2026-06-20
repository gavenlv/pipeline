#!/bin/bash
# Custom entrypoint for apex-test-jenkins:
#  1) Use jenkins-plugin-cli to install all required plugins with proper
#     dependency resolution (compatible with Jenkins 2.479.3 LTS).
#  2) Hand off to the official jenkins docker-entrypoint.sh

set +e

PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"

PLUGINS=(
    workflow-aggregator
    pipeline-groovy-lib
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
    workflow-cps
    workflow-job
    workflow-multibranch
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
    timestamper
    ws-cleanup
    ant
    mailer
    durable-task
    configuration-as-code
)

mkdir -p "$PLUGINS_DIR"

# Only install plugins if not already done (idempotent)
ALREADY_INSTALLED=$(ls -1 "$PLUGINS_DIR"/*.jpi 2>/dev/null | wc -l)
if [ "$ALREADY_INSTALLED" -gt 30 ]; then
    echo "[apex-entry] $ALREADY_INSTALLED plugins already present, skipping install"
else
    echo "[apex-entry] Installing ${#PLUGINS[@]} plugins via jenkins-plugin-cli..."

    # Create plugins.txt
    PLUGIN_FILE="/tmp/apex-plugins.txt"
    : > "$PLUGIN_FILE"
    for p in "${PLUGINS[@]}"; do
        echo "$p" >> "$PLUGIN_FILE"
    done

    # Use jenkins-plugin-cli with default update center (compatible with 2.479.3)
    jenkins-plugin-cli --plugin-file "$PLUGIN_FILE" --skip-failed-plugins 2>&1
    echo "[apex-entry] Plugin install complete. Files in $PLUGINS_DIR: $(ls -1 "$PLUGINS_DIR"/*.jpi 2>/dev/null | wc -l)"
fi

exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
