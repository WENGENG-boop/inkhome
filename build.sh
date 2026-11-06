#!/bin/sh
# Builds and signs inkhome.apk with the plain SDK tools.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/c/Android/Sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
rm -rf build
mkdir -p build/res build/classes build/dex build/gen
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets --java build/gen -o build/unsigned.apk build/res/res.zip
mkdir -p build/stubs
javac -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d build/stubs stubs/java/lang/invoke/LambdaMetafactory.java
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "build/stubs;$(cygpath -w "$JAR")" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8.bat" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
[ -f inkhome.keystore ] || keytool -genkeypair -keystore inkhome.keystore -storepass inkhome -keypass inkhome -alias inkhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=inkhome"
"$BT/apksigner.bat" sign --ks inkhome.keystore --ks-pass pass:inkhome --key-pass pass:inkhome --out inkhome.apk build/aligned.apk
echo built inkhome.apk
