/// <reference types="@cloudflare/vitest-plugin/types" />

import type { Env as AppEnv } from '../src/types/env';

declare global {
  namespace Cloudflare {
    interface Env extends AppEnv {
      TEST_MIGRATIONS: Array<{ name: string; queries: string[] }>;
    }
  }
}
