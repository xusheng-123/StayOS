import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Runtime test credentials required')
const fixtures = JSON.parse(process.env.STAYOS_HK_FIXTURES || '{}') as Record<string, { id: number; number: string; taskId: number }>
const employees = JSON.parse(process.env.STAYOS_HK_EMPLOYEES || '{}') as Record<string, number>
async function login(page: Page, role = 'HOUSEKEEPING_STAFF') {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`hk_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password!); await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).not.toHaveURL(/\/login/)
}
async function post(page: Page, path: string, body: unknown, key = crypto.randomUUID()) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const r = await page.request.post(path, { data: body, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key } }); expect(r.status(), await r.text()).toBe(200); return (await r.json()).data
}
async function task(page: Page, id: number) { const r = await page.request.get(`/api/housekeeping-tasks/${id}`); expect(r.status()).toBe(200); return (await r.json()).data }
async function act(page: Page, id: number, action: string, extra = {}) { return post(page, `/api/housekeeping-tasks/${id}/${action}`, { version: (await task(page, id)).version, ...extra }) }
async function search(page: Page, fixture: string) {
  await page.goto('/housekeeping/tasks'); await page.getByLabel('任务房号搜索', { exact: true }).fill(fixtures[fixture]!.number); await page.getByRole('button', { name: '查询任务', exact: true }).click()
}
function row(page: Page, fixture: string) { return page.getByRole('row').filter({ hasText: fixtures[fixture]!.number }) }
async function confirm(page: Page, name: string) {
  const dialog = page.getByRole('dialog', { name: `确认${name}`, exact: true }); await expect(dialog).toBeVisible(); await dialog.getByRole('button', { name: `确认${name}`, exact: true }).click()
}
async function manual(page: Page, fixture: string) {
  await page.getByRole('button', { name: '创建人工清洁任务', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '确认人工创建', exact: true })
  await dialog.getByLabel('人工任务房号搜索', { exact: true }).fill(fixtures[fixture]!.number); await dialog.getByRole('button', { name: '搜索房间', exact: true }).click()
  await dialog.getByRole('combobox', { name: '选择待打扫房间', exact: true }).click(); await page.getByRole('option', { name: new RegExp(fixtures[fixture]!.number) }).click()
  await dialog.getByLabel('操作原因（必填）', { exact: true }).fill('虚构界面测试：初始客房清洁')
  await dialog.getByRole('button', { name: '确认人工创建', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '人工创建成功' })).toBeVisible()
  const r = await page.request.get(`/api/housekeeping-tasks?roomNumber=${fixtures[fixture]!.number}`); return (await r.json()).data.items[0].id as number
}
for (const role of ['ADMIN', 'HOTEL_MANAGER', 'FRONT_DESK', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
  test(`${role}: 客房业务菜单、路由和创建权限`, async ({ page }) => {
    await login(page, role); await page.goto('/housekeeping/tasks')
    if (['ADMIN', 'FRONT_DESK'].includes(role)) { await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible(); return }
    await expect(page.getByRole('heading', { name: '清洁任务工作台', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '创建人工清洁任务', exact: true })).toHaveCount(role === 'HOUSEKEEPING_STAFF' ? 0 : 1)
    expect((await page.request.get('/api/users')).status()).toBe(403)
  })
}
test('主管通过界面为初始 DIRTY 房间创建真实人工任务', async ({ page }) => {
  await login(page, 'HOUSEKEEPING_MANAGER'); await search(page, 'manual'); const id = await manual(page, 'manual'); expect((await task(page, id)).source).toBe('MANUAL')
  await row(page, 'manual').getByRole('button', { name: '任务详情', exact: true }).click()
  await expect(page.getByText('主管人工创建', { exact: true })).toBeVisible(); await expect(page.getByRole('heading', { name: '操作历史', exact: true })).toBeVisible()
  await page.screenshot({ path: '../.local/housekeeping-manual.png', fullPage: true, animations: 'disabled' })
})
test('服务员执行主管分配的任务，开始和完成，时间与房态同步', async ({ page }) => {
  await login(page); await search(page, 'claim'); await expect(page.getByRole('button', { name: '领取任务', exact: true })).toHaveCount(0)
  await expect(row(page, 'claim')).toContainText('清洁测试HOUSEKEEPING_STAFF'); expect((await task(page, fixtures.claim!.taskId)).status).toBe('PENDING')
  await row(page, 'claim').getByRole('button', { name: '开始打扫', exact: true }).click(); await confirm(page, '开始打扫'); await expect(row(page, 'claim')).toHaveCount(0)
  let room = (await (await page.request.get(`/api/rooms/${fixtures.claim!.id}`)).json()).data; expect(room.cleaningStatus).toBe('CLEANING'); expect(room.canCheckIn).toBe(false)
  await page.getByRole('combobox', { name: '任务状态筛选', exact: true }).click(); await page.getByRole('option', { name: '正在打扫', exact: true }).click(); await page.getByRole('button', { name: '查询任务', exact: true }).click()
  await row(page, 'claim').getByRole('button', { name: '完成清洁', exact: true }).click(); await page.getByLabel('操作备注（选填）', { exact: true }).fill('已完成虚构测试清洁'); await confirm(page, '完成清洁')
  await expect(page.getByRole('alert').filter({ hasText: '完成清洁成功' })).toBeVisible(); const t = await task(page, fixtures.claim!.taskId); expect(t.status).toBe('COMPLETED'); expect(t.startedAt).toBeTruthy(); expect(t.completedAt).toBeTruthy()
  room = (await (await page.request.get(`/api/rooms/${fixtures.claim!.id}`)).json()).data; expect(room.cleaningStatus).toBe('CLEAN'); expect(room.canCheckIn).toBe(true)
})
test('主管分配并受控接管正在打扫任务，旧员工不可继续操作', async ({ page, browser }) => {
  const id = fixtures.takeover!.taskId; await login(page, 'HOUSEKEEPING_MANAGER'); await search(page, 'takeover')
  await row(page, 'takeover').getByRole('button', { name: '分配任务', exact: true }).click(); await page.getByRole('combobox', { name: '新的执行员工', exact: true }).click(); await page.getByRole('option', { name: `清洁测试HOUSEKEEPING_STAFF · #${employees.HOUSEKEEPING_STAFF}`, exact: true }).click(); await confirm(page, '分配任务')
  const context = await browser.newContext(); const staff = await context.newPage(); await login(staff); await act(staff, id, 'start'); const started = (await task(staff, id)).startedAt
  await page.getByRole('button', { name: '刷新任务', exact: true }).click(); await page.getByRole('combobox', { name: '任务状态筛选', exact: true }).click(); await page.getByRole('option', { name: '正在打扫', exact: true }).click(); await page.getByRole('button', { name: '查询任务', exact: true }).click()
  await row(page, 'takeover').getByRole('button', { name: '重新分配', exact: true }).click(); await page.getByRole('combobox', { name: '新的执行员工', exact: true }).click(); await page.getByRole('option', { name: `清洁测试STAFF2 · #${employees.STAFF2}`, exact: true }).click(); await page.getByLabel('操作原因（必填）', { exact: true }).fill('原员工临时离岗'); await confirm(page, '重新分配')
  await expect(row(page, 'takeover')).toContainText('清洁测试STAFF2'); expect((await task(page, id)).startedAt).toBe(started); expect((await staff.request.get(`/api/housekeeping-tasks/${id}`)).status()).toBe(404)
  await context.close()
})
test('完成响应丢失后复用原标识重试，只产生一次完成历史', async ({ page }) => {
  const id = fixtures.network!.taskId; await login(page); await act(page, id, 'start'); await page.goto('/housekeeping/tasks'); await page.getByRole('button', { name: '刷新任务', exact: true }).click()
  await page.getByRole('combobox', { name: '任务状态筛选', exact: true }).click(); await page.getByRole('option', { name: '正在打扫', exact: true }).click(); await page.getByLabel('任务房号搜索', { exact: true }).fill(fixtures.network!.number); await page.getByRole('button', { name: '查询任务', exact: true }).click()
  const keys: string[] = []; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/${id}/complete`)) keys.push(r.headers()['idempotency-key']!) })
  await page.route(`**/api/housekeeping-tasks/${id}/complete`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await row(page, 'network').getByRole('button', { name: '完成清洁', exact: true }).click(); await confirm(page, '完成清洁')
  const dialog = page.getByRole('dialog', { name: '确认完成清洁', exact: true }); await expect(dialog.getByRole('button', { name: '重试原清洁请求', exact: true })).toBeEnabled(); await dialog.getByRole('button', { name: '重试原清洁请求', exact: true }).click(); await expect(dialog).not.toBeVisible()
  expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1)
  const logs = (await (await page.request.get(`/api/housekeeping-tasks/${id}/history`)).json()).data; expect(logs.filter((l: { action: string }) => l.action === 'COMPLETE')).toHaveLength(1)
})
test('刷新后不保存备注，先查询已成功结果再明确结束重试', async ({ page }) => {
  const id = fixtures.refresh!.taskId; await login(page); await search(page, 'refresh')
  await page.route(`**/api/housekeeping-tasks/${id}/start`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await row(page, 'refresh').getByRole('button', { name: '开始打扫', exact: true }).click(); await confirm(page, '开始打扫'); await expect(page.getByRole('dialog').getByRole('button', { name: '重试原清洁请求', exact: true })).toBeEnabled(); await page.reload()
  await expect(page.getByText('上次清洁操作结果尚未确认，请重试原请求或先查询任务详情和历史。', { exact: true })).toBeVisible()
  const stored = await page.evaluate(() => JSON.stringify(sessionStorage)); expect(stored).not.toMatch(/body|reason|phone|document|虚构/)
  await page.getByRole('button', { name: '查看上次任务', exact: true }).click(); await expect(page.getByRole('heading', { name: '操作历史', exact: true })).toBeVisible(); expect((await task(page, id)).status).toBe('IN_PROGRESS')
  await page.getByRole('button', { name: '关闭任务详情', exact: true }).click(); await page.getByRole('button', { name: '核对后结束重试', exact: true }).click(); await page.getByRole('button', { name: '已核对，结束重试', exact: true }).click(); await expect(page.getByText('上次清洁操作结果尚未确认，请重试原请求或先查询任务详情和历史。', { exact: true })).toHaveCount(0)
})
test('手机卡片完成清洁，无整页横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await search(page, 'mobile')
  const card = page.getByRole('article', { name: `清洁任务 ${fixtures.mobile!.number}`, exact: true }); await expect(card.getByRole('button', { name: '开始打扫', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true); await page.screenshot({ path: '../.local/housekeeping-mobile.png', fullPage: true, animations: 'disabled' })
  await card.getByRole('button', { name: '开始打扫', exact: true }).click(); await confirm(page, '开始打扫')
  await page.getByRole('combobox', { name: '任务状态筛选', exact: true }).click(); await page.getByRole('option', { name: '正在打扫', exact: true }).click(); await page.getByRole('button', { name: '查询任务', exact: true }).click(); await card.getByRole('button', { name: '完成清洁', exact: true }).click(); await confirm(page, '完成清洁'); await expect(page.getByRole('alert').filter({ hasText: '完成清洁成功' })).toBeVisible(); expect((await task(page, fixtures.mobile!.taskId)).status).toBe('COMPLETED')
})
test('服务员通过查询参数和详情均无法获取其他员工任务', async ({ page, browser }) => {
  await login(page, 'HOUSEKEEPING_MANAGER'); await act(page, fixtures.scopeOther!.taskId, 'assign', { assigneeId: employees.STAFF2 })
  const context = await browser.newContext(); const staff = await context.newPage(); await login(staff); await search(staff, 'scopeOther'); await expect(row(staff, 'scopeOther')).toHaveCount(0)
  expect((await staff.request.get(`/api/housekeeping-tasks/${fixtures.scopeOther!.taskId}`)).status()).toBe(404); expect((await staff.request.get(`/api/housekeeping-tasks/${fixtures.scopeOther!.taskId}/history`)).status()).toBe(404)
  expect((await staff.request.get(`/api/housekeeping-tasks?assigneeId=${employees.STAFF2}`)).status()).toBe(403); await expect(staff.getByRole('combobox', { name: '任务负责人', exact: true })).toHaveCount(0); await context.close()
})
test('总经理遵守分配和状态规则执行任务，不获得管理员权限', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER'); const id = fixtures.manager!.taskId; await act(page, id, 'start'); await act(page, id, 'complete'); await page.goto('/housekeeping/tasks')
  expect((await page.request.get('/api/users')).status()).toBe(403); expect((await page.request.get('/api/stays')).status()).toBe(200); expect((await task(page, id)).status).toBe('COMPLETED')
})
test('损坏成功响应后保留标识，安全重试开始', async ({ page }) => {
  const id = fixtures.malformed!.taskId; await login(page); await search(page, 'malformed'); const keys: string[] = []
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/${id}/start`)) keys.push(r.headers()['idempotency-key']!) })
  await page.route(`**/api/housekeeping-tasks/${id}/start`, async route => { expect((await route.fetch()).status()).toBe(200); await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {} }) }) }, { times: 1 })
  await row(page, 'malformed').getByRole('button', { name: '开始打扫', exact: true }).click(); await confirm(page, '开始打扫'); const dialog = page.getByRole('dialog', { name: '确认开始打扫', exact: true }); await expect(dialog.getByText('操作响应不完整，请重试原清洁请求确认结果', { exact: true })).toBeVisible(); await dialog.getByRole('button', { name: '重试原清洁请求', exact: true }).click(); await expect(dialog).not.toBeVisible(); expect(new Set(keys).size).toBe(1)
})
test('真实人工清洁、入住、退房、再次清洁和再次入住的完整闭环', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER'); await search(page, 'cycle'); const firstTask = await manual(page, 'cycle')
  await act(page, firstTask, 'assign', { assigneeId: employees.HOTEL_MANAGER }); await act(page, firstTask, 'start'); await act(page, firstTask, 'complete')
  const body = { roomId: fixtures.cycle!.id, expectedCheckOutAt: new Date(Date.now() + 86400000).toISOString(), guests: [{ name: '虚构闭环测试住客', documentType: 'OTHER', documentNumber: 'HK-CYCLE-TEST001', primary: true }], notes: '' }
  const first = await post(page, '/api/stays', body); const out = await post(page, `/api/stays/${first.id}/check-out`, { segmentId: first.segmentId }); expect(out.taskId).not.toBe(firstTask)
  await act(page, out.taskId, 'assign', { assigneeId: employees.HOTEL_MANAGER }); await act(page, out.taskId, 'start'); await act(page, out.taskId, 'complete')
  await page.goto('/rooms'); await page.getByLabel('房号搜索', { exact: true }).fill(fixtures.cycle!.number); await page.getByRole('button', { name: '查询', exact: true }).click(); await expect(page.getByRole('button', { name: `查看房间 ${fixtures.cycle!.number}`, exact: true }).locator('.room-status-pill')).toHaveText('空闲可入住')
  const second = await post(page, '/api/stays', body); expect(second.id).not.toBe(first.id)
  const list = (await (await page.request.get(`/api/housekeeping-tasks?roomNumber=${fixtures.cycle!.number}&status=COMPLETED`)).json()).data; expect(list.total).toBe(2)
  expect(JSON.stringify(list)).not.toMatch(/document|phone|HK-CYCLE|虚构闭环测试住客/)
})
