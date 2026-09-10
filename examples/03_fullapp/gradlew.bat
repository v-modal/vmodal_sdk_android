@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do set JAVA_HOME=%%~fD
if not defined JAVA_HOME for /d %%D in ("%ProgramFiles%\Java\jdk-17*") do set JAVA_HOME=%%~fD
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
"%JAVA_EXE%" -XshowSettings:properties -version 2>&1 | findstr /C:"java.specification.version = 17" >NUL
if not %ERRORLEVEL% equ 0 (
    echo. 1>&2
    echo ERROR: VModal Full Search requires JDK 17. 1>&2
    echo Install JDK 17 and set JAVA_HOME to its installation directory, then rerun this command. 1>&2
    echo In Android Studio, set Settings ^> Build Tools ^> Gradle ^> Gradle JDK to JDK 17. 1>&2
    goto fail
)

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set ANDROID_HOME=%ANDROID_SDK_ROOT%
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
if not exist "%ANDROID_HOME%" (
    echo. 1>&2
    echo ERROR: Android SDK not found. 1>&2
    echo Install Android SDK Platform 34 in Android Studio, or set ANDROID_HOME or ANDROID_SDK_ROOT. 1>&2
    goto fail
)
if not exist "%ANDROID_HOME%\platforms\android-34" (
    echo. 1>&2
    echo ERROR: Android SDK Platform 34 is missing from %ANDROID_HOME%. 1>&2
    echo Install Android SDK Platform 34 with Android Studio's SDK Manager, then rerun this command. 1>&2
    goto fail
)

@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
