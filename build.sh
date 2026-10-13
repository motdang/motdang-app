#!/bin/sh
# Builds build/motdang.apk with the SDK's own tools: aapt2, javac, d8, zipalign, apksigner.
# Same toolchain as baan-launcher; no Gradle.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex build/test
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/base.apk build/res.zip \
  --java build/gen --min-sdk-version 26 --target-sdk-version 36 --version-code 4 --version-name 0.4
javac -nowarn -source 11 -target 11 -encoding UTF-8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true
# Links is plain Java: its test runs on the Mac.
javac -encoding UTF-8 -d build/test src/net/motdang/app/Links.java test/LinksTest.java
java -cp build/test LinksTest
# The parking logic is plain Java too.
javac -encoding UTF-8 -d build/test src/net/motdang/app/Park.java src/net/motdang/app/ParkDetector.java \
  src/net/motdang/app/ParkBook.java src/net/motdang/app/ParkText.java test/ParkDetectorTest.java test/ParkBookTest.java
java -cp build/test ParkDetectorTest
java -cp build/test ParkBookTest
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f motdang-app.keystore ] || keytool -genkeypair -keystore motdang-app.keystore -storepass motdangapp \
  -keypass motdangapp -alias motdang -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Mot Dang" >/dev/null 2>&1
"$BT/apksigner" sign --ks motdang-app.keystore --ks-pass pass:motdangapp --out build/motdang.apk build/aligned.apk
"$BT/apksigner" verify build/motdang.apk && ls -l build/motdang.apk
