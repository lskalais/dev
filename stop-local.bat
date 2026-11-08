@echo off
setlocal

if not defined MARIADB_HOME set "MARIADB_HOME=C:\devtool\mariadb-13.0.2-winx64"
if not defined MARIADB_DATA set "MARIADB_DATA=C:\devtool\mariadb-13.0.2-winx64\data13"
if not defined TOMCAT_HOME set "TOMCAT_HOME=C:\devtool\apache-tomcat-10.1.61"
if not defined DB_PORT set "DB_PORT=3306"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-local.ps1" -MariaDbHome "%MARIADB_HOME%" -MariaDbData "%MARIADB_DATA%" -TomcatHome "%TOMCAT_HOME%" -MariaDbPort %DB_PORT%
exit /b %ERRORLEVEL%
