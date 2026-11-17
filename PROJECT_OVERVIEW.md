# PROJECT_OVERVIEW.md — EACH OTHER

**Phase:** Phase 0 — Foundation and Feasibility  
**Status:** Baseline documentation complete; awaiting user approval & environment resolution before Phase 1  

---

## 1. What EACH OTHER Is

**EACH OTHER** is a private, consent-based native Android application (Kotlin + Jetpack Compose) backed by a serverless HTTPS API (Cloudflare Workers + Cloudflare D1) that allows a **Connected User** to explicitly share selected device information—periodic location, authorized app-usage summaries, and basic device status—with a trusted **Administrator (Owner)** inside a private group.

- **Single APK, Dual Role:** One Android APK supports both the Administrator role and the Connected User role.
- **One-Way Reporting:** Connected Users report to the Administrator of their joined group. Connected Users cannot read the Administrator's data or any other Connected User's data.
- **Consent-First:** All sharing categories start **OFF**. Each category requires its own plain-language disclosure and explicit permission grant on the Connected User's phone.
- **Offline-First & Honest:** Records are durably queued in Room (SQLite) while offline and uploaded via WorkManager when connectivity returns. The UI never pretends stale data is live.
- **Budget Target:** ₹0 for initial development and testing (private APK sideloading + Cloudflare Workers/D1 Free Tier).

---

## 2. Inspected Environment & Repository Reality (Phase 0 Audit)

An inspection of the workspace (`/app/applet`) and container runtime was performed on **2026-11-17**:

### 2.1 Existing Repository Contents
- `.env.example`, `.gitignore`, `index.html`, `metadata.json`, `package.json`, `src/App.tsx`, `src/index.css`, `src/main.tsx`, `tsconfig.json`, `vite.config.ts`
- No Android Gradle files (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`), no `app/src/main/AndroidManifest.xml`, and no Kotlin source files exist yet.
- The workspace was initialized with an AI Studio **Node.js 22 + React + Vite** starter template.

### 2.2 Installed Tools Checked in Container
| Tool / Runtime | Inspected Status | Notes |
| --- | --- | --- |
| Node.js (`node`) | Installed (`v22.23.2`, `GOOGLE_RUNTIME=nodejs22`) | Capable of running TypeScript, Wrangler, and Vitest for the Cloudflare Worker backend |
| npm / npx | Installed (`/usr/local/bin/npm`, `/usr/local/bin/npx`) | Available |
| Java JDK (`java`, `javac`) | **NOT INSTALLED** (`java: not found`) | Required for Gradle and Kotlin compilation |
| Gradle / Android SDK (`sdkmanager`, `adb`) | **NOT INSTALLED** | Required to compile Android `.apk` files and run Android unit/instrumented tests |
| Cloudflare `wrangler` | Not globally installed (can be added as devDependency in `backend/`) | Usable in Node.js 22 once installed in Phase 1 |

### 2.3 Critical Environment Finding / Blocker for Android Compilation
- **What is unblocked here:** All Phase 0 architecture/specifications, and in Phase 1+, the **Cloudflare Workers + D1 TypeScript backend** (plus Vitest automated backend tests and Wrangler local D1 development).
- **What is blocked in this container:** Compiling or running the **native Android (Kotlin + Jetpack Compose + Room + WorkManager) APK** and Android Gradle tests cannot execute inside a `nodejs22`-only AI Studio container without JDK + Android SDK.
- **Resolution options before Phase 1:**
  1. Re-open or pair this repository with an Android-capable environment (Android Studio locally or an Android AI Studio runtime) to build/test the `android/` Gradle module while keeping `backend/` on Node.js 22, **or**
  2. Author the modular `android/` Kotlin/Gradle project cleanly against pinned compatible AGP/Kotlin/SDK versions (documented in `DEVELOPMENT_ROADMAP.md`) so it compiles directly in Android Studio, while executing and verifying the `backend/` Worker + D1 tests directly in this container.

---

## 3. Target Hardware & Free-Tier Sizing

- **Target Android Hardware:** Entry-level devices (4 GB RAM, 64 GB storage, intermittent/slow mobile internet, e.g., mid/entry-tier Android phones).
- **Target Android Versions (Recommended Default):** `minSdk = 26` (Android 8.0), `targetSdk = 35` (Android 15), `compileSdk = 35`. (See `REQUIREMENTS.md` and `PHASE_STATUS.md` Open Decision #1.)
- **Cloudflare Free Tier Limits (Verified):**
  - Workers Free: **100,000 requests/day**, **10 ms CPU time/invocation**, **128 MB isolate memory**, **50 subrequests/request**.
  - D1 Free: **5,000,000 rows read/day**, **100,000 rows written/day**, **500 MB max per database**, **5 GB total storage**, **7-day Time Travel**.
  - Exceeding daily limits causes hard failures (`429`/errors) until `00:00 UTC` reset, making **indexed queries** and **batched uploads** mandatory.

---

## 4. Document Map

| File | Purpose |
| --- | --- |
| `PRD.md` | Verbatim product requirements & build specification (Single Source of Truth) |
| `PROJECT_OVERVIEW.md` | High-level summary, repo/environment audit, and key constraints |
| `REQUIREMENTS.md` | Detailed functional, non-functional, feasibility findings, and Open Decision recommendations |
| `ARCHITECTURE.md` | End-to-end architecture, Android MVVM layers, Cloudflare Worker modules, and stack versions |
| `DATABASE_SCHEMA.md` | Cloudflare D1 SQL schema + Room SQLite schema, indexes, and migration strategy |
| `API_SPECIFICATION.md` | Finalized `/v1/*` HTTP endpoints, Zod contracts, error codes, and rate limits |
| `PERMISSIONS.md` | Android permission matrix, targetSdk 35 impacts, disclosures, and settings flows |
| `OFFLINE_SYNC.md` | Local queue state machine, batching, idempotency, partial ack, and deletion protocol |
| `SECURITY.md` | Auth/enrollment design, credential storage, threat model, and privacy controls |
| `UI_UX_SPECIFICATION.md` | Navigation graph, screen inventory, states, accessibility, and Material 3 design rules |
| `TEST_PLAN.md` | Unit, integration, Room/Worker, and 18 end-to-end acceptance scenarios |
| `DEVELOPMENT_ROADMAP.md` | Phased execution plan (Phases 1–7) and release/deployment checklist |
| `PHASE_STATUS.md` | Phase 0 completion checklist, blockers, and explicit Open Decisions awaiting approval |
