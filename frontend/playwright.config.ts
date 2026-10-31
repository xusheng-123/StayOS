import { defineConfig } from '@playwright/test'

// This pinned Playwright version can include filled password fields in its AI error snapshot.
// Keep assertions/console results while excluding automatic DOM copies of credential/guest forms.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30000,
  reporter: [['list'], ['json', { outputFile: process.env.STAYOS_E2E_REPORT || '../.local/playwright-results.json' }]],
  use: { baseURL: 'http://127.0.0.1:5174', channel: 'msedge', headless: true, trace: 'off' },
  webServer: {
    command: 'npm run dev -- --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    env: { STAYOS_API_URL: process.env.STAYOS_E2E_API_URL || 'http://127.0.0.1:8080' },
    timeout: 30000,
  },
})
