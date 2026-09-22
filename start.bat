@echo off
setlocal EnableExtensions DisableDelayedExpansion

pushd "%~dp0"
set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" goto directory_error

echo ========================================
echo Sitemap Generator - Java application
echo Requires JDK 21+ and Maven 3.9+ on PATH.
echo ========================================
echo.

echo [1/4] Checking Java version...
java -version
set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" goto java_error

echo.
echo [2/4] Checking Maven version...
call mvn --version
set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" goto maven_error

echo.
echo [3/4] Running tests and packaging with Maven verify...
call mvn verify
set "EXIT_CODE=%errorlevel%"
if not "%EXIT_CODE%"=="0" goto build_error

echo.
echo [4/4] Starting the application in the foreground...
echo Default address: http://localhost:8080
echo Wait for Spring Boot startup logs before opening the page.
echo Press Ctrl+C to stop the application.
echo.
java -jar "target/sitemap-studio-1.2.0.jar" %*
set "EXIT_CODE=%errorlevel%"
goto finish

:java_error
echo ERROR: Java check failed. Install JDK 21+ and check PATH.
goto finish

:maven_error
echo ERROR: Maven check failed. Install Maven 3.9+ and check PATH.
goto finish

:build_error
echo ERROR: Maven verify failed. The application was not started.
goto finish

:directory_error
echo ERROR: Cannot enter the script directory.
goto return_code

:finish
popd

:return_code
endlocal & exit /b %EXIT_CODE%
