import type { Env } from '../types/env';

export async function getStorageAndQuotaStatus(env: Env, groupId: string) {
  const probe = await env.DB.prepare('SELECT 1 AS ok').run();
  const sizeAfter = (probe.meta as { size_after?: number } | undefined)?.size_after;

  const counts = await env.DB.prepare(
    `SELECT
       (SELECT COUNT(*) FROM location_records WHERE group_id = ?) AS location_count,
       (SELECT COUNT(*) FROM usage_summaries WHERE group_id = ?) AS usage_count,
       (SELECT COUNT(*) FROM device_status_records WHERE group_id = ?) AS status_count`
  )
    .bind(groupId, groupId, groupId)
    .first<{ location_count: number; usage_count: number; status_count: number }>();

  const dayUtc = new Date().toISOString().slice(0, 10);
  const daily = await env.DB.prepare(
    'SELECT request_count, d1_rows_written_est FROM daily_quota_usage WHERE day_utc = ?'
  )
    .bind(dayUtc)
    .first<{ request_count: number; d1_rows_written_est: number }>();

  const estimatedRowBytes =
    ((counts?.location_count ?? 0) + (counts?.usage_count ?? 0) + (counts?.status_count ?? 0)) * 256;
  const dbBytes = typeof sizeAfter === 'number' && sizeAfter > 0 ? sizeAfter : estimatedRowBytes;
  const maxBytes = Number(env.D1_MAX_BYTES || 524_288_000);
  const warnBytes = Number(env.D1_SOFT_WARN_BYTES || 400_000_000);

  return {
    storage: {
      estimatedDbBytes: dbBytes,
      freeTierLimitBytes: maxBytes,
      softWarningBytes: warnBytes,
      warningActive: dbBytes >= warnBytes,
      autoExpirationEnabled: false,
      groupRecordCounts: {
        locations: counts?.location_count ?? 0,
        usageSummaries: counts?.usage_count ?? 0,
        deviceStatuses: counts?.status_count ?? 0,
      },
    },
    dailyQuotaUtc: {
      dayUtc,
      trackedRequestsToday: daily?.request_count ?? 0,
      freeTierDailyRequestCap: 100_000,
      trackedWritesToday: daily?.d1_rows_written_est ?? 0,
      freeTierDailyWriteCap: 100_000,
    },
  };
}

export async function exportGroupData(env: Env, groupId: string, deviceId?: string) {
  const whereClause = deviceId ? 'WHERE group_id = ? AND device_id = ?' : 'WHERE group_id = ?';
  const bindings = deviceId ? [groupId, deviceId] : [groupId];

  const [locations, usageSummaries, deviceStatuses] = await Promise.all([
    env.DB.prepare(
      `SELECT id, device_id, latitude, longitude, accuracy_m, provider, recorded_at_ms, received_at_ms
       FROM location_records ${whereClause} ORDER BY recorded_at_ms DESC LIMIT 5000`
    )
      .bind(...bindings)
      .all(),
    env.DB.prepare(
      `SELECT id, device_id, package_name, app_label, usage_duration_ms, period_start_ms, period_end_ms, data_completeness, recorded_at_ms
       FROM usage_summaries ${whereClause} ORDER BY period_start_ms DESC LIMIT 5000`
    )
      .bind(...bindings)
      .all(),
    env.DB.prepare(
      `SELECT id, device_id, battery_percent, charging_state, network_state, is_interactive, app_health, observed_at_ms
       FROM device_status_records ${whereClause} ORDER BY observed_at_ms DESC LIMIT 5000`
    )
      .bind(...bindings)
      .all(),
  ]);

  return {
    exportedAtMs: Date.now(),
    groupId,
    deviceId: deviceId ?? null,
    locations: locations.results,
    usageSummaries: usageSummaries.results,
    deviceStatuses: deviceStatuses.results,
  };
}
