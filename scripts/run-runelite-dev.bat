@echo off

REM Jagex Account + sideload dev (official RuneLite wiki: Using Jagex Accounts):

REM   1) Start menu: "RuneLite (configure)" ^(or launcher with --configure^).

REM   2) Client arguments: add  --insecure-write-credentials   then Save.

REM   3) Launch RuneLite ONCE through the Jagex Launcher (creates .runelite\credentials.properties under your user folder).

REM   4) Remove that flag from configure after ^(optional^); delete credentials.properties when done testing.

REM   5) Run THIS script for dev-mode sideload. Never share credentials.properties.

REM

REM IMPORTANT: Running java -jar RuneLite.jar passes -Drunelite.launcher.version from the launcher, which

REM DISABLES developer mode — sideloaded-plugins are NEVER loaded. This script starts the CLIENT classpath

REM directly so --developer-mode actually works.



setlocal

set ROOT=%~dp0..

set SIDELOAD=%USERPROFILE%\.runelite\sideloaded-plugins

REM Pick whichever jar gradle just produced (filename includes project.version).

set PLUGIN_JAR=

set REPO2=%USERPROFILE%\.runelite\repository2

set RL_JAVA=%LOCALAPPDATA%\RuneLite\jre\bin\java.exe

REM Heap for dev client (full repository2 classpath + sideload). Override: set RUNELITE_DEV_XMX=1024m

if not defined RUNELITE_DEV_XMX set RUNELITE_DEV_XMX=512m



pushd "%ROOT%"

call gradlew.bat jar --no-daemon

if errorlevel 1 (

  echo Build failed.

  popd

  pause

  exit /b 1

)

popd



for /f "delims=" %%F in ('dir /b /o-d "%ROOT%\build\libs\osrs-color-lock-runelite-*.jar" 2^>nul') do (

  if not defined PLUGIN_JAR set PLUGIN_JAR=%ROOT%\build\libs\%%F

)



if not defined PLUGIN_JAR (

  echo Missing plugin JAR in %ROOT%\build\libs after build.

  pause

  exit /b 1

)



echo Using plugin jar: %PLUGIN_JAR%

if not exist "%REPO2%" (

  echo Missing folder:

  echo %REPO2%

  echo Open normal RuneLite once so dependencies download, then try again.

  pause

  exit /b 1

)

if not exist "%RL_JAVA%" (

  echo Bundled Java not found at:

  echo %RL_JAVA%

  echo Falling back to PATH java...

  set RL_JAVA=java

)



mkdir "%SIDELOAD%" 2>nul

REM Wipe any previous color-lock jars in sideload so old versions don't shadow the new one.

del /q "%SIDELOAD%\osrs-color-lock-runelite-*.jar" 2>nul

copy /Y "%PLUGIN_JAR%" "%SIDELOAD%\" >nul



if not exist "%USERPROFILE%\.runelite\credentials.properties" (

  echo.

  echo WARNING: No credentials.properties yet — Jagex accounts usually cannot log in until you do step 1-3 in the REM notes at the top of this script.

  echo.

)



echo Starting CLIENT directly ^(developer-mode ON^); sideload: %SIDELOAD%

echo Max heap: %RUNELITE_DEV_XMX% ^(set RUNELITE_DEV_XMX=4096m to raise^)

REM No -Drunelite.launcher.version so sideload runs.

"%RL_JAVA%" -ea -XX:+DisableAttachMechanism -Xmx%RUNELITE_DEV_XMX% -Xss2m -cp "%REPO2%\*" net.runelite.client.RuneLite --developer-mode %*

