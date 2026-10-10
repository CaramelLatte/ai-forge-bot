#!/bin/sh
# Starts Adventure mode with the LLM agent in control (see docs/LLM-Player.md).
# Run from a source checkout after building forge-gui-mobile-dev; extra arguments are passed to the game,
# e.g. ./forge-adventure-llm.sh width=1280 height=720
# Set JAVA to pick a specific java binary, and -Dforge.llm.*=... in JAVA_OPTS to override config.json.
# --claude plays with the Claude API instead of the local server (key: claudeApiKey in config.json or ANTHROPIC_API_KEY).
cd "$(dirname "$0")" || exit 1
LLM_OPTS=""
for arg do
    shift
    if [ "$arg" = "--claude" ]; then
        LLM_OPTS="-Dforge.llm.provider=claude"
    else
        set -- "$@" "$arg"
    fi
done
JAR=$(ls target/forge-gui-mobile-dev-*-jar-with-dependencies.jar 2>/dev/null | head -n 1)
if [ -z "$JAR" ]; then
    echo "Build first: mvn -DskipTests -pl forge-gui-mobile-dev -am install"
    exit 1
fi
exec "${JAVA:-java}" -Xmx4096m -Dforge.llm.agent=true $LLM_OPTS $JAVA_OPTS \
    --add-opens java.desktop/java.beans=ALL-UNNAMED --add-opens java.desktop/javax.swing.border=ALL-UNNAMED \
    --add-opens java.desktop/javax.swing.event=ALL-UNNAMED --add-opens java.desktop/sun.swing=ALL-UNNAMED \
    --add-opens java.desktop/java.awt.image=ALL-UNNAMED --add-opens java.desktop/java.awt.color=ALL-UNNAMED \
    --add-opens java.desktop/sun.awt.image=ALL-UNNAMED --add-opens java.desktop/javax.swing=ALL-UNNAMED \
    --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED \
    --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED \
    -Dio.netty.tryReflectionSetAccessible=true -Dfile.encoding=UTF-8 \
    -jar "$JAR" "$@"
