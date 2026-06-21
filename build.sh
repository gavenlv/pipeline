#!/usr/bin/env bash
# apex-ci-library build script
# 兼容 Linux / macOS / Git Bash on Windows
#
# 2026-06: 改造成 Maven 项目后，此脚本仅作为便捷入口。
# 真实构建逻辑全部由 pom.xml + gmavenplus + surefire 承担。

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ============================================================
# 解析参数
# ============================================================
MVN_GOAL="test"
MVN_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        -skipTests|--skip-tests)
            MVN_ARGS+=("-DskipTests")
            shift
            ;;
        -clean|--clean)
            MVN_GOAL="clean"
            shift
            ;;
        -package|-pkg|--package)
            MVN_GOAL="package"
            shift
            ;;
        -verify|--verify)
            MVN_GOAL="verify"
            shift
            ;;
        -compile|--compile)
            MVN_GOAL="compile"
            shift
            ;;
        *)
            MVN_ARGS+=("$1")
            shift
            ;;
    esac
done

# ============================================================
# 检测 Maven
# ============================================================
if [ -z "${MVN:-}" ]; then
    if command -v mvn >/dev/null 2>&1; then
        MVN="mvn"
    elif [ -x "./mvnw" ]; then
        MVN="./mvnw"
    else
        echo "[ERROR] Maven not found. Please install Maven 3.6+ or run './mvnw'."
        echo "Download: https://maven.apache.org/download.cgi"
        exit 1
    fi
fi

# ============================================================
# 执行 Maven
# ============================================================
echo "==> Running: $MVN $MVN_GOAL ${MVN_ARGS[*]}"
"$MVN" -B -ntp "$MVN_GOAL" "${MVN_ARGS[@]}"

echo "==> Build complete."
echo "    JAR     : target/apex-ci-library-*.jar"
echo "    Reports : target/surefire-reports/"
