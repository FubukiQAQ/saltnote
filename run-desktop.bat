@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"
set "PATH=%JAVA_HOME%\bin;%USERPROFILE%\.cargo\bin;%PATH%"
cd /d "%~dp0"
if exist "app\composeApp\build\compose\binaries\main\app\SaltNote\SaltNote.exe" (
    start "" "app\composeApp\build\compose\binaries\main\app\SaltNote\SaltNote.exe"
) else (
    call gradlew.bat :app:composeApp:run --console=plain
)
