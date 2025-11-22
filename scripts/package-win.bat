@echo off
setlocal enabledelayedexpansion

where mvn >nul 2>nul || (echo Maven (mvn) not found. Please install Maven and ensure it is on PATH.& exit /b 1)
where jpackage >nul 2>nul || (echo jpackage not found. Please use JDK 14+ with jpackage available.& exit /b 1)

for %%i in ("%~dp0..") do set "ROOT_DIR=%%~fi"
set "DIST_DIR=%ROOT_DIR%\dist"
set "APP_INPUT=%ROOT_DIR%\target\app"
set "JPACKAGE_DIR=%ROOT_DIR%\target\jpackage"
set "APP_NAME=RocketMQAdmin"
set "MAIN_CLASS=org.tzh.rocketmqgui.AppLauncher"
set "POM_PATH=%ROOT_DIR%\pom.xml"

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if exist "%APP_INPUT%" rmdir /S /Q "%APP_INPUT%"
if exist "%JPACKAGE_DIR%" rmdir /S /Q "%JPACKAGE_DIR%"

pushd "%ROOT_DIR%" >nul
mvn -q -DskipTests clean package
for /f "usebackq delims=" %%i in (`powershell -NoLogo -NoProfile -Command "& { $pom = New-Object System.Xml.XmlDocument; $pom.Load('%POM_PATH%'); $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable); $ns.AddNamespace('m','http://maven.apache.org/POM/4.0.0'); Write-Output ($pom.SelectSingleNode('/m:project/m:artifactId',$ns).InnerText.Trim()) }"`) do set "ARTIFACT_ID=%%i"
for /f "usebackq delims=" %%i in (`powershell -NoLogo -NoProfile -Command "& { $pom = New-Object System.Xml.XmlDocument; $pom.Load('%POM_PATH%'); $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable); $ns.AddNamespace('m','http://maven.apache.org/POM/4.0.0'); Write-Output ($pom.SelectSingleNode('/m:project/m:version',$ns).InnerText.Trim()) }"`) do set "PROJECT_VERSION=%%i"
set "FINAL_NAME=%ARTIFACT_ID%-%PROJECT_VERSION%"
set "APP_VERSION=%PROJECT_VERSION%"
for /f "delims=-" %%i in ("%APP_VERSION%") do set "APP_VERSION=%%i"
set "MAIN_JAR=%FINAL_NAME%-app.jar"
if not exist "target\%MAIN_JAR%" (
  echo Main jar target\%MAIN_JAR% not found.
  exit /b 1
)
popd >nul

mkdir "%APP_INPUT%"
copy /Y "%ROOT_DIR%\target\%MAIN_JAR%" "%APP_INPUT%\" >nul

jpackage ^
  --type app-image ^
  --name "%APP_NAME%" ^
  --app-version "%APP_VERSION%" ^
  --input "%APP_INPUT%" ^
  --main-jar "%MAIN_JAR%" ^
  --main-class "%MAIN_CLASS%" ^
  --dest "%JPACKAGE_DIR%"

set "APP_BUNDLE=%JPACKAGE_DIR%\%APP_NAME%"
if not exist "%APP_BUNDLE%" (
  set "APP_BUNDLE=%JPACKAGE_DIR%\%APP_NAME%.app"
)
if not exist "%APP_BUNDLE%" (
  echo Could not locate jpackage output under %JPACKAGE_DIR%.
  exit /b 1
)

set "ZIP_NAME=rocketmq-gui-win.zip"
set "ZIP_PATH=%DIST_DIR%\%ZIP_NAME%"
if exist "%ZIP_PATH%" del "%ZIP_PATH%"
powershell -NoLogo -NoProfile -Command "Compress-Archive -Path '%APP_BUNDLE%' -DestinationPath '%ZIP_PATH%' -Force" >nul

echo Packaged runtime available at dist\%ZIP_NAME%
endlocal
