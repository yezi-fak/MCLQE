@echo off
rem ---------------------------------------------------------------------------
rem Maven Wrapper bootstrap script (Windows)
rem Downloads maven-wrapper.jar on first run; the jar then fetches and runs
rem the Maven distribution declared in .mvn/wrapper/maven-wrapper.properties.
rem ---------------------------------------------------------------------------
setlocal enableextensions

set "BASE_DIR=%~dp0"
if "%BASE_DIR:~-1%"=="\" set "BASE_DIR=%BASE_DIR:~0,-1%"

set "WRAPPER_DIR=%BASE_DIR%\.mvn\wrapper"
set "PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties"

if not exist "%PROPERTIES%" (
    echo Error: cannot find "%PROPERTIES%"
    exit /b 1
)

set "WRAPPER_URL="
for /f "usebackq tokens=1,* delims==" %%A in ("%PROPERTIES%") do (
    if /i "%%A"=="wrapperUrl" set "WRAPPER_URL=%%B"
)

if "%WRAPPER_URL%"=="" (
    echo Error: missing wrapperUrl in maven-wrapper.properties
    exit /b 1
)

set "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"

if not exist "%WRAPPER_JAR%" (
    echo Downloading Maven Wrapper bootstrap ...
    if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%' -UseBasicParsing } catch { Write-Host $_.Exception.Message; exit 1 }"
    if not exist "%WRAPPER_JAR%" (
        echo Error: failed to download maven-wrapper.jar
        exit /b 1
    )
)

if defined JAVA_HOME (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVACMD=java"
)

"%JAVACMD%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" org.apache.maven.wrapper.MavenWrapperMain %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
