@echo off
rem Copies the built plugin jar into RuneLite's sideloaded-plugins folder.
rem Build first:  cd plugin ^&^& gradlew jar
setlocal
set LIBS=%~dp0..\plugin\build\libs
set DEST=%USERPROFILE%\.runelite\sideloaded-plugins

rem Pick the jar by pattern rather than by version: the release workflow bumps
rem the version from the merged PR's semver label, so the filename moves.
set SRC=
for %%J in ("%LIBS%\runelite-mcp-server-plugin-*.jar") do set SRC=%%~fJ
if "%SRC%"=="" (
	echo No plugin jar in %LIBS% - build it first: cd plugin ^&^& gradlew jar
	exit /b 1
)

if not exist "%DEST%" mkdir "%DEST%"

rem Clear every older copy first. Two jars of this plugin in the folder load it
rem twice, and the second copy cannot bind the port. That includes jars left by
rem an earlier version number and by the names this plugin used before the
rem rename to runelite-mcp-server-plugin.
del /q "%DEST%\runelite-mcp-server-plugin-*.jar" 2>nul
del /q "%DEST%\gielinor-companion-*.jar" 2>nul
del /q "%DEST%\runelite-mcp-bridge-*.jar" 2>nul

copy /y "%SRC%" "%DEST%" >nul
echo Installed %SRC%
echo   to %DEST%
echo Start the client with scripts\runelite-dev.cmd (sideloaded plugins need developer mode).
endlocal
