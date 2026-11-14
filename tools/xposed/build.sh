#!/bin/sh
# Builds XposedBridge.jar for the guest boot classpath: the API 100 declaration layer
# (see src/de/robv/android/xposed/XposedBridge.java). Two jars drive the build, both
# downloaded ahead of time — no Android SDK on this machine:
#   ECJ_JAR  the Eclipse batch compiler (org.eclipse.jdt.ecj, ~3 MB)
#   D8_JAR   r8's D8 dexer from Google's maven (~17 MB)
# Output: build/XposedBridge.jar — a plain jar whose classes.dex goes on
# /system/framework/XposedBridge.jar in the image (rootify.py places it).
set -e
cd "$(dirname "$0")"
ECJ_JAR=${ECJ_JAR:-/tmp/ecj.jar}
D8_JAR=${D8_JAR:-/tmp/r8.jar}
[ -f "$ECJ_JAR" ] || { echo "ECJ_JAR missing: $ECJ_JAR"; exit 1; }
[ -f "$D8_JAR" ] || { echo "D8_JAR missing: $D8_JAR"; exit 1; }
rm -rf build && mkdir -p build/classes
java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 \
  -d build/classes $(find src -name '*.java')
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 28 \
  --output build build/classes/de/robv/android/xposed/*.class build/classes/de/robv/android/xposed/callbacks/*.class 2>/dev/null || \
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 28 \
  --output build $(find build/classes -name '*.class')
python3 - <<'EOF'
import zipfile, os
os.makedirs("build", exist_ok=True)
with zipfile.ZipFile("build/XposedBridge.jar", "w", zipfile.ZIP_DEFLATED) as z:
    z.write("build/classes.dex", "classes.dex")
    for r, _, fs in os.walk("build/classes"):
        for f in fs:
            if f.endswith(".class"):
                p = os.path.join(r, f)
                z.write(p, os.path.relpath(p, "build/classes"))
print("build/XposedBridge.jar written")
EOF
ls -la build/XposedBridge.jar
