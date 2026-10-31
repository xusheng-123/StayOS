import { test, expect, type Page } from '@playwright/test'
import type { Stay, StayExtension } from '../src/types/stay'

const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_EXTENSION_FIXTURES || '{}') as Record<string, Stay>
if (!password || !fixtures.normal) throw new Error('Run via the isolated JUnit extension launcher')
// The operational date entry must still mean Beijing time on an overseas browser.
test.use({ timezoneId: 'America/Los_Angeles' })
async function login(page: Page, role = 'FRONT_DESK') {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`extension_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}
async function get<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(path); expect(response.status()).toBe(200); return (await response.json()).data as T
}
async function post(page: Page, path: string, data: unknown) {
  const csrf = await get<{ headerName: string; token: string }>(page, '/api/auth/csrf')
  const response = await page.request.post(path, { data, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() } })
  expect(response.status()).toBe(200); return (await response.json()).data
}
const dialog = (page: Page) => page.getByRole('dialog', { name: '办理续住与预计离店调整', exact: true })
function beijing(date: Date) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date)
}
const future = () => new Date(Math.floor((Date.now() + 2 * 86400000) / 1000) * 1000)
async function open(page: Page, stay: Stay) {
  await page.goto(`/stays/${stay.id}`); await page.getByRole('button', { name: '办理续住', exact: true }).click(); await expect(dialog(page)).toBeVisible()
}
async function fill(page: Page, date: Date, reason = '虚构续住计划调整') {
  await dialog(page).getByLabel('新的预计退房时间（北京时间）', { exact: true }).fill(beijing(date))
  await dialog(page).getByLabel('新的预计退房时间（北京时间）', { exact: true }).press('Enter')
  await dialog(page).getByLabel('调整原因', { exact: true }).click()
  await dialog(page).getByLabel('调整原因', { exact: true }).fill(reason)
}
async function submit(page: Page) { await dialog(page).getByRole('button', { name: '确认续住', exact: true }).click() }
async function success(page: Page) { await expect(page.getByRole('dialog', { name: '续住办理成功', exact: true })).toBeVisible() }

test('续住更新同一入住、历史与房态，海外浏览器仍按北京时间输入', async ({ page }) => {
  await login(page); const stay = fixtures.normal!; const date = future(); await open(page, stay); await fill(page, date); await submit(page); await success(page)
  const current = await get<Stay>(page, `/api/stays/${stay.id}`)
  expect(current.id).toBe(stay.id); expect(current.segmentId).toBe(stay.segmentId); expect(current.roomId).toBe(stay.roomId)
  expect(current.status).toBe('IN_HOUSE'); expect(current.version).toBe(stay.version + 1)
  expect(Date.parse(current.expectedCheckOutAt)).toBe(date.getTime())
  const history = await get<{ items: StayExtension[]; total: number }>(page, `/api/stays/${stay.id}/extensions`)
  expect(history.total).toBe(1); expect(history.items[0]!.reason).toBe('虚构续住计划调整')
  expect((await get<{ occupied: boolean; cleaningStatus: string }>(page, `/api/rooms/${stay.roomId}`))).toMatchObject({ occupied: true, cleaningStatus: 'CLEAN' })
  await page.getByRole('dialog', { name: '续住办理成功', exact: true }).getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '续住办理成功', exact: true })).toBeHidden()
  await expect(page.getByRole('region', { name: '续住与预计离店调整历史' })).toContainText('虚构续住计划调整')
  await page.screenshot({ path: '../.local/extensions-desktop.png', fullPage: true, animations: 'disabled' })
})
test('总经理可以缩短离店计划，仍保持在住', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER'); const stay = fixtures.shorter!; await open(page, stay)
  const date = new Date(Math.floor((Date.now() + 5 * 3600000) / 1000) * 1000); await fill(page, date, '虚构提前离店计划'); await submit(page); await success(page)
  const current = await get<Stay>(page, `/api/stays/${stay.id}`); expect(current.status).toBe('IN_HOUSE'); expect(Date.parse(current.expectedCheckOutAt)).toBe(date.getTime())
  const extension = (await get<{ items: StayExtension[] }>(page, `/api/stays/${stay.id}/extensions`)).items[0]!
  const audit = (await get<{ items: { id: number; targetId: number }[] }>(page, '/api/audit-logs?action=STAY_EXTEND')).items.find(item => item.targetId === extension.id)!
  expect(audit).toBeTruthy(); await page.goto('/audit-logs')
  await page.getByRole('combobox', { name: '审计操作类型', exact: true }).click()
  await page.getByRole('option', { name: '续住与预计离店调整', exact: true }).click()
  await page.getByRole('button', { name: '查询操作记录', exact: true }).click()
  await expect(page.getByRole('button', { name: `查看操作记录 ${audit.id}`, exact: true })).toContainText('续住与预计离店调整')
})
test('未填写原因和无变化时间不能产生续住记录', async ({ page }) => {
  await login(page); const stay = fixtures.validation!; await open(page, stay)
  await expect(dialog(page).getByRole('button', { name: '确认续住', exact: true })).toBeDisabled()
  await fill(page, new Date(stay.expectedCheckOutAt)); await submit(page)
  await expect(dialog(page).getByRole('alert')).toContainText('与原时间相同')
  expect((await get<{ total: number }>(page, `/api/stays/${stay.id}/extensions`)).total).toBe(0)
})
test('成功响应丢失后用同一标识重试，仅产生一条历史', async ({ page }) => {
  await login(page); const stay = fixtures.lost!; await open(page, stay); await fill(page, future())
  const keys: string[] = []; page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith(`/api/stays/${stay.id}/extensions`)) keys.push(request.headers()['idempotency-key']!) })
  await page.route(`**/api/stays/${stay.id}/extensions`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await submit(page); await expect(dialog(page).getByRole('button', { name: '重试原续住请求', exact: true })).toBeEnabled()
  await expect(dialog(page).getByLabel('调整原因', { exact: true })).toBeDisabled()
  await dialog(page).getByRole('button', { name: '重试原续住请求', exact: true }).click(); await success(page)
  expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1)
  expect((await get<{ total: number }>(page, `/api/stays/${stay.id}/extensions`)).total).toBe(1)
})
test('刷新后仅查询原回执，不持久化正文或自动重新提交', async ({ page }) => {
  await login(page); const stay = fixtures.reload!; await open(page, stay); await fill(page, future(), '禁止持久化的虚构调整正文')
  let writes = 0; page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith(`/api/stays/${stay.id}/extensions`)) writes++ })
  await page.route(`**/api/stays/${stay.id}/extensions`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await submit(page); await expect(dialog(page).getByRole('button', { name: '查询原续住结果', exact: true })).toBeEnabled()
  expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }))).not.toContain('禁止持久化的虚构调整正文')
  await page.reload(); await expect(dialog(page).getByRole('button', { name: '查询原续住结果', exact: true })).toBeEnabled(); expect(writes).toBe(1)
  await dialog(page).getByRole('button', { name: '查询原续住结果', exact: true }).click(); await success(page); expect(writes).toBe(1)
})
test('另一人先调整后，旧页面不会覆盖新计划', async ({ page }) => {
  await login(page); const stay = fixtures.stale!; await open(page, stay); await fill(page, future())
  const date = new Date(Math.floor((Date.now() + 4 * 86400000) / 1000) * 1000)
  await post(page, `/api/stays/${stay.id}/extensions`, { segmentId: stay.segmentId, version: stay.version, newExpectedCheckOutAt: date.toISOString(), reason: '虚构他人先修改' })
  await submit(page); await expect(dialog(page).getByRole('alert')).toContainText('入住记录已变化')
  expect(Date.parse((await get<Stay>(page, `/api/stays/${stay.id}`)).expectedCheckOutAt)).toBe(date.getTime())
})
test('390px手机续住可操作且没有整页横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await open(page, fixtures.mobile!); await fill(page, future())
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: '../.local/extensions-mobile.png', animations: 'disabled' }); await submit(page); await success(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
})
test('已退房详情不展示办理续住入口', async ({ page }) => {
  await login(page); const stay = fixtures.ended!; await post(page, `/api/stays/${stay.id}/check-out`, { segmentId: stay.segmentId })
  await page.goto(`/stays/${stay.id}`); await expect(page.getByRole('heading', { name: '入住详情', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '办理续住', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '续住与预计离店调整历史' })).toContainText('暂无续住')
})
for (const role of ['ADMIN', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
  test(`${role}不能进入入住详情或访问续住历史`, async ({ page }) => {
    await login(page, role); await page.goto(`/stays/${fixtures.normal!.id}`)
    await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
    expect((await page.request.get(`/api/stays/${fixtures.normal!.id}/extensions`)).status()).toBe(403)
  })
}
