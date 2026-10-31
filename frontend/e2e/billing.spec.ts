import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_BILLING_FIXTURES || '{}') as Record<string, number>
if (!password || !fixtures.folioId || !fixtures.closedFolioId) throw new Error('Run via isolated JUnit billing launcher')
async function login(page: Page, role = 'FRONT_DESK') {
  await page.context().clearCookies()
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`billing_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}
const figure = (page: Page, label: string) => page.locator('.folio-figures > div').filter({ hasText: label }).locator('dd')
async function confirmBox(page: Page, title: string, button: string) {
  const box = page.getByRole('dialog', { name: title, exact: true }); await expect(box).toBeVisible(); await box.getByRole('button', { name: button, exact: true }).click()
}
async function promptBox(page: Page, title: string, reason: string, button: string) {
  const box = page.locator('.el-message-box')
  await expect(page.getByText(title).first()).toBeVisible()
  await box.getByRole('textbox').fill(reason)
  await box.getByRole('button', { name: button, exact: true }).click()
}

test('前台浏览器全流程：列表→客账→消费履约→入账收款→结账→经理重开', async ({ page }) => {
  await login(page)
  await page.goto('/billing')
  await expect(page.getByRole('link', { name: /账务/ })).toBeVisible()
  const row = page.getByRole('button', { name: `查看客账 ${fixtures.folioId}`, exact: true })
  await expect(row).toBeVisible(); await row.click()
  await expect(page).toHaveURL(new RegExp(`/billing/folios/${fixtures.folioId}$`))
  // 快照基线：昨夜房费 288，预收现金 100 → 净应收 288、未付 188，两项分开展示。
  await expect(figure(page, '净应收')).toHaveText('¥288.00')
  await expect(figure(page, '净实收')).toHaveText('¥100.00')
  await expect(figure(page, '未付余额')).toHaveText('¥188.00')
  // 服务消费登记 → 履约后才入账 3 元。
  await page.getByRole('button', { name: '登记消费', exact: true }).click()
  const orderDialog = page.getByRole('dialog', { name: '登记服务消费', exact: true })
  await orderDialog.getByRole('combobox', { name: '服务项目', exact: true }).click()
  await page.getByRole('option', { name: /矿泉水/ }).first().click()
  await orderDialog.getByRole('button', { name: '登记消费', exact: true }).click()
  const orderRow = page.locator('.order-row').filter({ hasText: '矿泉水×1' })
  await expect(orderRow.locator('.el-tag')).toHaveText('待履约')
  await orderRow.getByRole('button', { name: '履约入账', exact: true }).click()
  await confirmBox(page, '履约确认', '确认履约')
  await expect(page.locator('.el-tag').filter({ hasText: '已履约' })).toBeVisible()
  await expect(figure(page, '净应收')).toHaveText('¥291.00')
  // 手工费用入账 15 元。
  await page.getByRole('button', { name: '费用入账', exact: true }).click()
  const chargeDialog = page.getByRole('dialog', { name: '费用入账', exact: true })
  await chargeDialog.getByText('手工费用', { exact: true }).click()
  await chargeDialog.getByLabel('费用名称', { exact: true }).fill('虚构迷你吧消费')
  await chargeDialog.getByLabel('单价（元）', { exact: true }).fill('15')
  await chargeDialog.getByRole('button', { name: '确认入账', exact: true }).click()
  await expect(figure(page, '净应收')).toHaveText('¥306.00')
  // 收款 206 元补齐（预收 100 已抵部分）→ 未付余额归零，结账无需确认余额。
  await page.getByRole('button', { name: '登记收款', exact: true }).click()
  const payDialog = page.getByRole('dialog', { name: '登记收款', exact: true })
  await payDialog.getByLabel('金额（元）', { exact: true }).fill('206')
  await payDialog.getByRole('button', { name: '确认收款', exact: true }).click()
  await expect(figure(page, '未付余额')).toHaveText('¥0.00')
  await page.getByRole('button', { name: '结账', exact: true }).click()
  await expect(page.locator('.el-tag').filter({ hasText: '已结账' })).toBeVisible()
  await expect(page.getByRole('button', { name: '结账', exact: true })).toHaveCount(0)
  // 前台无权重开；经理重开需填原因。
  await expect(page.getByRole('button', { name: '重开客账', exact: true })).toHaveCount(0)
  await login(page, 'HOTEL_MANAGER')
  await page.goto(`/billing/folios/${fixtures.folioId}`)
  await expect(page.locator('.el-tag').filter({ hasText: '已结账' })).toBeVisible()
  await page.getByRole('button', { name: '重开客账', exact: true }).click()
  await promptBox(page, '重开客账', '虚构结账后补充消费', '确认重开')
  await expect(page.locator('.el-tag').filter({ hasText: '未结账' })).toBeVisible()
  await expect(page.locator('.folio-event').filter({ hasText: '重开客账' })).toBeVisible()
})
test('价目维护页展示政策版本、房价方案与服务价目', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER')
  await page.goto('/billing/config')
  await expect(page.getByRole('heading', { name: '价目维护' })).toBeVisible()
  await expect(page.locator('.policy-row').filter({ hasText: '生效中' })).toBeVisible()
  await expect(page.locator('.el-table').first()).toContainText('门市价')
  await expect(page.getByText('¥288.00/晚')).toBeVisible()
  await expect(page.getByText('矿泉水')).toBeVisible()
  await expect(page.getByRole('button', { name: '发布新政策版本', exact: true })).toBeVisible()
})
test('已结账客账对经理可见重开入口，前台只能查看', async ({ page }) => {
  await login(page, 'HOTEL_MANAGER')
  await page.goto(`/billing/folios/${fixtures.closedFolioId}`)
  await expect(page.locator('.el-tag').filter({ hasText: '已结账' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重开客账', exact: true })).toBeVisible()
  await login(page)
  await page.goto(`/billing/folios/${fixtures.closedFolioId}`)
  await expect(page.locator('.el-tag').filter({ hasText: '已结账' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重开客账', exact: true })).toHaveCount(0)
})
test('无账务权限岗位被路由守卫拦下', async ({ page }) => {
  await login(page, 'HOUSEKEEPING_STAFF')
  await page.goto('/billing')
  await expect(page).toHaveURL(/\/forbidden/)
})
