export class AppError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string
  ) {
    super(message);
    this.name = 'AppError';
  }
}

export function jsonResponse(data: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

export function errorResponse(err: unknown): Response {
  if (err instanceof AppError) {
    return jsonResponse(
      {
        error: {
          code: err.code,
          message: err.message,
        },
      },
      err.status
    );
  }

  const msg = err instanceof Error ? err.message : '';
  if (
    msg.includes('D1_') ||
    msg.includes('exceeded') ||
    msg.includes('SQLITE_FULL') ||
    msg.includes('quota')
  ) {
    return jsonResponse(
      {
        error: {
          code: 'STORAGE_OR_QUOTA_LIMIT',
          message:
            'Database free-tier quota or storage limit reached. Export and delete unneeded data or wait for daily quota reset at 00:00 UTC.',
        },
      },
      503
    );
  }

  return jsonResponse(
    {
      error: {
        code: 'INTERNAL_ERROR',
        message: 'An unexpected server error occurred.',
      },
    },
    500
  );
}
