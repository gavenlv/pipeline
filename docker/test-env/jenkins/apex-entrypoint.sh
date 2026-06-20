#!/bin/bash
# Custom entrypoint for apex-test-jenkins.
# - Installs required build tools (maven, node/npm, go, python3) on first run
# - Plugins are pre-installed in the volume. Just start Jenkins directly.

set -e

PLUGINS_DIR="${PLUGINS_DIR:-/var/jenkins_home/plugins}"
PLUGIN_COUNT=$(ls -1 "$PLUGINS_DIR"/*.jpi 2>/dev/null | wc -l)
echo "[apex-entry] Found $PLUGIN_COUNT pre-installed plugins in $PLUGINS_DIR"

if [ "$PLUGIN_COUNT" -eq 0 ]; then
    echo "[apex-entry] WARNING: No plugins found! Jenkins will start without workflow plugins."
    echo "[apex-entry] Pre-install plugins into the volume before starting."
fi

# ---------------------------------------------------------------
# Install build tools required by the per-scenario Jenkinsfiles
# (apex-build-java, apex-parallel-build, apex-mixed). These are
# installed at container start so the host image stays slim and
# we don't need a custom base image. Skipped if already present.
# ---------------------------------------------------------------
if command -v mvn >/dev/null 2>&1 \
    && command -v node >/dev/null 2>&1 \
    && command -v npm >/dev/null 2>&1 \
    && command -v go >/dev/null 2>&1 \
    && command -v python3 >/dev/null 2>&1; then
    echo "[apex-entry] All build tools present, skipping apt install"
else
    echo "[apex-entry] Installing build tools (maven, nodejs, golang, python3)..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y --no-install-recommends \
        maven \
        nodejs \
        npm \
        golang \
        python3 \
        python3-pip \
        python3-venv
    rm -rf /var/lib/apt/lists/*
    echo "[apex-entry] Build tools installed"
fi

# ---------------------------------------------------------------
# Pre-compile apex-ci-library src/ into /var/jenkins_home/libs-classes/
# so the Jenkinsfile can `import com.hsbc.treasury.apex.ci.*`.
# We use the groovy that ships with the pipeline-groovy-lib plugin
# (located under JENKINS_HOME/war/WEB-INF/lib/groovy-*.jar).
# ---------------------------------------------------------------
LIB_CLASSES_DIR="/var/jenkins_home/libs-classes"
if [ ! -d "$LIB_CLASSES_DIR/com/hsbc/treasury/apex/ci" ]; then
    echo "[apex-entry] Pre-compiling apex-ci-library src/ to $LIB_CLASSES_DIR..."
    rm -rf "$LIB_CLASSES_DIR"
    mkdir -p "$LIB_CLASSES_DIR"
    GROOVY_JAR=$(find /var/jenkins_home/war/WEB-INF/lib -name 'groovy-*.jar' | head -1)
    if [ -z "$GROOVY_JAR" ] || [ ! -f "$GROOVY_JAR" ]; then
        echo "[apex-entry] WARNING: Groovy jar not found in WEB-INF/lib; class pre-compilation skipped"
    else
        # copy src tree into the libs-classes dir (preserves package layout)
        if [ -d /var/jenkins_home/casc_libs/apex-ci-library/src ]; then
            cp -r /var/jenkins_home/casc_libs/apex-ci-library/src/. "$LIB_CLASSES_DIR/" || true
        fi
        # find every .groovy file (including generated $... closures) and compile
        SRCS=$(find "$LIB_CLASSES_DIR" -name '*.groovy' 2>/dev/null)
        if [ -n "$SRCS" ]; then
            java -cp "$GROOVY_JAR" org.codehaus.groovy.tools.FileSystemCompiler \
                -d "$LIB_CLASSES_DIR" $SRCS 2>&1 | tail -20 || \
                echo "[apex-entry] Pre-compile reported errors (continuing)"
        fi
        # Count compiled classes
        NCLASS=$(find "$LIB_CLASSES_DIR" -name '*.class' 2>/dev/null | wc -l)
        echo "[apex-entry] Pre-compiled $NCLASS class files"
    fi
else
    echo "[apex-entry] $LIB_CLASSES_DIR already has compiled classes; skip"
fi

# ---------------------------------------------------------------
# Sync host-mounted files into the library git repo and commit.
# The host mounts vars/, src/, resources/ over the library's working
# tree (read-write so we can commit). Jenkins reads the library from
# the *committed* git state, so any uncommitted/untracked file would
# be invisible to jobs. We make a single sync commit before Jenkins
# starts; further edits happen on the host and the user reruns.
# ---------------------------------------------------------------
LIB_REPO="/var/jenkins_home/casc_libs/apex-ci-library"
if [ -d "$LIB_REPO/.git" ]; then
    cd "$LIB_REPO"
    git config user.email  "apex@local"  >/dev/null 2>&1 || true
    git config user.name   "apex"        >/dev/null 2>&1 || true
    # Pull latest from host working tree, allow deletions
    git add -A
    if ! git diff --cached --quiet 2>/dev/null; then
        git commit -m "sync from host mount $(date -u +%FT%TZ)" >/dev/null 2>&1 \
            && echo "[apex-entry] Synced host files into library repo ($(git rev-parse --short HEAD))" \
            || echo "[apex-entry] WARNING: failed to commit sync into library repo"
    else
        echo "[apex-entry] Library repo already in sync with host ($(git rev-parse --short HEAD))"
    fi
    cd - >/dev/null 2>&1 || true
else
    echo "[apex-entry] WARNING: $LIB_REPO is not a git repo, library sync skipped"
fi

echo "[apex-entry] Starting Jenkins..."
exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"
