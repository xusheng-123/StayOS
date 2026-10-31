import { test, expect, type Page } from '@playwright/test'
import type { Stay } from '../src/types/stay'
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_CORRECTION_FIXTURES || '{}') as Record<string, Stay>
if (!password || !fixtures.normal) throw new Error('Run via isolated JUnit correction launcher')
async function login(page: Page, role = 'FRONT_DESK') {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`correction_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}
const dialog = (page: Page) => page.getByRole('dialog', { name: '更正住客资料', exact: true })
const path = (stay: Stay) => `/api/stays/${stay.id}/guests/${stay.guests![0]!.id}/corrections`
async function get<T>(page: Page, url: string): Promise<T> { const r = await page.request.get(url); expect(r.status()).toBe(200); return (await r.json()).data as T }
async function post(page: Page, url: string, data: unknown) {
  const csrf = await get<{ headerName: string; token: string }>(page, '/api/auth/csrf')
  const r = await page.request.post(url, { data, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() } }); expect(r.status()).toBe(200)
}
async function open(page: Page, stay: Stay) { await page.goto(`/stays/${stay.id}`); await page.getByRole('button', { name: '更正住客资料', exact: true }).click() }
async function fill(page: Page) {
  await dialog(page).getByText('更正姓名', { exact: true }).click()
  await dialog(page).getByLabel('更正后的姓名', { exact: true }).fill('虚构浏览器更正')
  await dialog(page).getByLabel('更正原因', { exact: true }).fill('虚构登记笔误核验')
}
async function submit(page: Page) { await dialog(page).getByRole('button', { name: '保存更正', exact: true }).click() }
async function success(page: Page) { await expect(page.getByRole('dialog', { name: '住客资料更正成功', exact: true })).toBeVisible() }
test('更正证件姓名，详情和脱敏历史一致，存储不保存敏感正文', async ({ page }) => {
  await login(page); const stay = fixtures.normal!; await open(page, stay); await fill(page)
  await dialog(page).getByText('更正证件', { exact: true }).click()
  await expect(dialog(page).getByLabel('更正后的完整证件号码', { exact: true })).toHaveValue('')
  await dialog(page).getByLabel('更正后的证件类型', { exact: true }).click(); await page.getByRole('option', { name: '护照', exact: true }).click()
  await dialog(page).getByLabel('更正后的完整证件号码', { exact: true }).fill('PA9988776')
  await submit(page); await success(page)
  await page.getByRole('dialog', { name: '住客资料更正成功' }).getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '住客资料更正成功' })).toBeHidden()
  await expect(page.getByRole('region', { name: '住客更正历史' })).toContainText('PA****76')
  await expect(page.locator('body')).not.toContainText('PA9988776')
  expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }))).not.toContain('PA9988776')
  await page.screenshot({ path: '../.local/corrections-desktop.png', fullPage: true, animations: 'disabled' })
})
test('总经理可清空电话并查看集中更正审计', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER'); const stay = fixtures.phone!
  await post(page, path(stay), { version: 0, changes: { phone: '13900000997' }, reason: '虚构电话补正' })
  await open(page, stay); await dialog(page).getByText('更正联系电话', { exact: true }).click()
  await expect(dialog(page).getByLabel('更正后的联系电话', { exact: true })).toHaveValue('')
  await dialog(page).getByLabel('更正原因', { exact: true }).fill('虚构清空误记号码'); await submit(page); await success(page)
  expect((await get<Stay>(page, `/api/stays/${stay.id}`)).guests![0]!.phone).toBe('')
  await page.goto('/audit-logs'); await page.getByRole('combobox', { name: '审计操作类型', exact: true }).click()
  await page.getByRole('option', { name: '住客资料更正', exact: true }).click(); await page.getByRole('button', { name: '查询操作记录', exact: true }).click()
  await expect(page.getByRole('button', { name: /查看操作记录/ }).first()).toContainText('住客资料更正')
})
test('未选择字段和缺原因不能保存，关闭清除敏感输入', async ({ page }) => {
  await login(page); await open(page, fixtures.validation!); await submit(page); await expect(dialog(page).getByRole('alert')).toContainText('请填写更正原因')
  await dialog(page).getByLabel('更正原因', { exact: true }).fill('虚构原因'); await submit(page); await expect(dialog(page).getByRole('alert')).toContainText('请选择需要更正的字段')
  await dialog(page).getByText('更正联系电话', { exact: true }).click(); await dialog(page).getByLabel('更正后的联系电话', { exact: true }).fill('13900000998')
  await dialog(page).getByRole('button', { name: '暂时关闭', exact: true }).click(); await expect(dialog(page)).toBeHidden()
  await page.getByRole('button', { name: '更正住客资料', exact: true }).click(); await dialog(page).getByText('更正联系电话', { exact: true }).click()
  await expect(dialog(page).getByLabel('更正后的联系电话', { exact: true })).toHaveValue('')
})
test('成功响应丢失后使用原标识重试，不重复写历史', async ({ page }) => {
  await login(page); const stay = fixtures.lost!; await open(page, stay); await fill(page); const keys: string[] = []
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(path(stay))) keys.push(r.headers()['idempotency-key']!) })
  await page.route(`**${path(stay)}`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await submit(page); await expect(dialog(page).getByRole('button', { name: '重试原更正请求' })).toBeEnabled()
  await dialog(page).getByRole('button', { name: '重试原更正请求' }).click(); await success(page)
  expect(keys.length).toBe(2); expect(new Set(keys).size).toBe(1); expect((await get<{ total: number }>(page, path(stay))).total).toBe(1)
})
test('刷新仅查询原回执，不保存号码或自动重发', async ({ page }) => {
  await login(page); const stay = fixtures.reload!; await open(page, stay); await fill(page)
  await dialog(page).getByText('更正联系电话', { exact: true }).click(); await dialog(page).getByLabel('更正后的联系电话', { exact: true }).fill('13900000996')
  let writes = 0; page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(path(stay))) writes++ })
  await page.route(`**${path(stay)}`, async route => { expect((await route.fetch()).status()).toBe(200); await route.abort('failed') }, { times: 1 })
  await submit(page); await expect(dialog(page).getByRole('button', { name: '查询原更正结果' })).toBeEnabled()
  expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage, ...localStorage }))).not.toContain('13900000996')
  await page.reload(); await dialog(page).getByRole('button', { name: '查询原更正结果' }).click(); await success(page); expect(writes).toBe(1)
})
test('旧版本提交拒绝覆盖其他员工更正', async ({ page }) => {
  await login(page); const stay = fixtures.stale!; await open(page, stay); await fill(page)
  await post(page, path(stay), { version: 0, changes: { name: '虚构先行更正' }, reason: '虚构先行核验' }); await submit(page)
  await expect(dialog(page).getByRole('alert')).toContainText('住客资料已变化')
  expect((await get<Stay>(page, `/api/stays/${stay.id}`)).guests![0]!.name).toBe('虚构先行更正')
})
test('390px手机更正可操作，无整页横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await open(page, fixtures.mobile!); await fill(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  const bounds = await dialog(page).boundingBox(); expect(bounds).toBeTruthy(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(391)
  await page.screenshot({ path: '../.local/corrections-mobile.png', animations: 'disabled' }); await submit(page); await success(page)
})
test('退房后保留历史但不显示更正入口', async ({ page }) => {
  await login(page); const stay = fixtures.ended!; await post(page, path(stay), { version: 0, changes: { name: '虚构历史更正' }, reason: '虚构退房前核验' })
  await post(page, `/api/stays/${stay.id}/check-out`, { segmentId: stay.segmentId }); await page.goto(`/stays/${stay.id}`)
  await expect(page.getByRole('button', { name: '更正住客资料', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '住客更正历史' })).toContainText('虚构退房前核验')
})
test('更正历史支持失败重试和服务端分页', async ({ page }) => {
  await login(page); const stay = fixtures.history!
  for (let version = 0; version < 11; version++) await post(page, path(stay), { version, changes: { name: `虚构历史${version}` }, reason: `虚构历史原因${version}` })
  await page.route(`**${path(stay)}?*`, route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'SERVICE_UNAVAILABLE', message: '虚构暂时不可用' }) }), { times: 1 })
  await page.goto(`/stays/${stay.id}`); const history = page.getByRole('region', { name: '住客更正历史' })
  await expect(history.getByRole('alert')).toContainText('虚构暂时不可用'); await history.getByRole('button', { name: '重试更正历史' }).click()
  await expect(history).toContainText('虚构历史原因10'); await history.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(history).toContainText('虚构历史原因0'); await expect(history).not.toContainText('虚构历史原因10')
})
for (const role of ['ADMIN', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) test(`${role}无权访问更正页面或历史`, async ({ page }) => {
  await login(page, role); await page.goto(`/stays/${fixtures.normal!.id}`); await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
  expect((await page.request.get(path(fixtures.normal!))).status()).toBe(403)
})
