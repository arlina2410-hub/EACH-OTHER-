import { AppError } from '../http/errors';
import type { Env } from '../types/env';

export interface DeletionInput {
  groupId: string;
  deviceId: string;
  initiatedBy: 'ADMIN' | 'DEVICE';
  scope: 'SINGLE_RECORD' | 'DATE_RANGE' | 'CATEGORY' | 'ALL_DEVICE_DATA';
  category: 'LOCATION' | 'USAGE' | 'DEVICE_STATUS' | 'ALL';
  targetRecordId?: string;
  rangeStartMs?: number;
  rangeEndMs?: number;
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
  const stmts: D1PreparedStatement[] = [];
  const categories =
    input.category === 'ALL'
      ? (['LOCATION', 'USAGE', 'DEVICE_STATUS'] as const)
      : ([input.category] as const);

  if (input.scope === 'SINGLE_RECORD' && input.targetRecordId) {
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        stmts.push(
          env.DB.prepare('DELETE FROM location_records WHERE id = ? AND device_id = ? AND group_id = ?').bind(
            input.targetRecordId,
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'USAGE') {
        stmts.push(
          env.DB.prepare('DELETE FROM usage_summaries WHERE id = ? AND device_id = ? AND group_id = ?').bind(
            input.targetRecordId,
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'DEVICE_STATUS') {
        stmts.push(
          env.DB.prepare('DELETE FROM device_status_records WHERE id = ? AND device_id = ? AND group_id = ?').bind(
            input.targetRecordId,
            input.deviceId,
            input.groupId
          )
        );
      }
    }
  } else if (input.scope === 'DATE_RANGE' && input.rangeStartMs !== undefined && input.rangeEndMs !== undefined) {
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        stmts.push(
          env.DB.prepare(
            'DELETE FROM location_records WHERE device_id = ? AND group_id = ? AND recorded_at_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      } else if (cat === 'USAGE') {
        stmts.push(
          env.DB.prepare(
            'DELETE FROM usage_summaries WHERE device_id = ? AND group_id = ? AND period_start_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      } else if (cat === 'DEVICE_STATUS') {
        stmts.push(
          env.DB.prepare(
            'DELETE FROM device_status_records WHERE device_id = ? AND group_id = ? AND observed_at_ms BETWEEN ? AND ?'
          ).bind(input.deviceId, input.groupId, input.rangeStartMs, input.rangeEndMs)
        );
      }
    }
  } else {
    for (const cat of categories) {
      if (cat === 'LOCATION') {
        stmts.push(
          env.DB.prepare('DELETE FROM location_records WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'USAGE') {
        stmts.push(
          env.DB.prepare('DELETE FROM usage_summaries WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      } else if (cat === 'DEVICE_STATUS') {
        stmts.push(
          env.DB.prepare('DELETE FROM device_status_records WHERE device_id = ? AND group_id = ?').bind(
            input.deviceId,
            input.groupId
          )
        );
      }
    }
  }

  const deleteStmtCount = stmts.length;

  if (input.scope === 'SINGLE_RECORD' && input.targetRecordId) {
    stmts.push(
      env.DB.prepare(
        `INSERT INTO sync_receipts (record_id, device_id, record_type, accepted_at_ms, tombstoned)
         VALUES (?, ?, ?, ?, 1)
         ON CONFLICT(device_id, record_id) DO UPDATE SET tombstoned = 1`
      ).bind(
        input.targetRecordId,
        input.deviceId,
        input.category === 'ALL' ? 'LOCATION' : input.category,
        nowMs
      )
    );
  }

  stmts.push(
    env.DB.prepare(
      `INSERT INTO deletion_commands (id, group_id, device_id, initiated_by, scope, category, target_record_id, range_start_ms, range_end_ms, status, created_at_ms, acknowledged_at_ms)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_DEVICE_ACK', ?, NULL)`
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
      nowMs
    )
  );

  const results = await env.DB.batch(stmts);
  const serverDeletedCount = results
    .slice(0, deleteStmtCount)
    .reduce((acc, r) => acc + (r.meta.changes ?? 0), 0);

  return {
    commandId,
    serverDeletedCount,
    deviceDeletionStatus: 'PENDING_DEVICE_ACK',
  };
}

export async function getPendingDeviceCommands(env: Env, deviceId: string, groupId: string) {
  const device = await env.DB.prepare('SELECT status FROM devices WHERE id = ? AND group_id = ?')
    .bind(deviceId, groupId)
    .first<{ status: string }>();

  if (!device) {
    throw new AppError(404, 'DEVICE_NOT_FOUND', 'Device not found.');
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
  const stmts = commandIds.map((id) =>
    env.DB.prepare(
      `UPDATE deletion_commands
       SET status = 'ACKNOWLEDGED', acknowledged_at_ms = ?
       WHERE id = ? AND device_id = ? AND status = 'PENDING_DEVICE_ACK'`
    ).bind(nowMs, id, deviceId)
  );
  await env.DB.batch(stmts);
  return { acknowledgedIds: commandIds };
}
