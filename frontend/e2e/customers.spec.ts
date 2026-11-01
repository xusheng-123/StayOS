import { test, expect, type Page } from '@playwright/test'

// 客历档案浏览器验收（V5.0）：真实浏览器走有限客历界面。
// 由 CustomerProfileIntegrationTest.realBrowserAcceptance 启动（同名提示档案与待关联入住已在服务端备好）。
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_CUSTOMER_FIXTURES || '{}') as {
  profileName: string; stayGuestName: string; stayId: number; hintProfileId: number
}
if (!password || !fixtures.profileName || !fixtures.stayId) throw new Error('Run via isolated JUnit customer launcher')

async function login(page: Page, username: string) {
  await page.context().clearCookies()
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/(workspaces\/|customers|forbidden|$)/)
}

test('经理浏览器全流程：导航→列表→新建同名档案→人工提示→关联入住→操作记录', async ({ page }) => {
  await login(page, 'customer_test_hotel_manager')

  // 侧边导航出现 23 客历档案，直达页面。
  await expect(page.getByRole('link', { name: /客历档案/ })).toBeVisible()
  await page.getByRole('link', { name: /客历档案/ }).click()
  await expect(page).toHaveURL(/\/customers$/)
  await expect(page.getByRole('heading', { name: '客历档案', exact: true, level: 1 })).toBeVisible()

  // 既有同名档案在默认启用列表中可见。
  await expect(page.locator('tbody tr', { hasText: fixtures.profileName }).first()).toBeVisible()

  // 新建同名档案：界面只收集字段，同名提示由服务端返回（同名不自动认定同一人）。
  await page.getByRole('button', { name: '新建档案', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建客历档案', exact: true })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('客历姓名', { exact: true }).fill(fixtures.profileName)
  await dialog.getByLabel('证件号码', { exact: true }).fill('110101199901011234')
  await dialog.getByRole('button', { name: '创建档案', exact: true }).click()
  // 详情面板出现，含人工核验引导文案。
  await expect(page.getByRole('button', { name: '关联入住', exact: true })).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('同名不自动认定同一人', { exact: false })).toBeVisible()

  // 关联入住：按主要联系人检索并关联，登记挂到档案。
  await page.getByRole('button', { name: '关联入住', exact: true }).click()
  const linkDialog = page.getByRole('dialog', { name: '关联入住登记', exact: true })
  await expect(linkDialog).toBeVisible()
  await linkDialog.getByLabel('入住检索姓名', { exact: true }).fill(fixtures.stayGuestName)
  await linkDialog.getByRole('button', { name: '检索', exact: true }).click()
  const stayRow = linkDialog.locator('tbody tr', { hasText: fixtures.stayGuestName })
  await expect(stayRow).toBeVisible()
  await stayRow.getByRole('button', { name: '关联此入住', exact: true }).click()
  await expect(page.locator('.detail-panel tbody tr', { hasText: fixtures.stayGuestName }).first()).toBeVisible({ timeout: 10_000 })

  // 操作记录：客历模块可按模块+操作过滤。
  await page.goto('/audit-logs')
  await page.getByRole('combobox', { name: '审计业务模块' }).click()
  await page.getByRole('option', { name: '客历档案', exact: true }).click()
  await page.getByRole('combobox', { name: '审计操作类型' }).click()
  await page.getByRole('option', { name: '创建客历档案', exact: true }).click()
  await expect(page.locator('.audit-record', { hasText: '创建客历档案' }).first()).toBeVisible({ timeout: 10_000 })
})

test('前台可查客历而客房员工被路由拒绝', async ({ page }) => {
  await login(page, 'customer_test_front_desk')
  await expect(page.getByRole('link', { name: /客历档案/ })).toBeVisible()
  await page.goto('/customers')
  await expect(page.getByRole('heading', { name: '客历档案', exact: true, level: 1 })).toBeVisible()
  // 前台没有合并权限：页面不出现合并入口由组件控制；此处至少页面可用。
  await expect(page.getByRole('button', { name: '新建档案', exact: true })).toBeVisible()

  await login(page, 'customer_test_housekeeping_staff')
  await expect(page.getByRole('link', { name: /客历档案/ })).toHaveCount(0)
  await page.goto('/customers')
  await expect(page).toHaveURL(/\/forbidden$/)
})
