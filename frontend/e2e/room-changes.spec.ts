import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Runtime test credentials required')
type Room = { id: number; number: string }
const fixtures = JSON.parse(process.env.STAYOS_CHANGE_FIXTURES || '{}') as Record<string, { a: Room; b: Room; c: Room }>
async function login(page: Page, role = 'FRONT_DESK') {
 await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`roomchange_test_${role.toLowerCase()}`); await page.getByLabel('密码', { exact: true }).fill(password!); await page.getByRole('button', { name: '登录', exact: true }).click(); await expect(page).not.toHaveURL(/\/login/)
}
async function post(page: Page, path: string, body: unknown, key = crypto.randomUUID()) {
 const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
 return page.request.post(path, { data: body, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key } })
}
async function okPost(page: Page, path: string, body: unknown) { const r = await post(page, path, body); expect(r.status()).toBe(200); return (await r.json()).data }
async function get(page: Page, path: string) { const r = await page.request.get(path); expect(r.status()).toBe(200); return (await r.json()).data }
async function create(page: Page, name: string) {
 return okPost(page, '/api/stays', { roomId: fixtures[name]!.a.id, expectedCheckOutAt: new Date(Date.now() + 86400000).toISOString(), guests: [{ name: '虚构界面换房住客', documentType: 'OTHER', documentNumber: 'UI-ROOM-CHANGE-001', primary: true }], notes: '' })
}
function dialog(page: Page) { return page.getByRole('dialog', { name: '办理换房', exact: true }) }
async function open(page: Page, stay: { id: number }) { await page.goto(`/stays/${stay.id}`); await page.getByRole('button', { name: '办理换房', exact: true }).click(); await expect(dialog(page)).toBeVisible() }
async function select(page: Page, room: Room) {
 await dialog(page).getByLabel('换房目标房号', { exact: true }).fill(room.number); await dialog(page).getByRole('button', { name: '筛选目标房', exact: true }).click(); await dialog(page).getByRole('button', { name: `换入房间 ${room.number}`, exact: true }).click()
}
async function submit(page: Page) { await dialog(page).getByRole('button', { name: '确认办理换房', exact: true }).click(); const confirm = page.getByRole('dialog', { name: '确认正式换房', exact: true }); await expect(confirm).toBeVisible(); await confirm.getByRole('button', { name: '确认换房', exact: true }).click() }
async function success(page: Page) { await expect(page.getByRole('alert').filter({ hasText: '换房成功，原房清洁任务已创建' })).toBeVisible() }
for (const role of ['ADMIN', 'FRONT_DESK', 'HOTEL_MANAGER', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
 test(`${role}: 换房路由和接口权限`, async ({ page }) => {
  await login(page, role); await page.goto('/stays'); const allow = ['FRONT_DESK', 'HOTEL_MANAGER'].includes(role)
  if (allow) await expect(page.getByRole('heading', { name: '当前入住', exact: true })).toBeVisible()
  else await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
  expect((await post(page, '/api/stays/99999999/room-changes', { segmentId: 1, targetRoomId: 2, reasonType: 'OTHER', reason: '虚构权限验证' })).status()).toBe(allow ? 404 : 403)
 })
}
test('当前入住列表正式换房、历史、房态和最终退房', async ({ page }) => {
 await page.setViewportSize({ width: 1440, height: 1000 }); await login(page); const s = await create(page, 'normal'); await page.goto('/stays'); await page.getByLabel('房号搜索', { exact: true }).fill(fixtures.normal!.a.number); await page.getByRole('button', { name: '查询入住', exact: true }).click(); await page.getByRole('row').filter({ hasText: fixtures.normal!.a.number }).getByRole('button', { name: '办理换房', exact: true }).click()
 await expect(dialog(page)).toContainText('虚构界面换房住客'); await expect(dialog(page)).not.toContainText('UI-ROOM-CHANGE-001'); await select(page, fixtures.normal!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('虚构：调整楼层'); await page.screenshot({ path: '../.local/phase7-desktop.png', fullPage: true, animations: 'disabled' }); await submit(page); await success(page)
 await page.getByRole('link', { name: '查看本次入住历史', exact: true }).click(); await expect(page.locator('.stay-history-item').filter({ hasText: '虚构：调整楼层' })).toContainText(`${fixtures.normal!.a.number} → ${fixtures.normal!.b.number}`)
 const current = await get(page, `/api/stays/${s.id}`); expect(current.roomId).toBe(fixtures.normal!.b.id); expect(current.guests).toEqual(s.guests)
 expect((await get(page, `/api/rooms/${fixtures.normal!.a.id}`)).cleaningStatus).toBe('DIRTY'); expect((await get(page, `/api/rooms/${fixtures.normal!.b.id}`)).occupied).toBe(true)
 await page.getByRole('button', { name: '办理退房', exact: true }).click(); await page.getByRole('dialog', { name: '确认办理退房', exact: true }).getByRole('button', { name: '确认退房', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '退房成功，清洁任务创建成功' })).toBeVisible(); expect((await get(page, `/api/stays/${s.id}`)).roomSegments).toHaveLength(2)
})
test('同一次入住连续换房显示三段历史', async ({ page }) => {
 await login(page, 'HOTEL_MANAGER'); const s = await create(page, 'twice'); await open(page, s); await select(page, fixtures.twice!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('第一次换房'); await submit(page); await success(page); await page.getByRole('dialog', { name: '换房成功', exact: true }).getByRole('button', { name: '关闭', exact: true }).click()
 await page.getByRole('button', { name: '办理换房', exact: true }).click(); await select(page, fixtures.twice!.c); await dialog(page).getByLabel('换房说明', { exact: true }).fill('第二次换房'); await submit(page); await success(page); const d = await get(page, `/api/stays/${s.id}`); expect(d.roomSegments).toHaveLength(3); expect(d.roomChanges).toHaveLength(2); expect(d.id).toBe(s.id)
})
test('关联真实原房严重异常，换房不解除故障', async ({ page }) => {
 await login(page, 'HOTEL_MANAGER'); const s = await create(page, 'linked'); const issue = await okPost(page, '/api/room-issues', { roomId: fixtures.linked!.a.id, issueType: 'ELECTRICAL', severity: 'CRITICAL', description: '虚构空调故障' }); await open(page, s); await select(page, fixtures.linked!.b); await dialog(page).getByLabel('换房原因类型', { exact: true }).selectOption('ROOM_ISSUE'); await dialog(page).getByLabel('关联原房异常', { exact: true }).selectOption(String(issue.issueId)); await dialog(page).getByLabel('换房说明', { exact: true }).fill('空调故障，住客继续入住新房'); await submit(page); await success(page)
 const d = await get(page, `/api/stays/${s.id}`); expect(d.roomChanges[0].issueId).toBe(issue.issueId); expect((await get(page, `/api/room-issues/${issue.issueId}`)).status).toBe('OPEN'); expect((await get(page, `/api/rooms/${fixtures.linked!.a.id}`)).maintenanceStatus).toBe('OUT_OF_SERVICE')
})
test('提交前目标房新出现严重异常应拒绝旧页面', async ({ page }) => {
 await login(page, 'HOTEL_MANAGER'); const s = await create(page, 'stale'); await open(page, s); await select(page, fixtures.stale!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('旧页面校验'); await okPost(page, '/api/room-issues', { roomId: fixtures.stale!.b.id, issueType: 'SAFETY', severity: 'CRITICAL', description: '虚构提交前故障' }); await submit(page); await expect(dialog(page).getByRole('alert')).toContainText('房间正在维修或存在阻止入住的异常'); expect((await get(page, `/api/stays/${s.id}`)).roomId).toBe(fixtures.stale!.a.id)
})
test('无可用目标房时显示空状态，原因未填不能确认', async ({ page }) => {
 await login(page); const s = await create(page, 'empty'); await open(page, s); await dialog(page).getByLabel('换房目标房号', { exact: true }).fill('NO-SUCH-ROOM'); await dialog(page).getByRole('button', { name: '筛选目标房', exact: true }).click(); await expect(dialog(page).getByText('暂无符合条件的目标房间，请调整筛选或先处理房态', { exact: true })).toBeVisible(); await expect(dialog(page).getByRole('button', { name: '确认办理换房', exact: true })).toBeDisabled()
 await select(page, fixtures.empty!.b); await expect(dialog(page).getByRole('button', { name: '确认办理换房', exact: true })).toBeDisabled(); await dialog(page).getByLabel('换房说明', { exact: true }).fill('已填写'); await expect(dialog(page).getByRole('button', { name: '确认办理换房', exact: true })).toBeEnabled()
})
test('二次确认取消不执行换房', async ({ page }) => {
 await login(page); const s = await create(page, 'validation'); await open(page, s); await select(page, fixtures.validation!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('核对后取消'); await dialog(page).getByRole('button', { name: '确认办理换房', exact: true }).click(); await page.getByRole('dialog', { name: '确认正式换房', exact: true }).getByRole('button', { name: '继续核对', exact: true }).click(); expect((await get(page, `/api/stays/${s.id}`)).roomId).toBe(fixtures.validation!.a.id); expect(await get(page, `/api/stays/${s.id}/room-changes`)).toHaveLength(0)
})
test('丢失成功响应可原标识安全重试且不重复创建任务', async ({ page }) => {
 await login(page); const s = await create(page, 'network'); await open(page, s); await select(page, fixtures.network!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('虚构网络重试'); const keys: string[] = []; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/api/stays/${s.id}/room-changes`)) keys.push(r.headers()['idempotency-key']!) })
 await page.route(`**/api/stays/${s.id}/room-changes`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 }); await submit(page); await expect(dialog(page).getByRole('button', { name: '重试原换房请求', exact: true })).toBeEnabled(); await expect(dialog(page).getByLabel('换房说明', { exact: true })).toBeDisabled(); await dialog(page).getByRole('button', { name: '重试原换房请求', exact: true }).click(); await success(page); expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1); expect(await get(page, `/api/stays/${s.id}/room-changes`)).toHaveLength(1)
})
test('刷新后只查询原结果，不持久化换房说明或自动重发', async ({ page }) => {
 await login(page); const s = await create(page, 'refresh'); await open(page, s); await select(page, fixtures.refresh!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('不应保存的虚构换房正文'); let writes = 0; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/api/stays/${s.id}/room-changes`)) writes++ })
 await page.route(`**/api/stays/${s.id}/room-changes`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 }); await submit(page); await expect(dialog(page).getByRole('button', { name: '重试原换房请求', exact: true })).toBeEnabled(); expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }))).not.toContain('不应保存的虚构换房正文'); await page.reload(); await expect(page.getByRole('button', { name: '查询原换房结果', exact: true })).toBeVisible(); expect(writes).toBe(1); await page.getByRole('button', { name: '查询原换房结果', exact: true }).click(); await success(page); expect(writes).toBe(1)
})
test('手机筛选选房和确认无整页横向溢出', async ({ page }) => {
 await page.setViewportSize({ width: 390, height: 844 }); await login(page); const s = await create(page, 'mobile'); await open(page, s); await select(page, fixtures.mobile!.b); await dialog(page).getByLabel('换房说明', { exact: true }).fill('虚构手机换房'); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true); await page.screenshot({ path: '../.local/phase7-mobile.png', fullPage: false, animations: 'disabled' }); await submit(page); await success(page); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
})
test('换房任务接入原客房列表、主管批量分配和员工执行', async ({ page }) => {
 await login(page, 'HOTEL_MANAGER'); const s = await create(page, 'tasks'); const changed = await okPost(page, `/api/stays/${s.id}/room-changes`, { segmentId: s.segmentId, targetRoomId: fixtures.tasks!.b.id, reasonType: 'OTHER', reason: '虚构换房清洁联动' }); await okPost(page, '/api/housekeeping-tasks/batch-assign', { assigneeId: Number(process.env.STAYOS_CHANGE_STAFF_ID), roomIds: [fixtures.tasks!.a.id], reason: '分配换房清洁' }); await page.context().clearCookies(); await login(page, 'HOUSEKEEPING_STAFF'); await page.goto('/housekeeping/tasks'); await page.getByLabel('任务房号搜索', { exact: true }).fill(fixtures.tasks!.a.number); await page.getByRole('button', { name: '查询任务', exact: true }).click(); let row = page.getByRole('row').filter({ hasText: fixtures.tasks!.a.number }); await expect(row).toContainText('换房清洁'); await row.getByRole('button', { name: '开始打扫', exact: true }).click(); await page.getByRole('dialog', { name: '确认开始打扫', exact: true }).getByRole('button', { name: '确认开始打扫', exact: true }).click()
 await page.getByRole('combobox', { name: '任务状态筛选', exact: true }).click(); await page.getByRole('option', { name: '正在打扫', exact: true }).click(); await page.getByRole('button', { name: '查询任务', exact: true }).click(); row = page.getByRole('row').filter({ hasText: fixtures.tasks!.a.number }); await row.getByRole('button', { name: '完成清洁', exact: true }).click(); await page.getByRole('dialog', { name: '确认完成清洁', exact: true }).getByRole('button', { name: '确认完成清洁', exact: true }).click(); await expect(page.getByRole('alert').filter({ hasText: '完成清洁成功' })).toBeVisible(); expect((await get(page, `/api/housekeeping-tasks/${changed.taskId}`)).status).toBe('COMPLETED'); expect((await get(page, `/api/rooms/${fixtures.tasks!.a.id}`)).canCheckIn).toBe(true)
})
test('已退房详情没有可用换房入口', async ({ page }) => {
 await login(page); const s = await create(page, 'ended'); await okPost(page, `/api/stays/${s.id}/check-out`, { segmentId: s.segmentId }); await page.goto(`/stays/${s.id}`); await expect(page.getByRole('button', { name: '办理换房', exact: true })).toHaveCount(0); await expect(page.getByText('本次入住暂无换房记录。', { exact: true })).toBeVisible()
})
