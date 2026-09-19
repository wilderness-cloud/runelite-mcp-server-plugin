@echo off
rem Copies the built plugin jar into RuneLite's sideloaded-plugins folder.
rem Build first:  cd plugin ^&^& gradlew jar
setlocal
set SRC=%~dp0..\plugin\build\libs\gielinor-companion-0.1.0.jar
set DEST=%USERPROFILE%\.runelite\sideloaded-plugins
if not exist "%SRC%" (
	echo Plugin jar not found at %SRC% - build it first: cd plugin ^&^& gradlew jar
	exit /b 1
)
if not exist "%DEST%" mkdir "%DEST%"
rem Clear the pre-rename jar; leaving it there loads the plugin twice and the
rem second copy cannot bind the port.
if exist "%DEST%unelite-mcp-bridge-0.1.0.jar" del /q "%DEST%unelite-mcp-bridge-0.1.0.jar"
copy /y "%SRC%" "%DEST%" >nul
echo Installed %SRC%
echo   to %DEST%
echo Start the client with scripts\runelite-dev.cmd (sideloaded plugins need developer mode).
endlocal
