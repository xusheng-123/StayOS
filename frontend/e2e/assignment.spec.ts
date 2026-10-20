import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Runtime credentials required')
const rooms = JSON.parse(process.env.STAYOS_HK_FIXTURES || '{}') as Record<string, { id: number; number: string; taskId?: number }>
const people = JSON.parse(process.env.STAYOS_HK_EMPLOYEES || '{}') as Record<string, number>
async function login(page: Page, role = 'HOUSEKEEPING_MANAGER') {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`hk_test_${role.toLowerCase()}`); await page.getByLabel('密码', { exact: true }).fill(password!)
  await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).not.toHaveURL(/\/login/)
}
async function post(page: Page, path: string, body: unknown, key = crypto.randomUUID()) {
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  return page.request.post(path, { data: body, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key } })
}
async function choose(page: Page, employee = 'HOUSEKEEPING_STAFF') {
  await page.goto('/housekeeping/assignment'); await page.getByRole('button', { name: `选择员工 清洁测试${employee}`, exact: true }).click()
  await expect(page.getByRole('heading', { name: `清洁测试${employee} · 当前负责房间`, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '全选当前筛选可分配房间', exact: true })).toBeEnabled()
}
async function select(page: Page, ...names: string[]) {
  for (const name of names) await page.getByRole('checkbox', { name: `分配房间 ${rooms[name]!.number}`, exact: true }).check()
}
async function confirmation(page: Page) {
  await page.getByRole('button', { name: '确认分配', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '确认批量分房', exact: true }); await expect(dialog).toBeVisible(); return dialog
}
for (const role of ['ADMIN', 'FRONT_DESK', 'HOUSEKEEPING_STAFF', 'HOTEL_MANAGER', 'HOUSEKEEPING_MANAGER']) {
  test(`${role}: 人员分房导航、路由及后端按最终权限控制`, async ({ page }) => {
    await login(page, role)
    const allowed = ['HOTEL_MANAGER', 'HOUSEKEEPING_MANAGER'].includes(role)
    await expect(page.getByRole('link', { name: /人员分房/ })).toHaveCount(allowed ? 1 : 0)
    await page.goto('/housekeeping/assignment')
    if (allowed) await expect(page.getByRole('heading', { name: '人员分房', exact: true })).toBeVisible()
    else {
      await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
      expect((await page.request.get('/api/housekeeping-tasks/assignment-employees')).status()).toBe(403)
      expect((await post(page, '/api/housekeeping-tasks/batch-assign', { assigneeId: people.HOUSEKEEPING_STAFF, roomIds: [rooms.batchA!.id] })).status()).toBe(403)
    }
    expect((await post(page, `/api/housekeeping-tasks/${rooms.batchExisting!.taskId}/claim`, { version: 0 })).status()).toBe(403)
  })
}
test('员工卡片真实工作量、当前房间与筛选多选清空', async ({ page }) => {
  await login(page); await choose(page)
  const employees = (await (await page.request.get('/api/housekeeping-tasks/assignment-employees')).json()).data
  const current = employees.find((e: { id: number }) => e.id === people.HOUSEKEEPING_STAFF)
  const card = page.getByRole('button', { name: '选择员工 清洁测试HOUSEKEEPING_STAFF', exact: true })
  await expect(card).toContainText(`待开始 ${current.pending}`); await expect(card).toContainText('已启用')
  await expect(page.locator('.assignment-current')).toContainText(rooms.network!.number)
  await page.getByLabel('分房房号搜索', { exact: true }).fill('S5_BATCH0')
  await page.getByRole('combobox', { name: '分房楼层', exact: true }).click(); await page.getByRole('option', { name: '5 楼', exact: true }).click()
  await page.getByRole('combobox', { name: '分房房型', exact: true }).click(); await page.getByRole('option', { name: '虚构清洁测试房型', exact: true }).click()
  await page.getByRole('button', { name: '筛选房间', exact: true }).click(); await expect(page.getByRole('checkbox')).toHaveCount(9)
  await page.getByRole('button', { name: '全选当前筛选可分配房间', exact: true }).click(); await expect(page.getByText('已选 9 间房', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: `取消 ${rooms.batchA!.number}`, exact: true }).click(); await expect(page.getByText('已选 8 间房', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '清空选择', exact: true }).click(); await expect(page.getByText('已选 0 间房', { exact: true })).toBeVisible()
})
test('一次确认混合分配三间房，员工看到任务并正常完成', async ({ page, browser }) => {
  await login(page); await choose(page); await select(page, 'batchA', 'batchB', 'batchExisting')
  const calls: string[] = []; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/batch-assign')) calls.push(r.url()) })
  const dialog = await confirmation(page); await expect(dialog).toContainText('清洁测试HOUSEKEEPING_STAFF'); await expect(dialog).toContainText('3 间房')
  for (const name of ['batchA', 'batchB', 'batchExisting']) await expect(dialog).toContainText(rooms[name]!.number)
  await dialog.getByRole('button', { name: '确认本批分房', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '分房成功：3 间房' })).toBeVisible(); expect(calls).toHaveLength(1)
  for (const name of ['batchA', 'batchB', 'batchExisting']) { await expect(page.locator('.assignment-current')).toContainText(rooms[name]!.number); await expect(page.getByRole('checkbox', { name: `分配房间 ${rooms[name]!.number}`, exact: true })).toBeDisabled() }
  const existing = (await (await page.request.get(`/api/housekeeping-tasks?roomNumber=${rooms.batchExisting!.number}`)).json()).data; expect(existing.total).toBe(1); expect(existing.items[0].id).toBe(rooms.batchExisting!.taskId)
  const context = await browser.newContext(); const staff = await context.newPage(); await login(staff, 'HOUSEKEEPING_STAFF'); await staff.goto('/housekeeping/tasks')
  for (const name of ['batchA', 'batchB', 'batchExisting']) await expect(staff.getByRole('row').filter({ hasText: rooms[name]!.number })).toBeVisible()
  await expect(staff.getByRole('button', { name: '领取任务', exact: true })).toHaveCount(0)
  const list = (await (await staff.request.get(`/api/housekeeping-tasks?roomNumber=${rooms.batchA!.number}`)).json()).data; const id = list.items[0].id
  for (const action of ['start', 'complete']) { const task = (await (await staff.request.get(`/api/housekeeping-tasks/${id}`)).json()).data; expect((await post(staff, `/api/housekeeping-tasks/${id}/${action}`, { version: task.version })).status()).toBe(200) }
  const room = (await (await staff.request.get(`/api/rooms/${rooms.batchA!.id}`)).json()).data; expect(room.cleaningStatus).toBe('CLEAN'); expect(room.canCheckIn).toBe(true)
  await context.close(); await page.screenshot({ path: '../.local/batch-desktop.png', fullPage: true, animations: 'disabled' })
})
test('旧列表冲突时整批拒绝，刷新且不保留前一房间分配', async ({ page }) => {
  await login(page); await choose(page); await select(page, 'batchConflictA', 'batchConflictB')
  const dialog = await confirmation(page)
  expect((await post(page, '/api/housekeeping-tasks/batch-assign', { assigneeId: people.STAFF2, roomIds: [rooms.batchConflictB!.id] })).status()).toBe(200)
  await dialog.getByRole('button', { name: '确认本批分房', exact: true }).click(); await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: /本批未执行/ })).toBeVisible()
  const roomsResult = (await (await page.request.get(`/api/housekeeping-tasks/assignment-rooms?roomNumber=${rooms.batchConflictA!.number}`)).json()).data
  expect(roomsResult.items[0].taskId).toBeNull(); expect(roomsResult.items[0].assignable).toBe(true)
  await expect(page.getByRole('checkbox', { name: `分配房间 ${rooms.batchConflictB!.number}`, exact: true })).toBeDisabled()
})
test('分房响应丢失后同标识安全重试不重复建单', async ({ page }) => {
  await login(page); await choose(page); await select(page, 'batchNetwork')
  const keys: string[] = []; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/batch-assign')) keys.push(r.headers()['idempotency-key']!) })
  await page.route('**/api/housekeeping-tasks/batch-assign', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  const dialog = await confirmation(page); await dialog.getByRole('button', { name: '确认本批分房', exact: true }).click(); await expect(dialog.getByRole('button', { name: '重试原批次', exact: true })).toBeEnabled(); await dialog.getByRole('button', { name: '重试原批次', exact: true }).click()
  await expect(dialog).not.toBeVisible(); await expect(page.getByRole('alert').filter({ hasText: '分房成功：1 间房' })).toBeVisible(); expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1)
  const tasks = (await (await page.request.get(`/api/housekeeping-tasks?roomNumber=${rooms.batchNetwork!.number}`)).json()).data; expect(tasks.total).toBe(1)
  const logs = (await (await page.request.get(`/api/housekeeping-tasks/${tasks.items[0].id}/history`)).json()).data; expect(logs.map((l: { action: string }) => l.action)).toEqual(['MANUAL_CREATE', 'ASSIGN'])
})
test('刷新后按原标识查回成功结果，不保存备注或重新发请求', async ({ page }) => {
  await login(page); await choose(page); await select(page, 'batchRefresh'); const dialog = await confirmation(page)
  await dialog.getByLabel('分房备注（选填）', { exact: true }).fill('虚构非持久化备注')
  await page.route('**/api/housekeeping-tasks/batch-assign', async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await dialog.getByRole('button', { name: '确认本批分房', exact: true }).click(); await expect(dialog.getByRole('button', { name: '重试原批次', exact: true })).toBeEnabled(); await page.reload()
  await expect(page.getByText('上次批量分房结果尚未确认，请查询原批次或安全重试。', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toMatch(/reason|body|虚构非持久化备注/)
  await page.getByRole('button', { name: '查询原批次结果', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '分房成功：1 间房' })).toBeVisible(); await expect(page.getByText('上次批量分房结果尚未确认，请查询原批次或安全重试。', { exact: true })).toHaveCount(0)
})
test('手机选择员工和两间房一键分配，无整页横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await choose(page, 'STAFF2'); await select(page, 'batchMobileA', 'batchMobileB')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  const dialog = await confirmation(page); await expect(dialog).toContainText('2 间房'); await dialog.getByRole('button', { name: '确认本批分房', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '分房成功：2 间房' })).toBeVisible()
  await page.screenshot({ path: '../.local/batch-mobile.png', fullPage: true, animations: 'disabled' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
})
