#!/usr/bin/env bash
# apex-ci-library build script
# 兼容 Linux / macOS / Git Bash on Windows

set -e

# ============================================================
# 路径 / 工具解析
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 优先用环境变量，否则从 ~/.m2/repository 找
M2="${M2_REPO:-$HOME/.m2/repository}"

GROOVY_VER="${GROOVY_VER:-4.0.16}"
JUNIT_VER="${JUNIT_VER:-4.12}"
HAMCREST_VER="${HAMCREST_VER:-1.3}"

GROOVY_JAR="$M2/org/apache/groovy/groovy/$GROOVY_VER/groovy-$GROOVY_VER.jar"
GROOVY_JSON_JAR="$M2/org/apache/groovy/groovy-json/$GROOVY_VER/groovy-json-$GROOVY_VER.jar"
JUNIT_JAR="$M2/junit/junit/$JUNIT_VER/junit-$JUNIT_VER.jar"
HAMCREST_JAR="$M2/org/hamcrest/hamcrest-core/$HAMCREST_VER/hamcrest-core-$HAMCREST_VER.jar"

if [ ! -f "$GROOVY_JAR" ]; then
    echo "[ERROR] Groovy not found: $GROOVY_JAR"
    echo "Set GROOVY_VER or place the jar at the expected location."
    exit 1
fi
if [ ! -f "$JUNIT_JAR" ]; then
    echo "[ERROR] JUnit not found: $JUNIT_JAR"
    exit 1
fi
if [ ! -f "$HAMCREST_JAR" ]; then
    echo "[ERROR] Hamcrest not found: $HAMCREST_JAR"
    exit 1
fi

# 编译/运行 classpath 装配
CP="$GROOVY_JAR"
if [ -f "$GROOVY_JSON_JAR" ]; then
    CP="$CP:$GROOVY_JSON_JAR"
fi

# ============================================================
# 清理与准备
# ============================================================
BUILD_DIR="$SCRIPT_DIR/build"
SRC_CLASSES="$BUILD_DIR/classes/main"
TEST_CLASSES="$BUILD_DIR/classes/test"
rm -rf "$SRC_CLASSES" "$TEST_CLASSES"
mkdir -p "$SRC_CLASSES" "$TEST_CLASSES"

# ============================================================
# 编译主源码
# ============================================================
echo "==> Compiling main sources..."
MAIN_SRC_FILES=$(find src -name "*.groovy" 2>/dev/null)
if [ -z "$MAIN_SRC_FILES" ]; then
    echo "  (no main sources)"
else
    java -Xmx512m -cp "$CP" org.codehaus.groovy.tools.FileSystemCompiler \
        -d "$SRC_CLASSES" \
        $MAIN_SRC_FILES
fi

# 复制 vars/ 和 resources/ 到 classes（vars 编译后仍是 groovy 脚本，运行时由 Jenkins 编译）
cp -r vars/. "$SRC_CLASSES/vars/" 2>/dev/null || true
cp -r resources/. "$SRC_CLASSES/" 2>/dev/null || true

# ============================================================
# 编译测试
# ============================================================
echo "==> Compiling tests..."
TEST_SRC_FILES=$(find test -name "*.groovy" 2>/dev/null)
if [ -z "$TEST_SRC_FILES" ]; then
    echo "  (no tests)"
else
    java -Xmx512m -cp "$CP:$JUNIT_JAR:$HAMCREST_JAR:$SRC_CLASSES" \
        org.codehaus.groovy.tools.FileSystemCompiler \
        -d "$TEST_CLASSES" \
        -cp "$JUNIT_JAR:$HAMCREST_JAR:$SRC_CLASSES" \
        $TEST_SRC_FILES
fi

# ============================================================
# 收集测试类
# ============================================================
TEST_CLASSES_LIST=""
while IFS= read -r f; do
    cls=$(echo "$f" | sed -e 's|^test/||' -e 's|\.groovy$||' -e 's|/|.|g')
    TEST_CLASSES_LIST="$TEST_CLASSES_LIST $cls"
done < <(find test -name "*Test.groovy" 2>/dev/null)

if [ -z "$TEST_CLASSES_LIST" ]; then
    echo "==> No tests found. Done."
    exit 0
fi

# ============================================================
# 运行测试
# ============================================================
echo "==> Running tests..."
java -Xmx512m -cp "$CP:$JUNIT_JAR:$HAMCREST_JAR:$SRC_CLASSES:$TEST_CLASSES" \
    org.junit.runner.JUnitCore $TEST_CLASSES_LIST

echo "==> Build complete."
