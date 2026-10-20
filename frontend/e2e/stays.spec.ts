import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Runtime test credentials required')
const fixtures = JSON.parse(process.env.STAYOS_STAY_FIXTURES || '{}') as Record<string, { id: number; number: string }>
async function login(page: Page, role = 'FRONT_DESK') {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(`stay_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}
async function select(page: Page, fixture: string) {
  await page.goto('/stays/new')
  await page.getByRole('button', { name: `选择房间 ${fixtures[fixture]!.number}`, exact: true }).click()
}
async function guest(page: Page, i: number, name: string, doc: string) {
  await page.getByLabel(`住客 ${i} 姓名`, { exact: true }).fill(name)
  await page.getByRole('combobox', { name: `住客 ${i} 证件类型`, exact: true }).click()
  await page.getByRole('listbox', { name: `住客 ${i} 证件类型`, exact: true }).getByRole('option', { name: '其他证件', exact: true }).click()
  await page.getByLabel(`住客 ${i} 证件号码`, { exact: true }).fill(doc)
}
async function time(page: Page) {
  const tomorrow = new Date(Date.now() + 86400000 * 2)
  const day = tomorrow.toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
  await page.getByRole('combobox', { name: '预计退房时间', exact: true }).fill(`${day} 12:00`)
  await page.getByRole('heading', { name: '办理入住', exact: true }).click()
}
for (const role of ['ADMIN', 'HOTEL_MANAGER', 'FRONT_DESK', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
  test(`${role}: 入住导航与路由权限正确`, async ({ page }) => {
    await login(page, role)
    const allowed = ['HOTEL_MANAGER', 'FRONT_DESK'].includes(role)
    await expect(page.getByRole('navigation', { name: '入住功能' })).toHaveCount(allowed ? 1 : 0)
    await page.goto('/stays')
    if (allowed) await expect(page.getByRole('heading', { name: '当前入住', exact: true })).toBeVisible()
    else await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
  })
}
test('选房仅列可入住房间，筛选空结果明确提示', async ({ page }) => {
  await login(page); await page.goto('/stays/new')
  await expect(page.getByRole('button', { name: `选择房间 ${fixtures.dirty!.number}`, exact: true })).toHaveCount(0)
  await page.getByLabel('入住选房房号').fill('NO_SUCH_ROOM')
  await page.getByRole('button', { name: '查询房间', exact: true }).click()
  await expect(page.getByText('暂无可入住房间', { exact: true })).toBeVisible()
})
test('登记两名住客、详情脱敏、列表搜索及房态联动', async ({ page }) => {
  await login(page); await select(page, 'multi')
  await guest(page, 1, '虚构界面住客甲', 'UI-FICTIONAL-ALPHA001')
  await page.getByLabel('住客 1 联系电话').fill('000-000-0000')
  await page.getByRole('button', { name: '添加同行住客', exact: true }).click()
  await guest(page, 2, '虚构界面住客乙', 'UI-FICTIONAL-BETA002'); await time(page)
  await page.screenshot({ path: '../.local/stay-check-in.png', fullPage: true })
  await page.getByRole('button', { name: '确认入住', exact: true }).click()
  await expect(page).toHaveURL(/\/stays\/\d+$/)
  await expect(page.getByRole('heading', { name: '虚构界面住客乙', exact: true })).toBeVisible()
  await expect(page.getByText('主要联系人', { exact: true })).toBeVisible()
  await expect(page.getByText('在住', { exact: true })).toBeVisible()
  await expect(page.getByText('其他证件：UI****01', { exact: true })).toBeVisible()
  await expect(page.getByText('联系电话：00****00', { exact: true })).toBeVisible()
  expect(await page.locator('body').innerText()).not.toContain('UI-FICTIONAL-ALPHA001')
  await page.screenshot({ path: '../.local/stay-detail.png', fullPage: true })
  await page.getByRole('link', { name: '返回当前入住', exact: true }).click()
  await page.getByLabel('住客姓名', { exact: true }).fill('虚构界面住客乙')
  await page.getByRole('button', { name: '查询入住', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: fixtures.multi!.number })).toHaveCount(1)
  await page.goto('/rooms')
  const card = page.getByRole('button', { name: `查看房间 ${fixtures.multi!.number}`, exact: true })
  await expect(card.locator('.room-status-pill')).toHaveText('已入住')
  const room = (await (await page.request.get(`/api/rooms/${fixtures.multi!.id}`)).json()).data
  expect(room.occupied).toBe(true); expect(room.cleaningStatus).toBe('CLEAN'); expect(room.canCheckIn).toBe(false)
  await expect(page.getByTestId('stat-occupied')).toHaveText('1')
  await page.goto('/stays/history'); await expect(page.getByText('暂无入住记录', { exact: true })).toBeVisible()
})
test('服务器成功但响应丢失时沿用请求标识重试，只产生一次入住', async ({ page }) => {
  await login(page); await select(page, 'network'); await guest(page, 1, '虚构网络测试', 'UI-NETWORK-TEST001'); await time(page)
  const keys: string[] = []
  await page.route('**/api/stays', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    keys.push(route.request().headers()['idempotency-key']!)
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    await route.abort('failed')
  }, { times: 1 })
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/stays') && keys.length) keys.push(request.headers()['idempotency-key']!) })
  await page.getByRole('button', { name: '确认入住', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原入住请求', exact: true })).toBeVisible()
  await expect(page.getByLabel('住客 1 姓名', { exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '重试原入住请求', exact: true }).click()
  await expect(page).toHaveURL(/\/stays\/\d+$/)
  expect(keys.length).toBeGreaterThanOrEqual(2); expect(new Set(keys).size).toBe(1)
  const list = (await (await page.request.get(`/api/stays?roomNumber=${fixtures.network!.number}`)).json()).data
  expect(list.total).toBe(1)
})
test('刷新后只保存防重标识，提示先核对结果，不在浏览器存住客资料', async ({ page }) => {
  await login(page)
  await page.evaluate(async () => {
    const me = (await (await fetch('/api/auth/me')).json()).data
    sessionStorage.setItem(`stayos:check-in-pending:${me.id}`, crypto.randomUUID())
  })
  await page.goto('/stays/new')
  await expect(page.getByText('检测到上次入住结果尚未确认。请先查询当前入住记录，避免重复操作。', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认入住', exact: true })).toBeDisabled()
  expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain('FICTIONAL')
})
test('手机登记布局可用，删除同行住客后人数正确', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await login(page); await select(page, 'mobile')
  await page.getByRole('button', { name: '添加同行住客', exact: true }).click()
  await page.getByRole('button', { name: '删除住客 2', exact: true }).click()
  await expect(page.getByRole('heading', { name: '02 · 登记实际住客（1 人）', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  await page.screenshot({ path: '../.local/stay-mobile.png', fullPage: true })
})
