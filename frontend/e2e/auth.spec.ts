import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

const password = process.env.STAYOS_E2E_PASSWORD
if (!password) throw new Error('Run browser tests with scripts/test.ps1 -Browser; do not hardcode test passwords')
const ids = JSON.parse(process.env.STAYOS_E2E_IDS || '{}') as Record<string, number>
const fixtures = [
  ['ADMIN', '系统管理员工作区', '/workspaces/admin', ['系统管理']],
  ['HOTEL_MANAGER', '酒店总经理工作区', '/workspaces/hotel-manager', ['总经理工作台', '前台工作台', '客房管理', '客房工作台']],
  ['FRONT_DESK', '前台工作区', '/workspaces/front-desk', ['前台工作台']],
  ['HOUSEKEEPING_MANAGER', '客房主管工作区', '/workspaces/housekeeping-manager', ['客房管理', '客房工作台']],
  ['HOUSEKEEPING_STAFF', '客房服务员工作区', '/workspaces/housekeeping-staff', ['客房工作台']],
] as const
async function login(page: Page, role: string) {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(`test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password!)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}
async function post(context: APIRequestContext, path: string, data?: unknown) {
  const csrf = (await (await context.get('/api/auth/csrf')).json()).data as { headerName: string; token: string }
  return context.post(path, { data, headers: { [csrf.headerName]: csrf.token } })
}

for (const [role, heading, path, links] of fixtures) {
  test(`${role}: 登录、工作区和导航`, async ({ page }) => {
    await login(page, role)
    await expect(page).toHaveURL(path)
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    await expect(page.getByText('访问权限已验证', { exact: true })).toBeVisible()
    const nav = page.getByRole('navigation', { name: '工作区域' })
    await expect(nav.getByRole('link')).toHaveCount(links.length)
    for (const link of links) await expect(nav.getByRole('link', { name: link, exact: false })).toBeVisible()
    if (role !== 'ADMIN') {
      expect((await page.request.get('/api/workspaces/admin')).status()).toBe(403)
      await page.goto('/workspaces/admin')
      await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
    }
  })
}
test('总经理可连续使用前台与客房区域，且无管理员导航', async ({ page, context }) => {
  await login(page, 'HOTEL_MANAGER')
  await expect(page).toHaveURL('/workspaces/hotel-manager')
  const initial = (await context.cookies()).find(cookie => cookie.name === 'STAYOS_SESSION')!
  expect(initial.httpOnly).toBe(true)
  const nav = page.getByRole('navigation', { name: '工作区域' })
  for (const [name, heading] of [['前台工作台', '前台工作区'], ['客房管理', '客房主管工作区'], ['客房工作台', '客房服务员工作区']]) {
    await nav.getByRole('link', { name: new RegExp(name!) }).click()
    await expect(page.getByRole('heading', { name: heading!, exact: true })).toBeVisible()
    await expect(page.getByText('访问权限已验证', { exact: true })).toBeVisible()
  }
  expect((await context.cookies()).find(cookie => cookie.name === 'STAYOS_SESSION')?.value).toBe(initial.value)
  await expect(nav.getByRole('link', { name: /系统管理/ })).toHaveCount(0)
  await page.screenshot({ path: '../.local/manager-workspace.png', fullPage: true })
})
test('错误账号密码提示一致且清空密码', async ({ page }) => {
  await page.goto('/login')
  await page.screenshot({ path: '../.local/login.png', fullPage: true })
  await page.getByLabel('账号', { exact: true }).fill('test_front_desk')
  await page.getByLabel('密码', { exact: true }).fill('incorrect-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '账号或密码错误，或账号暂不可用' })).toBeVisible()
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  await page.getByLabel('账号', { exact: true }).fill('nonexistent_user')
  await page.getByLabel('密码', { exact: true }).fill('incorrect-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '账号或密码错误，或账号暂不可用' })).toBeVisible()
})
test('刷新保留登录，退出撤销登录并阻止后退', async ({ page }) => {
  await login(page, 'FRONT_DESK')
  await expect(page).toHaveURL('/workspaces/front-desk')
  await page.reload()
  await expect(page.getByRole('heading', { name: '前台工作区', exact: true })).toBeVisible()
  expect(await page.evaluate(() => Object.keys(localStorage))).toHaveLength(0)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL('/login')
  expect((await page.request.get('/api/auth/me')).status()).toBe(401)
  await page.goto('/workspaces/front-desk')
  await expect(page).toHaveURL('/login')
})
test('客房服务员不能通过地址或接口进入前台', async ({ page }) => {
  await login(page, 'HOUSEKEEPING_STAFF')
  await expect(page).toHaveURL('/workspaces/housekeeping-staff')
  expect((await page.request.get('/api/workspaces/front-desk')).status()).toBe(403)
  await page.goto('/workspaces/front-desk')
  await expect(page.getByText('无权访问此工作区', { exact: true })).toBeVisible()
})
test('登录在其他窗口撤销后，导航提示重新登录', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER')
  await expect(page).toHaveURL('/workspaces/hotel-manager')
  expect((await post(page.request, '/api/auth/logout')).status()).toBe(200)
  await page.getByRole('navigation', { name: '工作区域' }).getByRole('link', { name: /前台工作台/ }).click()
  await expect(page).toHaveURL(/\/login\?reason=expired/)
  await expect(page.getByText('登录状态已失效，请重新登录', { exact: true })).toBeVisible()
})
test('多角色可以使用两个工作区', async ({ page }) => {
  await login(page, 'MULTI')
  await expect(page).toHaveURL('/workspaces/front-desk')
  await expect(page.getByRole('navigation', { name: '工作区域' }).getByRole('link')).toHaveCount(2)
  await page.getByRole('navigation', { name: '工作区域' }).getByRole('link', { name: /客房工作台/ }).click()
  await expect(page.getByRole('heading', { name: '客房服务员工作区', exact: true })).toBeVisible()
})
test('管理员禁用账号后，已有页面下一次访问被拒绝', async ({ page, playwright }) => {
  const admin = await playwright.request.newContext({ baseURL: 'http://127.0.0.1:5174' })
  try {
    expect((await post(admin, '/api/auth/login', { username: 'test_admin', password })).status()).toBe(200)
    await login(page, 'FRONT_DESK')
    await expect(page).toHaveURL('/workspaces/front-desk')
    expect((await post(admin, `/api/users/${ids.FRONT_DESK}/set-enabled`, { enabled: false })).status()).toBe(200)
    expect((await page.request.get('/api/workspaces/front-desk')).status()).toBe(401)
    await page.reload()
    await expect(page).toHaveURL('/login')
  } finally {
    await post(admin, `/api/users/${ids.FRONT_DESK}/set-enabled`, { enabled: true })
    await admin.dispose()
  }
})
test('移动端登录页可用且不横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.local/login-mobile.png', fullPage: true })
})
