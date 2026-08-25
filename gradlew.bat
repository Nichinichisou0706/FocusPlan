@rem Gradle start up script for Windows
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute
echo ERROR: JAVA_HOME is not set and no java command could be found. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo ERROR: JAVA_HOME points to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
