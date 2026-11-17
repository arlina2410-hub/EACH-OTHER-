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
3. Wait for Gradle Sync to download dependencies and finish.
   - *Note:* The repo does not bundle `gradle-wrapper.jar` binary; Android Studio will automatically use its Gradle wrapper or generate the wrapper when syncing. If running from terminal, use `gradle wrapper` once if `gradlew` is not present.

---

## Step 3: Run the Local Backend (Optional for Testing Auth, Pairing & Consent)
1. Open a terminal inside the `backend/` folder.
2. Run `npm install`.
3. Run local D1 migrations: `npx wrangler d1 migrations apply each-other-db-dev --local`
4. Set local dev secret in `backend/.dev.vars` (never commit this file):
   ```text
   JWT_SIGNING_SECRET=dev-only-random-secret-at-least-32-chars
   ```
   *(ECDSA P-256 Administrator authentication requires no `PASSWORD_PEPPER`.)*
5. Start the local API server: `npx wrangler dev` (listens on `http://127.0.0.1:8787`).
   - An **Android Emulator** reaches `127.0.0.1:8787` using `http://10.0.2.2:8787` (already set as `BuildConfig.API_BASE_URL` for `debug` builds).

---

## Step 4: Run Android Automated Unit Tests
In Android Studio's Terminal (inside `android/`):
- Mac/Linux: `./gradlew testDebugUnitTest`
- Windows: `gradlew.bat testDebugUnitTest`

This runs:
- `AdminKeyInteroperabilityTest`: verifies Java/Android `SHA256withECDSA` ASN.1 DER -> 64-byte IEEE P1363 conversion for Cloudflare Web Crypto compatibility.
- `AuthRepositoryTest`: verifies ECDSA challenge-response login, `PUT /v1/devices/me/sharing-settings`, and 401 refresh-token rotation with `MockWebServer`.

---

## Step 5: Build a Debug APK for Testing
In Android Studio:
1. Click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**, or run `./gradlew assembleDebug`.
2. The APK is saved at:
   `android/app/build/outputs/apk/debug/app-debug.apk`
3. Install on a physical Android phone via USB debugging (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) or run directly from Android Studio on an Emulator / physical device.

---

## Step 6: Verification Checklist on a Physical Phone (NOT YET VERIFIED)
Because physical-device behavior cannot be simulated in this Node.js container, verify manually after installing:
- Admin creation generates an Android Keystore ECDSA P-256 keypair, displays 10 recovery codes once, and signs challenges without passwords.
- Recovery on a second phone invalidates all prior Admin devices and sessions.
- Connected User can join via 12-char invite code OR Photo Picker QR image import (without requesting Camera permission).
- Every sharing category defaults OFF; each disclosure dialog appears before OS permissions; revoking Location or Usage Access in Android Settings resets sharing OFF on return to the app (`ON_RESUME`) and updates the Admin dashboard.
