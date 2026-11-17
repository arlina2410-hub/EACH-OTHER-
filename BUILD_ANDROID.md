# BUILD_ANDROID.md — Building & Testing EACH OTHER on Android

**Important:** This repository's AI Studio workspace runs on Node.js 22 and does **not** include Java JDK, Gradle, or the Android SDK. Therefore, the native Android project under `android/` has **not** been compiled or tested on a device yet.

Follow these steps one at a time on your computer to build, test, and install the app.

---

## Step 1: Install Android Studio
1. Download and install **Android Studio** (Ladybug / Meerkat or newer) on your computer.
2. During setup, make sure the following components are installed via **SDK Manager**:
   - **Android SDK Platform 35** (Android 15)
   - **Android SDK Build-Tools 35.x**
   - **JDK 17** (bundled with Android Studio as JetBrains Runtime)

---

## Step 2: Open the `android/` Folder
1. Copy/clone this repository to your computer.
2. Open **Android Studio** -> click **Open** -> select the **`android/`** folder (not the outer root folder).
3. Wait for Gradle Sync to finish cleanly using the committed Gradle 8.9 wrapper (`android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar` with pinned `distributionSha256Sum`, and `android/gradle/libs.versions.toml`).
   - Do not regenerate or downgrade the wrapper; **JDK 17** and **Android SDK Platform 35** match AGP `8.7.3` + Kotlin `2.0.21`.

---

## Step 3: Run the Local Backend (Optional for Testing Auth, Pairing & Consent)
1. Open a terminal inside the `backend/` folder.
2. Run `npm install`.
3. Run local D1 migrations (`0001_initial_schema.sql` and `0002_phase2_1_hardening.sql`):
   `npx wrangler d1 migrations apply each-other-db-dev --local`
4. Set required local dev secrets in `backend/.dev.vars` (never commit this file):
   ```text
   JWT_SIGNING_SECRET=dev-only-random-secret-at-least-32-chars
   CODE_HASH_PEPPER=dev-only-random-pepper-at-least-32-chars
   ```
   *(The Worker fails closed with `500 SERVER_MISCONFIGURED` if `CODE_HASH_PEPPER` is missing or too short. For upgrading legacy pre-Phase-2.1 local databases only, you may optionally set `ALLOW_LEGACY_UNPEPPERED_CODES=true`.)*
5. Start the local API server: `npx wrangler dev` (listens on `http://127.0.0.1:8787`).
   - An **Android Emulator** reaches `127.0.0.1:8787` using `http://10.0.2.2:8787` (already set as `BuildConfig.API_BASE_URL` for `debug` builds).

---

## Step 4: Run Android Automated Unit Tests
Inside `android/` (or Android Studio Terminal):
- Mac/Linux: `./gradlew testDebugUnitTest`
- Windows: `gradlew.bat testDebugUnitTest`

This runs:
- `AdminKeyInteroperabilityTest`: verifies Java/Android `SHA256withECDSA` ASN.1 DER -> 64-byte IEEE P1363 conversion for Cloudflare Web Crypto compatibility plus `KeySecurityLevelMapper` (`STRONGBOX_HARDWARE` / `TEE_HARDWARE` / `SOFTWARE` / `UNKNOWN`).
- `AuthRepositoryTest`: verifies ECDSA challenge-response login, non-destructive `recoverAdmin` candidate key preservation on failure, `MockWebServer` single-refresh coordination across concurrent `401` responses, `PUT /v1/devices/me/sharing-settings` `409` conflict reconciliation, honest offline disconnect status, and Admin disconnect-with-wipe response parsing.

---

## Step 5: Build a Debug APK for Testing
In Android Studio or terminal:
1. Click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**, or run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
2. The APK is saved at:
   `android/app/build/outputs/apk/debug/app-debug.apk`
3. Install on a physical Android phone via USB debugging (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) or run directly from Android Studio on an Emulator / physical device.

---

## Step 6: Verification Checklist on a Physical Phone (NOT YET VERIFIED)
Because Android Studio / JDK 17 / Android SDK / physical-device hardware cannot be simulated in this Node.js container, verify manually after installing:
- Admin creation generates an Android Keystore ECDSA P-256 keypair, displays honest `KeyInfo` status (TEE/StrongBox/Software) + 10 recovery codes once, and signs challenges without passwords.
- Failed recovery does not erase the existing working Keystore key; valid recovery on a second phone invalidates all prior Admin devices and sessions.
- Connected User can join via 12-char invite code OR Photo Picker QR image import (without requesting Camera permission).
- Every sharing category defaults OFF; dashboard reads `"Sharing permissions enabled — reporting not yet implemented"` until Phase 3–5 capture is added; revoking Location or Usage Access in Android Settings resets sharing OFF on `ON_RESUME` and syncs monotonically to the server.
