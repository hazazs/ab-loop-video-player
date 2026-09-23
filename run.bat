@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo Java was not found in PATH.
  echo Install JDK 21 and try again.
  pause
  exit /b 1
)

where mvn >nul 2>&1
if errorlevel 1 (
  echo Maven was not found in PATH.
  echo Install Maven 3.9+ and try again.
  pause
  exit /b 1
)

echo Starting A-B Loop Video Player...
mvn javafx:run
if errorlevel 1 (
  echo.
  echo The application could not be started.
  echo Make sure JDK 21 is active: java -version
  pause
  exit /b 1
)
endlocal
