# Run on the JDK Kronstadt was built with, so a project that pins an older one through JAVA_HOME or PATH
# (jenv, asdf, sdkman) can't break it. KRONSTADT_JAVA_HOME overrides it. If neither is there, fall back to
# JAVA_HOME, then PATH.
kronstadt_build_java_home=@BUILD_JAVA_HOME@
JAVACMD=
if [ -n "$KRONSTADT_JAVA_HOME" ] ; then
    JAVACMD=$KRONSTADT_JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: KRONSTADT_JAVA_HOME is set to an invalid directory: $KRONSTADT_JAVA_HOME"
    fi
elif [ -x "$kronstadt_build_java_home/bin/java" ] ; then
    JAVACMD=$kronstadt_build_java_home/bin/java
fi

if [ -z "$JAVACMD" ] ; then
