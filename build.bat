@echo off
REM apex-ci-library build script (Windows native / cmd.exe)
REM Mirrors build.sh for non-Git-Bash environments.

setlocal enableextensions enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

if "%M2_REPO%"=="" (
    set "M2=%USERPROFILE%\.m2\repository"
) else (
    set "M2=%M2_REPO%"
)

if "%GROOVY_VER%"=="" set "GROOVY_VER=4.0.16"
if "%JUNIT_VER%"=="" set "JUNIT_VER=4.12"
if "%HAMCREST_VER%"=="" set "HAMCREST_VER=1.3"

set "GROOVY_JAR=%M2%\org\apache\groovy\groovy\%GROOVY_VER%\groovy-%GROOVY_VER%.jar"
set "GROOVY_JSON_JAR=%M2%\org\apache\groovy\groovy-json\%GROOVY_VER%\groovy-json-%GROOVY_VER%.jar"
set "JUNIT_JAR=%M2%\junit\junit\%JUNIT_VER%\junit-%JUNIT_VER%.jar"
set "HAMCREST_JAR=%M2%\org\hamcrest\hamcrest-core\%HAMCREST_VER%\hamcrest-core-%HAMCREST_VER%.jar"

if not exist "%GROOVY_JAR%" (
    echo [ERROR] Groovy not found: %GROOVY_JAR%
    exit /b 1
)
if not exist "%JUNIT_JAR%" (
    echo [ERROR] JUnit not found: %JUNIT_JAR%
    exit /b 1
)

set "CP=%GROOVY_JAR%;%GROOVY_JSON_JAR%;%JUNIT_JAR%;%HAMCREST_JAR%"
set "TARGET=%1"
if "%TARGET%"=="" set "TARGET=all"

REM === Clean ===
if "%TARGET%"=="clean" goto :clean
if "%TARGET%"=="all" goto :clean
if "%TARGET%"=="compile" goto :compile
if "%TARGET%"=="test" goto :compile

:clean
if exist build rmdir /S /Q build
if "%TARGET%"=="clean" goto :end

:compile
mkdir build\classes\main 2>nul
mkdir build\classes\test 2>nul
mkdir build\test-reports 2>nul

echo ==^> Compiling main sources...
dir /S /B src\*.groovy > build\sources.txt
java -cp "%CP%" org.codehaus.groovy.tools.FileSystemCompiler -d build\classes\main @build\sources.txt
if errorlevel 1 (
    echo [ERROR] Main compilation failed
    exit /b 1
)

if "%TARGET%"=="compile" goto :end

echo ==^> Compiling tests...
dir /S /B test\*.groovy > build\tests.txt
java -cp "%CP%;build\classes\main" org.codehaus.groovy.tools.FileSystemCompiler -d build\classes\test @build\tests.txt
if errorlevel 1 (
    echo [ERROR] Test compilation failed
    exit /b 1
)

if "%TARGET%"=="compile-main" goto :end

echo ==^> Running tests...
java -cp "%CP%;build\classes\main;build\classes\test" org.junit.runner.JUnitCore %JUNIT_CORE_OPTS% ^
    com.hsbc.treasury.apex.ci.config.LibraryConfigTest ^
    com.hsbc.treasury.apex.ci.core.RetryTest ^
    com.hsbc.treasury.apex.ci.core.DynamicParamsTest ^
    com.hsbc.treasury.apex.ci.core.PipelineContextTest ^
    com.hsbc.treasury.apex.ci.builders.JavaBuilderTest ^
    com.hsbc.treasury.apex.ci.builders.JavaBuilderTestEx ^
    com.hsbc.treasury.apex.ci.builders.NodeBuilderTest ^
    com.hsbc.treasury.apex.ci.builders.PythonBuilderTest ^
    com.hsbc.treasury.apex.ci.builders.GoBuilderTest ^
    com.hsbc.treasury.apex.ci.builders.ShellBuilderTest ^
    com.hsbc.treasury.apex.ci.builders.BuilderFactoryTest ^
    com.hsbc.treasury.apex.ci.scanners.ScanRunnerTest ^
    com.hsbc.treasury.apex.ci.docker.DockerBuilderTest ^
    com.hsbc.treasury.apex.ci.docker.DockerPusherTest ^
    com.hsbc.treasury.apex.ci.artifact.NexusClientTest ^
    com.hsbc.treasury.apex.ci.utils.SandboxTest ^
    com.hsbc.treasury.apex.ci.utils.UtilTest ^
    com.hsbc.treasury.apex.ci.version.SemVerTest ^
    com.hsbc.treasury.apex.ci.version.VersionManagerTest ^
    com.hsbc.treasury.apex.ci.integration.LightweightDslTest ^
    com.hsbc.treasury.apex.ci.integration.ParallelBuildTest ^
    com.hsbc.treasury.apex.ci.integration.ScanWaitIntegrationTest ^
    com.hsbc.treasury.apex.ci.integration.VersionUpgradeIntegrationTest

:end
endlocal
