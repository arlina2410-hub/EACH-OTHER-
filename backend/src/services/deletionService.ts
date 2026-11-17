import { AppError } from '../http/errors';
import type { Env } from '../types/env';
import type { SharingCategory } from './consentPolicyService';

export interface DeletionInput {
  groupId: string;
  deviceId: string;
  initiatedBy: 'ADMIN' | 'DEVICE';
  scope: 'SINGLE_RECORD' | 'DATE_RANGE' | 'CATEGORY' | 'ALL_DEVICE_DATA';
  category: 'LOCATION' | 'USAGE' | 'DEVICE_STATUS' | 'ALL';
  targetRecordId?: string;
  rangeStartMs?: number;
  rangeEndMs?: number;
  initialStatus?: 'PENDING_DEVICE_ACK' | 'DEVICE_REVOKED_LOCAL_UNVERIFIED';
}

export async function createDeletionCommand(
  env: Env,
  input: DeletionInput,
  nowMs: number = Date.now()
) {
  const device = await env.DB.prepare('SELECT id FROM devices WHERE id = ? AND group_id = ?')
    .bind(input.deviceId, input.groupId)
    .first<{ id: string }>();

  if (!device) {
    throw new AppError(404, 'DEVICE_NOT_FOUND', 'Device not found in group.');
  }

  const commandId = crypto.randomUUID();
  const categories: SharingCategory[] =
    input.category === 'ALL'
      ? ['LOCATION', 'USAGE', 'DEVICE_STATUS']
      : [input.category];

  const tombstoneStmts: D1PreparedStatement[] = [];
  const deleteStmts: D1PreparedStatement[] = [];

  if (input.scope === 'SINGLE_RECORD' && input.targetRecordId) {
    // 1. Apply tombstones BEFORE deleting source rows
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'LOCATION', ?, 1
             FROM location_records
             WHERE id = ? AND device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.targetRecordId, input.deviceId, input.groupId)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM location_records WHERE id = ? AND device_id = ? AND group_id = ?'
          ).bind(input.targetRecordId, input.deviceId, input.groupId)
        );
      } else if (cat === 'USAGE') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'USAGE', ?, 1
             FROM usage_summaries
             WHERE id = ? AND device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.targetRecordId, input.deviceId, input.groupId)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM usage_summaries WHERE id = ? AND device_id = ? AND group_id = ?'
          ).bind(input.targetRecordId, input.deviceId, input.groupId)
        );
      } else if (cat === 'DEVICE_STATUS') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'DEVICE_STATUS', ?, 1
             FROM device_status_records
             WHERE id = ? AND device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.targetRecordId, input.deviceId, input.groupId)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM device_status_records WHERE id = ? AND device_id = ? AND group_id = ?'
          ).bind(input.targetRecordId, input.deviceId, input.groupId)
        );
      }
    }

    // Also record an explicit receipt tombstone when category is specific (even if offline record wasn't uploaded yet)
    if (input.category !== 'ALL') {
      tombstoneStmts.push(
        env.DB.prepare(
          `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
           VALUES (?, ?, ?, ?, 1)
           ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
        ).bind(input.targetRecordId, input.deviceId, input.category, nowMs)
      );
    } else {
      // Update any existing receipt for targetRecordId regardless of category
      tombstoneStmts.push(
        env.DB.prepare(
          'UPDATE sync_receipts SET tombstoned = 1 WHERE device_id = ? AND record_id = ?'
        ).bind(input.deviceId, input.targetRecordId)
      );
    }
  } else if (
    input.scope === 'DATE_RANGE' &&
    input.rangeStartMs !== undefined &&
    input.rangeEndMs !== undefined
  ) {
    // 1. Tombstone ALL affected record IDs BEFORE deleting source rows
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'LOCATION', ?, 1
             FROM location_records
             WHERE device_id = ? AND group_id = ? AND recorded_at_ms BETWEEN ? AND ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM location_records WHERE device_id = ? AND group_id = ? AND recorded_at_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      } else if (cat === 'USAGE') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'USAGE', ?, 1
             FROM usage_summaries
             WHERE device_id = ? AND group_id = ? AND period_start_ms BETWEEN ? AND ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM usage_summaries WHERE device_id = ? AND group_id = ? AND period_start_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      } else if (cat === 'DEVICE_STATUS') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'DEVICE_STATUS', ?, 1
             FROM device_status_records
             WHERE device_id = ? AND group_id = ? AND observed_at_ms BETWEEN ? AND ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
        deleteStmts.push(
          env.DB.prepare(
            'DELETE FROM device_status_records WHERE device_id = ? AND group_id = ? AND observed_at_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      }
    }
  } else {
    // CATEGORY or ALL_DEVICE_DATA: Tombstone ALL matching source rows & receipts BEFORE deletion
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'LOCATION', ?, 1
             FROM location_records
             WHERE device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId)
        );
        tombstoneStmts.push(
          env.DB.prepare(
            "UPDATE sync_receipts SET tombstoned = 1 WHERE device_id = ? AND record_type = 'LOCATION'"
          ).bind(input.deviceId)
        );
        deleteStmts.push(
          env.DB.prepare('DELETE FROM location_records WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'USAGE') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'USAGE', ?, 1
             FROM usage_summaries
             WHERE device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId)
        );
        tombstoneStmts.push(
          env.DB.prepare(
            "UPDATE sync_receipts SET tombstoned = 1 WHERE device_id = ? AND record_type = 'USAGE'"
          ).bind(input.deviceId)
        );
        deleteStmts.push(
          env.DB.prepare('DELETE FROM usage_summaries WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'DEVICE_STATUS') {
        tombstoneStmts.push(
          env.DB.prepare(
            `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
             SELECT id, device_id, 'DEVICE_STATUS', ?, 1
             FROM device_status_records
             WHERE device_id = ? AND group_id = ?
             ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
          ).bind(nowMs, input.deviceId, input.groupId)
        );
        tombstoneStmts.push(
          env.DB.prepare(
            "UPDATE sync_receipts SET tombstoned = 1 WHERE device_id = ? AND record_type = 'DEVICE_STATUS'"
          ).bind(input.deviceId)
        );
        deleteStmts.push(
          env.DB.prepare('DELETE FROM device_status_records WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      }
    }
  }

  const commandStatus = input.initialStatus ?? 'PENDING_DEVICE_ACK';
  const commandInsertStmt = env.DB.prepare(
    `INSERT INTO deletion_commands (
       id, group_id, device_id, initiated_by, scope, category,
       target_record_id, range_start_ms, range_end_ms, status, created_at_ms, acknowledged_at_ms
     )
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`
  ).bind(
    commandId,
    input.groupId,
    input.deviceId,
    input.initiatedBy,
    input.scope,
    input.category,
    input.targetRecordId ?? null,
    input.rangeStartMs ?? null,
    input.rangeEndMs ?? null,
    commandStatus,
    nowMs
  );

  // Batch order: tombstones FIRST -> source DELETEs -> deletion_commands audit/command entry
  const allStmts = [...tombstoneStmts, ...deleteStmts, commandInsertStmt];
  const results = await env.DB.batch(allStmts);

  const deleteOffset = tombstoneStmts.length;
  const serverDeletedCount = results
    .slice(deleteOffset, deleteOffset + deleteStmts.length)
    .reduce((acc, r) => acc + (r.meta.changes ?? 0), 0);

  return {
    commandId,
    serverDeletedCount,
    deviceDeletionStatus: commandStatus,
    localWipeGuaranteed: false,
  };
}

/**
 * Checks whether an incoming record (e.g., from a future Phase 3 offline sync batch)
 * is blocked by an explicit receipt tombstone OR a matching deletion command covering
 * historical records captured at or before the deletion command.
 */
export async function isRecordTombstoned(
  env: Env,
  deviceId: string,
  recordId: string,
  recordType: SharingCategory,
  recordedAtMs: number
): Promise<boolean> {
  const receipt = await env.DB.prepare(
    'SELECT tombstoned FROM sync_receipts WHERE device_id = ? AND record_id = ?'
  )
    .bind(deviceId, recordId)
    .first<{ tombstoned: number }>();

  if (receipt?.tombstoned === 1) {
    return true;
  }

  const blockingCmd = await env.DB.prepare(
    `SELECT id FROM deletion_commands
     WHERE device_id = ?
       AND (category = ? OR category = 'ALL')
       AND (
         (scope = 'SINGLE_RECORD' AND target_record_id = ?)
         OR (scope = 'DATE_RANGE' AND ? BETWEEN range_start_ms AND range_end_ms)
         OR (scope IN ('CATEGORY', 'ALL_DEVICE_DATA') AND created_at_ms >= ?)
       )
     LIMIT 1`
  )
    .bind(deviceId, recordType, recordId, recordedAtMs, recordedAtMs)
    .first<{ id: string }>();

  return blockingCmd !== null;
}

export async function getPendingDeviceCommands(env: Env, deviceId: string, groupId: string) {
  const device = await env.DB.prepare('SELECT status FROM devices WHERE id = ? AND group_id = ?')
    .bind(deviceId, groupId)
    .first<{ status: string }>();

  if (!device || device.status === 'REVOKED') {
    throw new AppError(401, 'DEVICE_REVOKED', 'Device access has been revoked.');
  }

  const cmds = await env.DB.prepare(
    `SELECT id AS commandId, initiated_by AS initiatedBy, scope, category, target_record_id AS targetRecordId,
            range_start_ms AS rangeStartMs, range_end_ms AS rangeEndMs, created_at_ms AS createdAtMs
     FROM deletion_commands
     WHERE device_id = ? AND status = 'PENDING_DEVICE_ACK'
     ORDER BY created_at_ms ASC LIMIT 50`
  )
    .bind(deviceId)
    .all();

  return {
    deviceStatus: device.status,
    pendingDeletions: cmds.results,
  };
}

export async function acknowledgeDeviceCommands(
  env: Env,
  deviceId: string,
  commandIds: string[],
  nowMs: number = Date.now()
) {
  const uniqueIds = Array.from(new Set(commandIds));
  const acknowledgedIds: string[] = [];
  const rejectedCommandIds: string[] = [];

  for (const id of uniqueIds) {
    const owned = await env.DB.prepare(
      'SELECT id, status FROM deletion_commands WHERE id = ? AND device_id = ?'
    )
      .bind(id, deviceId)
      .first<{ id: string; status: string }>();

    if (!owned) {
      rejectedCommandIds.push(id);
      continue;
    }

    if (owned.status === 'PENDING_DEVICE_ACK') {
      await env.DB.prepare(
        `UPDATE deletion_commands
         SET status = 'ACKNOWLEDGED', acknowledged_at_ms = ?
         WHERE id = ? AND device_id = ? AND status = 'PENDING_DEVICE_ACK'`
      )
        .bind(nowMs, id, deviceId)
        .run();
      acknowledgedIds.push(id);
    } else if (owned.status === 'ACKNOWLEDGED') {
      // Idempotent retry by the legitimate owning device
      acknowledgedIds.push(id);
    } else {
      rejectedCommandIds.push(id);
    }
  }

  return {
    acknowledgedIds,
    rejectedCommandIds,
  };
}
