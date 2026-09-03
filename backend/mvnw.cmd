@echo off
REM Minimal Maven wrapper (Part 1). Uses global mvn if present,
REM otherwise bootstraps Apache Maven 3.9.6 into .mvn\wrapper.
setlocal
set MAVEN_VERSION=3.9.6
set WRAPPER_DIR=%~dp0.mvn\wrapper
set MAVEN_HOME_DIR=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%
set MAVEN_BIN=%MAVEN_HOME_DIR%\bin\mvn.cmd
where mvn >nul 2>nul
if %ERRORLEVEL%==0 (
  mvn %*
  exit /b %ERRORLEVEL%
)
if exist "%MAVEN_BIN%" (
  call "%MAVEN_BIN%" %*
  exit /b %ERRORLEVEL%
)
echo [mvnw] Global Maven not found. Downloading Apache Maven %MAVEN_VERSION% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '%WRAPPER_DIR%' | Out-Null; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip' -OutFile '%WRAPPER_DIR%\maven.zip'; Expand-Archive -LiteralPath '%WRAPPER_DIR%\maven.zip' -DestinationPath '%WRAPPER_DIR%' -Force; Remove-Item -LiteralPath '%WRAPPER_DIR%\maven.zip' -Force"
if not exist "%MAVEN_BIN%" (
  echo [mvnw] Download failed. Install manually: winget install Apache.Maven
  exit /b 1
)
call "%MAVEN_BIN%" %*
