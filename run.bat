@echo off
setlocal
set "JAR=%~dp0target\retro-text-editor-1.0-SNAPSHOT-shaded.jar"
if not exist "%JAR%" (
    echo NeoNorton jar not found at "%JAR%".
    echo Build it first with: mvn package
    exit /b 1
)
javaw -jar "%JAR%" %*
