@echo off
REM apex-ci-library build script (Windows native / cmd.exe)
REM 2026-06: 改造成 Maven 项目后，此脚本仅作为便捷入口。
REM 真实构建逻辑全部由 pom.xml + gmavenplus + surefire 承担。

setlocal enableextensions enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

REM ============================================================
REM 解析参数
REM ============================================================
set "MVN_GOAL=test"
set "MVN_EXTRA_ARGS="
:parse_args
if "%~1"=="" goto :after_parse
if /I "%~1"=="-skipTests" (
    set "MVN_EXTRA_ARGS=%MVN_EXTRA_ARGS% -DskipTests"
    shift
    goto :parse_args
)
if /I "%~1"=="--skip-tests" (
    set "MVN_EXTRA_ARGS=%MVN_EXTRA_ARGS% -DskipTests"
    shift
    goto :parse_args
)
if /I "%~1"=="-clean" goto :set_clean
if /I "%~1"=="--clean" goto :set_clean
if /I "%~1"=="-package" goto :set_package
if /I "%~1"=="--package" goto :set_package
if /I "%~1"=="-verify" goto :set_verify
if /I "%~1"=="--verify" goto :set_verify
if /I "%~1"=="-compile" goto :set_compile
if /I "%~1"=="--compile" goto :set_compile
REM 透传其他参数
set "MVN_EXTRA_ARGS=%MVN_EXTRA_ARGS% %~1"
shift
goto :parse_args

:set_clean
set "MVN_GOAL=clean"
shift
goto :parse_args
:set_package
set "MVN_GOAL=package"
shift
goto :parse_args
:set_verify
set "MVN_GOAL=verify"
shift
goto :parse_args
:set_compile
set "MVN_GOAL=compile"
shift
goto :parse_args

:after_parse

REM ============================================================
REM 检测 Maven
REM ============================================================
if not "%MVN%"=="" goto :have_mvn
where mvn >nul 2>nul
if not errorlevel 1 (
    set "MVN=mvn"
    goto :have_mvn
)
if exist "mvnw.cmd" (
    set "MVN=mvnw.cmd"
    goto :have_mvn
)
echo [ERROR] Maven not found. Please install Maven 3.6+ or run 'mvnw'.
exit /b 1

:have_mvn

REM ============================================================
REM 执行 Maven
REM ============================================================
echo ==^> Running: %MVN% %MVN_GOAL%%MVN_EXTRA_ARGS%
call %MVN% -B -ntp %MVN_GOAL%%MVN_EXTRA_ARGS%
if errorlevel 1 exit /b 1

echo ==^> Build complete.
echo     JAR     : target\apex-ci-library-*.jar
echo     Reports : target\surefire-reports\

endlocal
