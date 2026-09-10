@echo off
set APP_HOME=%~dp0
call "%APP_HOME%..\02_search\gradlew.bat" -p "%APP_HOME%" %*
