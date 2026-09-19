@echo off
rem Launches the RuneLite client in developer mode so sideloaded plugins load.
rem Prereqs:
rem   1. The OFFICIAL RuneLite launcher has been run at least once (via the Jagex
rem      Launcher or standalone) so %USERPROFILE%\.runelite\repository2 has the client jars.
rem   2. JDK 11+ on PATH (Temurin). If "java not found", open a NEW terminal.
rem   3. The plugin jar installed via scripts\install-sideload.cmd.
rem Jagex accounts: auto-login uses .runelite\credentials.properties - see the
rem README section "Jagex account login" for the one-time capture steps.
setlocal
set RL=%USERPROFILE%\.runelite
if not exist "%RL%\repository2" (
	echo %RL%\repository2 not found - launch RuneLite through the Jagex Launcher once first.
	exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
	echo java not found on PATH - install Temurin JDK 11+ and open a new terminal.
	exit /b 1
)
if not exist "%RL%\credentials.properties" (
	echo NOTE: no saved Jagex credentials at %RL%\credentials.properties
	echo   - Jagex account: do the one-time capture in the README ^("Jagex account login"^)
	echo     or log in once through the Jagex Launcher, then relaunch this script.
	echo   - Classic username/password account: just log in on the client's login screen.
	echo.
)
java -ea -Xmx768m -Xss2m ^
  --add-opens=java.base/java.net=ALL-UNNAMED ^
  --add-opens=java.base/java.io=ALL-UNNAMED ^
  -cp "%RL%\repository2\*" ^
  net.runelite.client.RuneLite --developer-mode --debug
endlocal
