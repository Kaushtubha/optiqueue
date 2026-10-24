@REM ============================================================
@REM Maven Wrapper Script — mvnw.cmd
@REM
@REM This script bootstraps Maven without requiring it to be
@REM installed on the developer's machine. It downloads the exact
@REM Maven version specified in .mvn/wrapper/maven-wrapper.properties
@REM and caches it in ~/.m2/wrapper/dists/
@REM
@REM Usage: .\mvnw.cmd [maven goals]
@REM   e.g. .\mvnw.cmd package -DskipTests
@REM        .\mvnw.cmd test
@REM ============================================================

@SETLOCAL
@SET __MVNW_PROJECT_BASEDIR__=%~dp0
@IF "%__MVNW_PROJECT_BASEDIR__:~-1%"=="\" SET __MVNW_PROJECT_BASEDIR__=%__MVNW_PROJECT_BASEDIR__:~0,-1%
@SET __MVNW_LAUNCHER__=%__MVNW_PROJECT_BASEDIR__%\.mvn\wrapper\maven-wrapper.jar

@REM Detect JAVA_HOME
@IF NOT "%JAVA_HOME%"=="" (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
  GOTO :OkJHome
)
@SET JAVA_EXE=java.exe

:OkJHome
@IF NOT EXIST "%__MVNW_LAUNCHER__%" GOTO :DownloadWrapper
@GOTO :RunWrapper

:DownloadWrapper
@echo Downloading Maven Wrapper JAR via PowerShell...
@powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar' -OutFile '%__MVNW_LAUNCHER__%'"
@IF NOT EXIST "%__MVNW_LAUNCHER__%" (
  @echo Failed to download Maven Wrapper JAR. Please check your internet connection.
  @exit /b 1
)
@GOTO :RunWrapper

:RunWrapper
@"%JAVA_EXE%" -classpath "%__MVNW_LAUNCHER__%" "-Dmaven.multiModuleProjectDirectory=%__MVNW_PROJECT_BASEDIR__%" "-Dmaven.wrapper.properties=%__MVNW_PROJECT_BASEDIR__%\.mvn\wrapper\maven-wrapper.properties" org.apache.maven.wrapper.MavenWrapperMain %*
