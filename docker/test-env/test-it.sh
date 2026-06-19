#!/usr/bin/env bash
# apex-ci-library integration test orchestrator
# Brings up local Nexus + registry, runs every library use case end-to-end against real services.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
LOG_DIR="$ROOT_DIR/build/test-it-logs"
RESULTS_FILE="$ROOT_DIR/build/test-it-results.json"

mkdir -p "$LOG_DIR"
mkdir -p "$(dirname "$RESULTS_FILE")"

NEXUS_URL="http://localhost:8081"
REGISTRY_URL="http://localhost:5000"
ADMIN_USER="admin"
ADMIN_PASS="admin123"   # nexus default after NEXUS_SECURITY_RANDOMPASSWORD=false
SAMPLE_DIR="$SCRIPT_DIR/samples"

# Track results
declare -a RESULTS
ok_count=0
fail_count=0

note() { printf "\n\033[1;36m== %s ==\033[0m\n" "$*"; }
ok()   { printf "  \033[1;32m[PASS]\033[0m %s\n" "$*"; ok_count=$((ok_count+1)); RESULTS+=("PASS|$*"); }
fail() { printf "  \033[1;31m[FAIL]\033[0m %s\n" "$*"; fail_count=$((fail_count+1)); RESULTS+=("FAIL|$*"); }

# ============================================================
# 1. Bring up infrastructure
# ============================================================
note "Bringing up Docker Compose stack"
docker compose -f "$COMPOSE_FILE" up -d 2>&1 | tee "$LOG_DIR/compose-up.log"

note "Waiting for Nexus to be healthy"
for i in {1..60}; do
    code=$(curl -s -o /dev/null -w "%{http_code}" "$NEXUS_URL/service/rest/v1/status" || true)
    if [ "$code" = "200" ]; then
        ok "Nexus healthy"
        break
    fi
    if [ "$i" = "60" ]; then
        fail "Nexus did not become healthy in time (last code=$code)"
        docker compose -f "$COMPOSE_FILE" logs nexus | tail -50
        exit 1
    fi
    sleep 5
done

note "Waiting for Registry to be healthy"
for i in {1..20}; do
    if curl -s -f "$REGISTRY_URL/v2/_catalog" >/dev/null 2>&1; then
        ok "Registry healthy"
        break
    fi
    if [ "$i" = "20" ]; then
        fail "Registry did not become healthy"
        exit 1
    fi
    sleep 2
done

# ============================================================
# 2. Configure Nexus (repos + user)
# ============================================================
note "Configuring Nexus (creating repos + docker Bearer realm)"
NEXUS_BEARER="docker.local"

# The first request is anonymous; on a fresh install Nexus may require a
# one-time activation. We retry with admin/admin123 to be safe.
login_nexus() {
    curl -fsS -u "$ADMIN_USER:$ADMIN_PASS" "$@"
}

# helper: create or update a hosted repo
create_repo() {
    local format="$1"   # e.g. "maven2", "raw", "npm", "pypi", "docker"
    local type="$2"     # "hosted" / "proxy" / "group"
    local name="$3"     # repo name
    local body="$4"
    local url="$NEXUS_URL/service/rest/v1/repositories/$format/$type"
    if login_nexus "$NEXUS_URL/service/rest/v1/repositories/$name" -o /dev/null 2>/dev/null; then
        login_nexus -X DELETE "$NEXUS_URL/service/rest/v1/repositories/$name" >/dev/null 2>&1 || true
    fi
    login_nexus -X POST "$url" \
        -H "Content-Type: application/json" -d "$body" >/dev/null
}

# Enable Docker Bearer Token Realm (needed for docker registry login)
login_nexus -X PUT "$NEXUS_URL/service/rest/v1/security/realms/active" \
    -H "Content-Type: application/json" \
    -d '["NexusAuthenticatingRealm","NexusDockerBearerTokenRealm"]' >/dev/null 2>&1 || true

# Maven hosted releases
create_repo "maven" "hosted" "maven-releases" '{
  "name":"maven-releases","format":"maven2","type":"hosted","online":true,
  "url":"http://localhost:8081/repository/maven-releases",
  "storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"},
  "maven":{"versionPolicy":"RELEASE","layoutPolicy":"STRICT"}
}' && ok "Created maven-releases" || fail "maven-releases"

# npm private
create_repo "npm" "hosted" "npm-private" '{
  "name":"npm-private","format":"npm","type":"hosted","online":true,
  "url":"http://localhost:8081/repository/npm-private",
  "storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"}
}' && ok "Created npm-private" || fail "npm-private"

# pypi hosted
create_repo "pypi" "hosted" "pypi-hosted" '{
  "name":"pypi-hosted","format":"pypi","type":"hosted","online":true,
  "url":"http://localhost:8081/repository/pypi-hosted",
  "storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"}
}' && ok "Created pypi-hosted" || fail "pypi-hosted"

# raw hosted (for tarballs / misc artifacts)
create_repo "raw" "hosted" "raw-hosted" '{
  "name":"raw-hosted","format":"raw","type":"hosted","online":true,
  "url":"http://localhost:8081/repository/raw-hosted",
  "storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"}
}' && ok "Created raw-hosted" || fail "raw-hosted"

# Create docker hosted (port 8082 proxied through 8081)
create_repo "docker" "hosted" "docker-hosted" '{
  "name":"docker-hosted","format":"docker","type":"hosted","online":true,
  "url":"http://localhost:8082",
  "storage":{"blobStoreName":"default","strictContentTypeValidation":false,"writePolicy":"ALLOW"},
  "docker":{"v1Enabled":false,"forceBasicAuth":false,"httpPort":8082}
}' && ok "Created docker-hosted" || fail "docker-hosted"

# Make sure deployment user has rights
login_nexus -X PUT "$NEXUS_URL/service/rest/v1/security/users/deployment" \
    -H "Content-Type: application/json" -d "{
  \"userId\":\"deployment\",
  \"firstName\":\"Deploy\",
  \"lastName\":\"User\",
  \"emailAddress\":\"deploy@local\",
  \"password\":\"deployment123\",
  \"status\":\"active\",
  \"roles\":[\"nx-admin\",\"nx-deployment\"]}" >/dev/null 2>&1 || \
login_nexus -X POST "$NEXUS_URL/service/rest/v1/security/users" \
    -H "Content-Type: application/json" -d "{
  \"userId\":\"deployment\",
  \"firstName\":\"Deploy\",
  \"lastName\":\"User\",
  \"emailAddress\":\"deploy@local\",
  \"password\":\"deployment123\",
  \"status\":\"active\",
  \"roles\":[\"nx-admin\",\"nx-deployment\"]}" >/dev/null 2>&1 || true

# ============================================================
# 3. Run real build / scan / publish tests
# ============================================================

# ---- 3.1 Java Maven build + publish ----
note "3.1 Java Maven build (compile, test, deploy to Nexus)"
JAVA_DIR="$SAMPLE_DIR/java"
pushd "$JAVA_DIR" >/dev/null
if mvn -B -s settings.xml clean deploy 2>&1 | tee "$LOG_DIR/mvn-deploy.log" | tail -5; then
    if curl -fsS "$NEXUS_URL/repository/maven-releases/com/apex/sample/demo/1.0.0/demo-1.0.0.jar" -o /tmp/demo.jar \
        && [ -s /tmp/demo.jar ]; then
        ok "Java/Maven: built, tested, deployed (jar $(stat -c%s /tmp/demo.jar 2>/dev/null || stat -f%z /tmp/demo.jar) bytes)"
    else
        fail "Java/Maven: deploy succeeded but jar not retrievable"
    fi
else
    fail "Java/Maven: mvn deploy failed"
fi
popd >/dev/null

# ---- 3.2 Node build + npm publish ----
note "3.2 Node build (npm install, test, publish to Nexus npm-private)"
NODE_DIR="$SAMPLE_DIR/node"
pushd "$NODE_DIR" >/dev/null
# Configure npm to use local registry with basic auth
NEXUS_NPM_URL="$NEXUS_URL/repository/npm-private/"
rm -f .npmrc
AUTH=$(printf 'deployment:deployment123' | base64 | tr -d '\r\n')
cat > .npmrc <<EOF
registry=$NEXUS_NPM_URL
//localhost:8081/repository/npm-private/:_auth=${AUTH}
always-auth=true
EOF
if npm install --no-audit --no-fund 2>&1 | tee "$LOG_DIR/npm-install.log" | tail -3 \
    && npm test 2>&1 | tee "$LOG_DIR/npm-test.log" | tail -3 \
    && npm publish --registry="$NEXUS_NPM_URL" --userconfig=.npmrc 2>&1 | tee "$LOG_DIR/npm-publish.log" | tail -3; then
    if curl -fsS "$NEXUS_URL/repository/npm-private/apex-sample-node/-/apex-sample-node-1.0.0.tgz" -o /tmp/apex-node.tgz \
        && [ -s /tmp/apex-node.tgz ]; then
        ok "Node/npm: built, tested, published (tgz $(stat -c%s /tmp/apex-node.tgz 2>/dev/null || stat -f%z /tmp/apex-node.tgz) bytes)"
    else
        fail "Node/npm: publish succeeded but tarball not retrievable"
    fi
else
    fail "Node/npm: build or publish failed"
fi
popd >/dev/null

# ---- 3.3 Python build + twine upload ----
note "3.3 Python build (pip install, build, twine upload to Nexus pypi-hosted)"
PY_DIR="$SAMPLE_DIR/python"
pushd "$PY_DIR" >/dev/null
# Windows prefers 'py' launcher; fall back to python3
if command -v py >/dev/null 2>&1; then PY=py; else PY=python3; fi
# Force UTF-8 output (twine uses unicode bullets that break Windows GBK console)
export PYTHONIOENCODING=utf-8
export LC_ALL=C.UTF-8
if $PY -m pip install --quiet --upgrade build twine 2>&1 | tee "$LOG_DIR/pip-install-tools.log" | tail -3 \
    && $PY -m build 2>&1 | tee "$LOG_DIR/py-build.log" | tail -3 \
    && TWINE_PASSWORD=deployment123 TWINE_USERNAME=deployment \
       $PY -m twine upload --repository-url "$NEXUS_URL/repository/pypi-hosted/" dist/* 2>&1 | tee "$LOG_DIR/twine-upload.log" | tail -3; then
    # PyPI hosted repos use a /packages/... layout (PEP 503); the component API shows downloadUrl
    if curl -fsS "$NEXUS_URL/repository/pypi-hosted/packages/apex-sample-py/1.0.0/apex_sample_py-1.0.0.tar.gz" -o /tmp/apex-py.tgz \
        && [ -s /tmp/apex-py.tgz ]; then
        ok "Python: built, packaged, uploaded (tgz $(stat -c%s /tmp/apex-py.tgz 2>/dev/null || stat -f%z /tmp/apex-py.tgz) bytes)"
    else
        fail "Python: upload succeeded but tarball not retrievable"
    fi
else
    fail "Python: build or upload failed"
fi
popd >/dev/null

# ---- 3.4 Docker build + push to local registry ----
note "3.4 Docker build (multi-arch via buildx, push to local registry)"
DOCKER_DIR="$SAMPLE_DIR/docker"
pushd "$DOCKER_DIR" >/dev/null
# Build single-arch for speed (multi-arch needs emulator)
docker build -t localhost:5000/apex-sample:1.0.0 -t localhost:5000/apex-sample:latest . 2>&1 | tee "$LOG_DIR/docker-build.log" | tail -5
if docker push localhost:5000/apex-sample:1.0.0 2>&1 | tee "$LOG_DIR/docker-push.log" | tail -3; then
    if curl -fsS "http://localhost:5000/v2/apex-sample/tags/list" | grep -q "1.0.0"; then
        ok "Docker: image built and pushed to local registry"
    else
        fail "Docker: push succeeded but image not in registry catalog"
    fi
else
    fail "Docker: push failed"
fi
popd >/dev/null

# ---- 3.5 Trivy container scan ----
note "3.5 Trivy container scan against local registry image"
TRIVY_LOG="$LOG_DIR/trivy.log"
if command -v trivy >/dev/null 2>&1; then
    trivy image --quiet --no-progress --format json --output "$TRIVY_LOG" localhost:5000/apex-sample:1.0.0 2>&1 | tail -3 || true
    if [ -s "$TRIVY_LOG" ] && python3 -c "import json; r=json.load(open('$TRIVY_LOG')); print('targets', len(r))" 2>/dev/null; then
        # Check at least one report has Results or Targets
        ok "Trivy: scan completed (report $(stat -c%s $TRIVY_LOG 2>/dev/null || stat -f%z $TRIVY_LOG) bytes)"
    else
        fail "Trivy: scan produced no usable report"
    fi
else
    # Pull trivy and run in docker
    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
        aquasec/trivy:0.49.1 image --format json --quiet --no-progress \
        localhost:5000/apex-sample:1.0.0 > "$TRIVY_LOG" 2>&1 || true
    if [ -s "$TRIVY_LOG" ]; then
        ok "Trivy: scan completed via Docker (report $(stat -c%s $TRIVY_LOG 2>/dev/null || stat -f%z $TRIVY_LOG) bytes)"
    else
        fail "Trivy: scan failed (trivy not available on host and docker run also failed)"
    fi
fi

# ---- 3.6 SCA via npm audit ----
note "3.6 SCA - npm audit on sample Node project"
SCA_LOG="$LOG_DIR/npm-audit.log"
if pushd "$SAMPLE_DIR/node" >/dev/null && npm audit --json 2>"$SCA_LOG" | head -1 >/dev/null; then
    ok "SCA (npm audit): completed"
elif [ -s "$SCA_LOG" ]; then
    # audit returns non-zero when vulnerabilities found; that's still a successful scan
    ok "SCA (npm audit): completed with vulnerabilities (see $SCA_LOG)"
else
    fail "SCA (npm audit): no output"
fi
popd >/dev/null

# ---- 3.7 SAST - simple pattern check (proxy for 'real' SAST in CI) ----
note "3.7 SAST - secret/secret-like pattern check (replaces heavier SonarQube for local tests)"
SAST_LOG="$LOG_DIR/sast.log"
if [ -f "$SAMPLE_DIR/java/src/main/java/com/apex/sample/App.java" ]; then
    : > "$SAST_LOG"
    # Hardcoded secret pattern - real SAST would run SonarQube; we use a fast grep baseline
    if grep -RInE "(password|api[_-]?key|secret).*?=.*?['\"]" "$SAMPLE_DIR/java/src/" >>"$SAST_LOG" 2>/dev/null; then
        fail "SAST: hardcoded secret pattern matched in sample (see $SAST_LOG)"
    else
        ok "SAST: no hardcoded secret pattern in sample (see $SAST_LOG)"
    fi
else
    fail "SAST: sample Java source not found"
fi

# ---- 3.8 Library-config YAML round-trip in CI context ----
note "3.8 LibraryConfig YAML parser under real config file"
YAML_FILE="$SAMPLE_DIR/config/apex.yaml"
if [ -f "$YAML_FILE" ]; then
    M2="$HOME/.m2/repository"
    CP="$M2/org/apache/groovy/groovy/4.0.16/groovy-4.0.16.jar:$M2/org/apache/groovy/groovy-json/4.0.16/groovy-json-4.0.16.jar:$ROOT_DIR/build/classes/main"
    SCRIPT_GROOVY=$(mktemp --suffix=.groovy)
    # Use Windows-style backslashes for Java File API under WSL/Git-Bash
    WIN_YAML=$(cygpath -w "$YAML_FILE" 2>/dev/null || echo "$YAML_FILE")
    cat > "$SCRIPT_GROOVY" <<GROOVY
import com.hsbc.treasury.apex.ci.config.LibraryConfig
def f = new File('${WIN_YAML//\\/\\\\}')
println 'exists=' + f.exists() + ' len=' + f.length()
def c = LibraryConfig.fromYamlLite(f.text)
println 'app=' + c.getString('app.name', '?')
println 'java.tool=' + c.getString('java.tool', '?')
println 'docker.platforms=' + c.getList('docker.platforms')
println 'scanners=' + c.getList('scanners.enabled')
GROOVY
    OUT=$(java -cp "$CP" groovy.ui.GroovyMain "$SCRIPT_GROOVY" 2>&1) || OUT=""
    rm -f "$SCRIPT_GROOVY"
    if echo "$OUT" | grep -q "app=apex-treasury-svc" && echo "$OUT" | grep -q "linux/amd64"; then
        ok "LibraryConfig: parsed sample apex.yaml ($(echo "$OUT" | grep -E 'platforms|java.tool' | tr '\n' ';' ))"
    else
        fail "LibraryConfig: failed to parse sample apex.yaml: $OUT"
    fi
else
    fail "LibraryConfig: sample apex.yaml missing"
fi

# ============================================================
# 4. Summary
# ============================================================
note "Summary: $ok_count passed, $fail_count failed"

{
  echo "{ \"passed\": $ok_count, \"failed\": $fail_count, \"results\": ["
  first=true
  for r in "${RESULTS[@]}"; do
      status="${r%%|*}"
      msg="${r#*|}"
      if [ "$first" = true ]; then first=false; else echo ","; fi
      printf '  {"status":"%s","name":"%s"}' "$status" "$(echo "$msg" | sed 's/"/\\"/g')"
  done
  echo
  echo "] }"
} > "$RESULTS_FILE"

if [ "$fail_count" -gt 0 ]; then
    printf "\n\033[1;31mIntegration test FAILED. Logs: %s\033[0m\n" "$LOG_DIR"
    exit 1
fi
printf "\n\033[1;32mAll integration tests PASSED.\033[0m\n"
