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
@REM        .\mvnw.cmd spring-boot:run
@REM ============================================================

@IF "%__MVNW_ARG0_NAME__%"=="" (SET __MVNW_ARG0_NAME__=%~nx0)
@SET __ MVNW_CMD__=%COMSPEC%
@IF NOT "%COMSPEC%"=="" (SET __MVNW_CMD__="%COMSPEC%" /c)
@SET __MVNW_PROJECT_BASEDIR__=%~dp0
@SET __MVNW_LAUNCHER__=%__MVNW_PROJECT_BASEDIR__%.mvn\wrapper\maven-wrapper.jar

@REM Detect JAVA_HOME
@IF NOT "%JAVA_HOME%"=="" (
  SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
  GOTO :OkJHome
)
@SET JAVA_EXE=java.exe

:OkJHome
@IF NOT EXIST "%__MVNW_LAUNCHER__%"  GOTO :DownloadWrapper
@GOTO :RunWrapper

:DownloadWrapper
@echo Downloading Maven Wrapper...
@"%JAVA_EXE%" -classpath "" ^
  "-Dmaven.wrapper.properties=%__MVNW_PROJECT_BASEDIR__%.mvn\wrapper\maven-wrapper.properties" ^
  org.apache.maven.wrapper.MavenWrapperMain %*
@GOTO :RunWrapper

:RunWrapper
@"%JAVA_EXE%" ^
  -classpath "%__MVNW_LAUNCHER__%" ^
  "-Dmaven.multiModuleProjectDirectory=%__MVNW_PROJECT_BASEDIR__%" ^
  "-Dmaven.wrapper.properties=%__MVNW_PROJECT_BASEDIR__%.mvn\wrapper\maven-wrapper.properties" ^
  org.apache.maven.wrapper.MavenWrapperMain %*
