@rem Run on the JDK Kronstadt was built with, so a project that pins an older one through JAVA_HOME or PATH
@rem can't break it. KRONSTADT_JAVA_HOME overrides it. If neither is there, fall back to JAVA_HOME, then PATH.
set "KRONSTADT_BUILD_JAVA_HOME=@BUILD_JAVA_HOME@"
if defined KRONSTADT_JAVA_HOME goto findJavaFromKronstadtJavaHome
set "JAVA_EXE=%KRONSTADT_BUILD_JAVA_HOME%/bin/java.exe"
if exist "%JAVA_EXE%" goto execute
goto findJava

:findJavaFromKronstadtJavaHome
set KRONSTADT_JAVA_HOME=%KRONSTADT_JAVA_HOME:"=%
set JAVA_EXE=%KRONSTADT_JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute

1>&2 echo.
1>&2 echo ERROR: KRONSTADT_JAVA_HOME is set to an invalid directory: %KRONSTADT_JAVA_HOME%
1>&2 echo.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel

:findJava
