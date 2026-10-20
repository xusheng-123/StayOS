import { defineConfig } from '@playwright/test'

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
