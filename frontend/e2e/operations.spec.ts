import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD!
if (!password) throw new Error('Run through the isolated JUnit browser launcher')
async function login(page: Page, role: string) {
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`ops_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}
for (const role of ['ADMIN', 'HOTEL_MANAGER', 'FRONT_DESK', 'HOUSEKEEPING_MANAGER', 'HOUSEKEEPING_STAFF']) {
  test(`${role}：账号页面和运营待办的岗位边界`, async ({ page }) => {
    await login(page, role)
    expect((await page.request.get('/api/operations/summary')).status()).toBe(role === 'ADMIN' ? 403 : 200)
    expect((await page.request.get('/api/users')).status()).toBe(role === 'ADMIN' ? 200 : 403)
    await page.goto('/manage/accounts')
    if (role === 'ADMIN') await expect(page.getByRole('heading', { name: '账号管理', exact: true })).toBeVisible()
    else await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
    await page.goto('/operations')
    if (role === 'ADMIN') await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
    else await expect(page.getByRole('heading', { name: '运营待办与交班', exact: true })).toBeVisible()
  })
}
test('管理员通过正式页面创建、搜索、调整角色、禁用和重置员工密码', async ({ page }) => {
  await login(page, 'ADMIN'); await page.goto('/manage/accounts')
  const username = `ops_test_created_${Date.now()}`
  await page.getByRole('button', { name: '新增员工', exact: true }).click()
  await page.getByLabel('员工账号', { exact: true }).fill(username); await page.getByLabel('员工姓名', { exact: true }).fill('虚构运营员工')
  await page.getByLabel('新密码', { exact: true }).fill(password); await page.getByLabel('再次输入密码', { exact: true }).fill(password)
  await page.getByRole('group', { name: '员工角色' }).getByText('客房服务员', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '客房服务员', exact: true })).toBeChecked()
  await page.getByRole('button', { name: '保存账号设置', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByLabel('搜索员工账号或姓名').fill(username); await page.getByRole('button', { name: '搜索员工', exact: true }).click()
  const card = page.getByRole('article', { name: `员工账号 ${username}` }); await expect(card).toBeVisible()
  await card.getByRole('button', { name: '调整角色' }).click(); await page.getByRole('group', { name: '员工角色' }).getByText('前台员工', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '前台员工', exact: true })).toBeChecked()
  await page.getByRole('button', { name: '保存账号设置', exact: true }).click(); await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(card.getByText('前台员工', { exact: true })).toBeVisible()
  await card.getByRole('button', { name: '禁用账号' }).click(); await page.getByRole('button', { name: '确认', exact: true }).click(); await expect(card.getByText('禁用', { exact: true })).toBeVisible()
  await card.getByRole('button', { name: '重置密码' }).click(); await page.getByLabel('新密码', { exact: true }).fill(password + 'A')
  await page.getByLabel('再次输入密码', { exact: true }).fill(password + 'A'); await page.getByRole('button', { name: '保存账号设置', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain(password)
})
test('房态活跃时自动更新，无操作后停止轮询，回到页面可重新获取', async ({ page }) => {
  // Install before navigation so the room board creates its timers on this clock.
  await page.clock.install()
  await login(page, 'FRONT_DESK'); await page.goto('/rooms'); await expect(page.getByRole('button', { name: '刷新房态', exact: true })).toBeVisible()
  // The refresh button is visible before the initial asynchronous load has finished.
  await expect(page.getByTestId('stat-total')).toHaveText(/^\d+$/)
  let reads = 0; page.on('request', r => { if (r.url().includes('/api/room-status?')) reads++ })
  await page.mouse.click(350, 200); await page.clock.fastForward(31000)
  await expect.poll(() => reads).toBeGreaterThan(0)
  await page.clock.fastForward(65000); const paused = reads; await page.clock.fastForward(65000)
  expect(reads).toBe(paused)
  await page.getByRole('button', { name: '刷新房态', exact: true }).click(); await expect.poll(() => reads).toBeGreaterThan(paused)
})
for (const [role, path, heading] of [['ADMIN', '/manage/accounts', '账号管理'], ['HOTEL_MANAGER', '/operations', '运营待办与交班']]) {
  test(`手机页面：${heading}`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 }); await login(page, role!); await page.goto(path!)
    await expect(page.getByRole('heading', { name: heading!, exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    await page.screenshot({ path: `../.local/operations-${role!.toLowerCase()}-mobile.png`, fullPage: true })
  })
}
