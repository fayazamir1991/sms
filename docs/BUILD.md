# Build and verification

Version 0.2.0 was built successfully using both Gradle 8.9 / Android Gradle Plugin 8.7.3 and a direct build with official Android SDK tools. Compile SDK 35, target 34, minimum 26. Gradle assembleDebug and lintDebug passed (remaining nonfatal warnings cover target version, required telephony and intentional Persian text alignment). APK signing v2/v3 and zip alignment verification passed. CoreChecks passed 3000 random schedule scenarios, 600 candidate batches and settings validation.

No real SMS has been sent. No physical handset, emulator, Android instrumentation or Google Messages display test was performed here. JVM regression tests use Robolectric to exercise the message actions and SQLite queue without sending real SMS. Complete the Persian installation guide acceptance steps before unattended use.

## Standard build

Install JDK 17 or 21, Gradle 8.9, Android SDK platform 35 and build-tools 35.0.0. Set ANDROID_HOME (or local.properties sdk.dir). Run `gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`. A Gradle wrapper is not bundled. If your user directory is read-only, configure a writable ANDROID_USER_HOME and GRADLE_USER_HOME.

## Direct build without dependency downloads

The app has no external libraries. Set JAVA_HOME to JDK 17 or 21 and ANDROID_HOME to the SDK. Run `python3 build-direct.py`. Output: `build/direct/delneveshte-0.2.0.apk`. This uses aapt2, javac, D8, zipalign and apksigner. A local development signing key is created in build/direct; retain it privately if you need to install future updates without uninstalling. It is not included in the source archive. Gradle and the direct script use different development keys by default; choose the same build path for updates.
