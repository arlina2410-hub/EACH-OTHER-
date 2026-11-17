# OFFLINE_SYNC.md — EACH OTHER

**Phase:** Phase 0 — Offline Queue, Idempotency, & Deletion Protocol  
**Source of Truth:** `PRD.md` §4.5, §5.6, §5.7, §5.8

---

## 1. Local Capture & Offline Queue State Machine

Every record created by a collector on the Connected Device starts in Room with:
- `id`: Client-generated UUIDv4 (for usage summaries, deterministic or mapped per `(device, package, day_utc)` — wait: see note below on daily usage upserts!).
- `recorded_at_ms` (or `observed_at_ms`): Actual UTC event timestamp from `Clock.nowMs()`, never a scheduled slot timestamp.
- `sync_state`: `PENDING` -> `IN_FLIGHT` -> `SYNCED` (or back to `PENDING` / `FAILED_RETRYABLE` on failure).

> **Important Note on Daily Usage Summaries & Idempotency:**  
> A day's app usage grows throughout the day. Rather than mutating an already-synced ID (which would be skipped by idempotency), each usage collection snapshot generates a **fresh UUIDv4** for the current `(package_name, period_start_ms, cumulative_duration_ms)` OR the server performs `ON CONFLICT(device_id, package_name, period_start_ms) DO UPDATE` if a monotonic `recorded_at_ms` is newer, while still logging `record_id` in `sync_receipts`!  
> **Chosen Clean Design:** Use UUIDv4 per snapshot record AND have D1 `usage_summaries` maintain a unique index on `(device_id, package_name, period_start_ms)` so the Worker atomically updates the day's total when `excluded.recorded_at_ms >= usage_summaries.recorded_at_ms`, while recording `record_id` in `sync_receipts` for strict retry idempotency! That guarantees zero duplicate rows per app per day AND exact retry idempotency.

```text
  [Collector runs if category ON & permission granted & NOT globally paused]
                                |
                                v
                      [Insert into Room: PENDING]
                                |
            +-------------------+-------------------+
            | (Device Offline)                      | (Network CONNECTED)
            v                                       v
   Remain durably in Room                 WorkManager SyncWorker runs
   UI shows "Waiting to sync (N)"         (OneTime expedited or Periodic)
                                                    |
                                                    v
                                     STEP 1: GET /v1/devices/me/commands
                                                    |
                                     +--------------+--------------+
                                     | (Has deletion commands)     | (None)
                                     v                             |
                       Execute local Room deletions                |
                       POST /v1/devices/me/commands/ack            |
                                     |                             |
                                     +--------------+--------------+
                                                    |
                                                    v
                                     STEP 2: Read up to 50 PENDING rows
                                             (oldest recorded_at first)
                                             Mark IN_FLIGHT in Room
                                                    |
                                                    v
                                     STEP 3: POST /v1/sync/batch
                                                    |
                  +---------------------------------+---------------------------------+
                  | (HTTP 200)                      | (HTTP 401 DEVICE_REVOKED)       | (Network / 5xx)
                  v                                 v                                 v
      Mark acceptedIds + duplicateIds SYNCED   Clear tokens, set status REVOKED    Revert IN_FLIGHT -> PENDING
      Delete rejectedTombstonedIds locally     Stop workers & notify user          Return Result.retry()
      Log/mark rejectedInvalid                                                     (Exponential backoff)
      Update lastSyncSuccessMs
      If more PENDING remain -> loop/enqueue
```

---

## 2. Deletion & Tombstone Protocol (Preventing Zombie Re-Uploads)

Scenario: Connected User's phone is **offline** with 20 queued location points from Nov 16. Meanwhile, the Administrator deletes all Nov 16 location history for that device.

1. **Server Immediate Action (`POST /v1/admin/devices/{id}/deletion-commands` or `/v1/devices/me/deletions`):**
   - Server applies `sync_receipts.tombstoned = 1` **before** deleting rows inside an atomic D1 batch across **all 4 scopes** (`SINGLE_RECORD`, `DATE_RANGE`, `CATEGORY`, `ALL_DEVICE_DATA`) and inserts a `deletion_commands` row (`PENDING_DEVICE_ACK` for active devices).
   - Admin UI displays: **"Deleted from server. Waiting for offline device to confirm local deletion."**
2. **Revoked Device Edge Case (`POST /v1/admin/devices/{id}/disconnect` with `deleteServerData: true`):**
   - Because disconnecting revokes the device's session immediately, that device can no longer authenticate to `/v1/devices/me/commands` or `/v1/devices/me/commands/ack`.
   - The server deletes its server-stored data and records **`DEVICE_REVOKED_LOCAL_UNVERIFIED`** (`localWipeGuaranteed: false`), so the Admin UI never falsely claims a remote wipe succeeded on the phone.
3. **Connected Device Reconnects (Phase 3 Sync Contract):**
   - `SyncWorker` **ALWAYS** executes `GET /v1/devices/me/commands` **before** calling `POST /v1/sync/batch`.
   - For every command returned:
     - `SINGLE_RECORD`: `DELETE FROM local_* WHERE id = :targetRecordId`
     - `DATE_RANGE`: `DELETE FROM local_* WHERE recorded_at_ms BETWEEN :start AND :end` (filtered by category)
     - `CATEGORY`: `DELETE FROM local_<category>`
     - `ALL_DEVICE_DATA`: `DELETE` all 3 local report tables.
   - Records `commandId` in `local_deletion_log` inside the same Room transaction.
   - Calls `POST /v1/devices/me/commands/ack` -> Server verifies ownership (`WHERE id = ? AND device_id = ?`), marks legitimate commands `ACKNOWLEDGED`, and returns `{ acknowledgedIds, rejectedCommandIds }`.
4. **Race Condition Defense (`isRecordTombstoned` Safety Helper):**
   - Even if a device sends an in-flight batch created offline before a deletion command, `POST /v1/sync/batch` runs `isRecordTombstoned(env, deviceId, recordId, recordType, recordedAtMs)` checking both explicit `sync_receipts.tombstoned = 1` rows AND `deletion_commands` covering that record's scope/date/timestamp (`created_at_ms >= recordedAtMs`).
   - Matching items are **never re-inserted** into D1; they are returned in `rejectedTombstonedIds` so the device deletes them locally.

---

## 3. Crash Safety & Ambiguous Network Failures

- **Process Death Mid-Sync:** If the OS kills the app while rows are `IN_FLIGHT`, at the start of the next `SyncWorker` run any stale `IN_FLIGHT` rows are reset to `PENDING` before reading the next batch.
- **Ambiguous Timeout (Server committed batch, client timed out before reading HTTP 200):**
  - On retry, `SyncWorker` sends the same record IDs.
  - Server sees `sync_receipts` already contains `(device_id, record_id)` with `tombstoned = 0`, skips insert, and returns those IDs in `duplicateIds`.
  - Client treats `acceptedIds + duplicateIds` identically as confirmed server persistence and marks them `SYNCED`.
- **WorkManager Backoff Policy:** `BackoffPolicy.EXPONENTIAL` starting at `30 seconds` (capped by WorkManager max backoff), constrained to `NetworkType.CONNECTED`.
- **Manual "Sync Now":** Enqueues `ExistingWorkPolicy.KEEP` one-time `SyncWorker` so it never races with an already running sync worker.
