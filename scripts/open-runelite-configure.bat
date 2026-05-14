@echo off
REM Opens the RuneLite *launcher* settings (JVM args, client args like --insecure-write-credentials).
REM This is NOT the in-game plugin config panel.

set RL_EXE=%LOCALAPPDATA%\RuneLite\RuneLite.exe
if not exist "%RL_EXE%" (
  echo RuneLite launcher not found:
  echo %RL_EXE%
  echo Install/update from https://runelite.net/ ^(launcher 2.6.3+ has Configure^).
  pause
  exit /b 1
)
start "" "%RL_EXE%" --configure
