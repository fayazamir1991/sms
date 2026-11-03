# Build and verification

The previous version 0.2.0 was built successfully using both Gradle 8.9 / Android Gradle Plugin 8.7.3 and a direct build with official Android SDK tools. Compile SDK 35, target 34, minimum 26. Gradle assembleDebug and lintDebug passed (remaining nonfatal warnings cover target version, required telephony and intentional Persian text alignment). APK signing v2/v3 and zip alignment verification passed. CoreChecks passed 3000 random schedule scenarios, 600 candidate batches and settings validation.

No real SMS has been sent. No physical handset, emulator, Android instrumentation or Google Messages display test was performed here. JVM regression tests use Robolectric to exercise the message actions and SQLite queue without sending real SMS. Complete the Persian installation guide acceptance steps before unattended use.

## Standard build

Install JDK 17 or 21, Gradle 8.9, Android SDK platform 35 and build-tools 35.0.0. Set ANDROID_HOME (or local.properties sdk.dir). Run `gradle :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`. A Gradle wrapper is not bundled. If your user directory is read-only, configure a writable ANDROID_USER_HOME and GRADLE_USER_HOME.

## Direct build without dependency downloads

The app has no external runtime libraries. Set JAVA_HOME to JDK 17 or 21 and ANDROID_HOME to the SDK. Run `python3 build-direct.py`. Output: `build/direct/delneveshte-0.3.0.apk`. This uses aapt2, javac, D8, zipalign and apksigner. It refuses to create a replacement signing key. Supply ANDROID_SIGNING_STORE and ANDROID_SIGNING_PASSWORD for the privately preserved key, or retain the original local build/direct/debug.keystore. The certificate must match signing-certificate.sha256.

For distribution, first deploy the Docker backend as documented in HAMRAVESH.md and set the API_BASE_URL GitHub repository variable to its HTTPS origin. GitHub Actions always runs debug compilation, lint and unit tests. Release build, Android 14 emulator validation and upload run only when the preserved signing secrets are configured. The APK certificate must match the installed app; GitHub Releases contain notes/previews only and no APK binary or workflow artifact. Upload also needs APP_RELEASE_UPLOAD_TOKEN in GitHub Actions and its SHA-256 digest in the server environment. Missing distribution secrets leave CI tests green but skip the APK and release tag. Debug APKs are for development and must not be distributed as compatible updates.

## Server checks

Node 24 or newer: `cd server && npm test && npm run seed:build`. Tests use an in-memory SQLite D1 adapter and include authorization, encrypted private logs, idempotent synchronization, catalog deduplication and replay-safe first-deploy seeding. No Cloudflare account is needed for these tests. The deployment workflow installs Wrangler only after tests pass.

Robolectric covers existing SMS approval actions plus database migration and cloud acknowledgement races without real network or SMS. On-device battery/background behavior still needs handset validation.

## Docker checks

Run `docker build -t delneveshte-test .` from the repository root, then `python3 server/scripts/docker-smoke.py delneveshte-test`. This runs and replaces real containers, verifies HTTP API/admin authorization behind an HTTPS public origin, and confirms persistence on a named volume. It creates ephemeral test credentials and removes its own containers and volume on completion.

Version 0.8 adds controlled delayed-network Robolectric tests for immediate cached display, preserving in-progress search input, stale-response rejection and install-registration recovery. Local direct SDK build and the preserved-certificate check work without a Gradle daemon; managed sandbox socket restrictions may require Gradle lint and Robolectric to run in GitHub Actions.

Version 0.8.1 tests catalog startup on SDK 28 and 34, including nonempty but unusable legacy caches and empty server replies. Release CI also installs the actual signed distributable APK into an Android 14 emulator, disables Wi-Fi/mobile data, and checks a positive visible message count on first launch, category switching and a repeated cold launch.
