import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Runtime test credentials required')
const fixtures = JSON.parse(process.env.STAYOS_CHECKOUT_FIXTURES || '{}') as Record<string, { id: number; number: string }>
async function login(page: Page, role = 'FRONT_DESK') {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(`checkout_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}
async function post(page: Page, path: string, body: unknown, key = crypto.randomUUID()) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.post(path, { data: body, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key } })
  expect(response.status()).toBe(200)
  return (await response.json()).data
}
async function create(page: Page, fixture: string) {
  return post(page, '/api/stays', { roomId: fixtures[fixture]!.id, expectedCheckOutAt: new Date(Date.now() + 86400000).toISOString(),
    guests: [{ name: '虚构界面退房住客', documentType: 'OTHER', documentNumber: 'UI-CHECKOUT-TEST001', phone: '000-000-0000', primary: true }], notes: '' })
}
async function open(page: Page, fixture: string) {
  await page.goto('/stays')
  const row = page.getByRole('row').filter({ hasText: fixtures[fixture]!.number })
  await row.getByRole('button', { name: '办理退房', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '确认办理退房', exact: true })).toBeVisible()
}
for (const role of ['ADMIN', 'HOTEL_MANAGER', 'FRONT_DESK', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
  test(`${role}: 退房与客房任务菜单及路由权限`, async ({ page }) => {
    await login(page, role)
    const tasks = ['HOTEL_MANAGER', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF'].includes(role)
    await expect(page.getByRole('navigation', { name: '客房功能', exact: true })).toHaveCount(tasks ? 1 : 0)
    await page.goto('/housekeeping/tasks')
    if (tasks) await expect(page.getByRole('heading', { name: '清洁任务工作台', exact: true })).toBeVisible()
    else await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
    await page.goto('/stays')
    if (['FRONT_DESK', 'HOTEL_MANAGER'].includes(role)) await expect(page.getByRole('heading', { name: '当前入住', exact: true })).toBeVisible()
    else await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
  })
}
test('正式退房确认、服务器时间、历史、房态和任务联动', async ({ page }) => {
  await login(page); const stay = await create(page, 'normal'); await open(page, 'normal')
  const dialog = page.getByRole('dialog', { name: '确认办理退房', exact: true })
  await expect(dialog.getByText('虚构界面退房住客', { exact: true })).toBeVisible()
  await expect(dialog.getByText('虚构退房测试房型', { exact: true })).toBeVisible()
  await expect(dialog.getByText('入住时间', { exact: true })).toBeVisible()
  await expect(dialog.getByText('预计退房时间', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../.local/checkout-confirm.png', fullPage: true, animations: 'disabled' })
  await dialog.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
  await expect(page.getByText('待打扫', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../.local/checkout-success.png', fullPage: true, animations: 'disabled' })
  await page.getByRole('link', { name: '查看历史入住', exact: true }).click()
  await expect(page.locator('.el-table').getByRole('row').filter({ hasText: fixtures.normal!.number })).toContainText('已退房')
  const detail = (await (await page.request.get(`/api/stays/${stay.id}`)).json()).data
  expect(detail.actualCheckOutAt).toBeTruthy(); expect(detail.checkedOutBy).toBeTruthy()
  await page.goto('/rooms')
  const card = page.getByRole('button', { name: `查看房间 ${fixtures.normal!.number}`, exact: true })
  await expect(card.locator('.room-status-pill')).toHaveText('待打扫')
  const room = (await (await page.request.get(`/api/rooms/${fixtures.normal!.id}`)).json()).data
  expect(room.occupied).toBe(false); expect(room.cleaningStatus).toBe('DIRTY'); expect(room.canCheckIn).toBe(false); expect(room.hasOpenCleaningTask).toBe(true)
})
test('网络响应丢失后同标识安全重试，且阻止重复点击', async ({ page }) => {
  await login(page); await create(page, 'network'); await open(page, 'network')
  const keys: string[] = []
  await page.route('**/api/stays/*/check-out', async route => {
    keys.push(route.request().headers()['idempotency-key']!)
    const response = await route.fetch(); expect(response.status()).toBe(200)
    await new Promise(resolve => setTimeout(resolve, 700)); await route.abort('failed')
  }, { times: 1 })
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/check-out') && keys.length) keys.push(request.headers()['idempotency-key']!) })
  await page.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原退房请求', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '重试原退房请求', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '重试原退房请求', exact: true }).click()
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
  expect(keys.length).toBeGreaterThanOrEqual(2); expect(new Set(keys).size).toBe(1)
})
test('刷新后恢复原退房请求，浏览器不保存住客资料', async ({ page }) => {
  await login(page); await create(page, 'refresh'); await open(page, 'refresh')
  let originalKey = ''
  await page.route('**/api/stays/*/check-out', async route => {
    originalKey = route.request().headers()['idempotency-key']!
    expect((await route.fetch()).status()).toBe(200); await route.abort('failed')
  }, { times: 1 })
  await page.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原退房请求', exact: true })).toBeEnabled()
  const saved = await page.evaluate(() => JSON.stringify(sessionStorage))
  expect(saved).toContain(originalKey); expect(saved).not.toMatch(/虚构|document|phone|UI-CHECKOUT/)
  await page.reload()
  await expect(page.getByText('检测到上次退房结果尚未确认，请重试原退房请求或先查看入住历史。', { exact: true })).toBeVisible()
  const response = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith('/check-out'))
  await page.getByRole('button', { name: '重试原退房请求', exact: true }).click()
  expect((await response).headers()['idempotency-key']).toBe(originalKey)
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
})
test('客房主管筛选和查看未分配退房任务，没有住客资料与越流程处理按钮', async ({ page }) => {
  await login(page, 'HOUSEKEEPING_MANAGER'); await page.goto('/housekeeping/tasks')
  await page.getByLabel('任务房号搜索', { exact: true }).fill(fixtures.task!.number)
  await page.getByRole('combobox', { name: '任务楼层', exact: true }).click()
  await page.getByRole('listbox').getByRole('option', { name: '4 楼', exact: true }).click()
  await page.getByRole('button', { name: '查询任务', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: fixtures.task!.number })
  await expect(row).toHaveCount(1); await expect(row).toContainText('待处理')
  await row.getByRole('button', { name: '任务详情', exact: true }).click()
  await expect(page.getByText('退房自动创建', { exact: true })).toBeVisible()
  expect(await page.locator('body').innerText()).not.toMatch(/虚构退房住客|UI-CHECKOUT-TEST001|000-000-0000/)
  await expect(page.getByRole('button', { name: /开始打扫|清洁完成|验收|接单/ })).toHaveCount(0)
  const data = (await (await page.request.get(`/api/housekeeping-tasks?roomNumber=${fixtures.task!.number}`)).json()).data
  expect(data.total).toBe(1); expect(JSON.stringify(data)).not.toMatch(/guest|document|phone|ciphertext/i)
  await page.screenshot({ path: '../.local/housekeeping-task.png', fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '关闭任务详情', exact: true }).click()
  await page.getByLabel('任务房号搜索', { exact: true }).fill('NO_SUCH_ROOM')
  await page.getByRole('button', { name: '查询任务', exact: true }).click()
  await expect(page.getByText('暂无待处理清洁任务', { exact: true })).toBeVisible()
})
test('退房已成功但响应内容损坏时保留原标识重试', async ({ page }) => {
  await login(page); await create(page, 'malformed'); await open(page, 'malformed')
  const keys: string[] = []
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/check-out')) keys.push(request.headers()['idempotency-key']!) })
  await page.route('**/api/stays/*/check-out', async route => {
    expect((await route.fetch()).status()).toBe(200)
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {} }) })
  }, { times: 1 })
  await page.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByText('退房响应内容不完整，请重试原退房请求确认结果', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重试原退房请求', exact: true }).click()
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
  expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1)
})
test('酒店总经理直接办理退房并查看客房任务，无需切换账号', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER'); await create(page, 'manager'); await open(page, 'manager')
  await page.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('navigation', { name: '客房功能', exact: true }).getByRole('link', { name: /清洁任务/ }).click()
  await expect(page.getByRole('row').filter({ hasText: fixtures.manager!.number })).toContainText('待处理')
  expect((await page.request.get('/api/users')).status()).toBe(403)
})
test('手机退房确认和结果布局可用', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await create(page, 'mobile'); await open(page, 'mobile')
  await expect(page.getByRole('button', { name: '确认退房', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  await page.screenshot({ path: '../.local/checkout-mobile.png', fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '确认退房', exact: true }).click()
  await expect(page.getByText('退房成功，清洁任务创建成功', { exact: true })).toBeVisible()
})
