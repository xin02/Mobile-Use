#!/bin/bash
# Windows/Git-Bash build for vd-tool-java (javac + d8).
# Produces bin/agent_vd.dex and bin/agent_tools.dex, same roles as build.sh.
set -e
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_JAR="D:\\tools\\adsdk\\platforms\\android-37.0\\android.jar"
D8_JAR="D:\\tools\\adsdk\\build-tools\\36.0.0\\lib\\d8.jar"

rm -rf bin/classes bin/out_vd bin/out_tools bin/vd_in.jar bin/tools_in.jar
mkdir -p bin/classes bin/out_vd bin/out_tools

echo "[build] javac"
javac -proc:none -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR" \
    src/com/agent/DaemonMain.java src/com/agent/ToolMain.java -d bin/classes 2>&1 | grep -v "^警告\|^注:" || true

cd bin/classes
jar cf ../vd_in.jar com/agent/DaemonMain*.class
jar cf ../tools_in.jar com/agent/ToolMain*.class
cd "$SCRIPT_DIR/bin"

echo "[build] d8 DaemonMain"
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 21 --lib "$ANDROID_JAR" \
    --output out_vd vd_in.jar 2>&1 | grep -v "^Warning" || true
echo "[build] d8 ToolMain"
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 21 --lib "$ANDROID_JAR" \
    --output out_tools tools_in.jar 2>&1 | grep -v "^Warning" || true

cp out_vd/classes.dex agent_vd.dex
cp out_tools/classes.dex agent_tools.dex
ls -lh agent_vd.dex agent_tools.dex
