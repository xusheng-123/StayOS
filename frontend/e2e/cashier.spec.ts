import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_CASHIER_FIXTURES || '{}') as Record<string, string | number>
if (!password || !fixtures.shiftId || !fixtures.dayDate) throw new Error('Run via isolated JUnit cashier launcher')
async function login(page: Page, role = 'FRONT_DESK') {
  await page.context().clearCookies()
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`cashier_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}
async function confirmBox(page: Page, title: string, button: string) {
  const box = page.getByRole('dialog', { name: title, exact: true }); await expect(box).toBeVisible(); await box.getByRole('button', { name: button, exact: true }).click()
}
async function promptBox(page: Page, title: string, value: string, button: string) {
  // Scope by title: the previous box may still be fading out while the next prompt opens.
  const box = page.locator('.el-message-box').filter({ hasText: title })
  await expect(box).toBeVisible()
  await box.getByRole('textbox').fill(value)
  await box.getByRole('button', { name: button, exact: true }).click()
}

test('收银与营业日浏览器全流程：实盘提交→经理确认→关账固化日报→日报可见', async ({ page }) => {
  const dayDate = String(fixtures.dayDate)
  // 前台：班次列表显示归属现金，实盘差异需填原因，提交后进入待确认。
  await login(page)
  await page.goto('/cashier')
  await expect(page.getByRole('link', { name: /收银与日报/ })).toBeVisible()
  const row = page.locator('.shift-row').filter({ hasText: `#${fixtures.shiftId}` })
  await expect(row).toBeVisible()
  await expect(row).toContainText('实收现金 ¥206.00')
  await expect(row).toContainText('账面应存 ¥306.00')
  await expect(page.locator('.day-row').filter({ hasText: dayDate })).toContainText('营业中')
  await row.getByRole('button', { name: '提交实盘', exact: true }).click()
  await promptBox(page, `提交班次 #${fixtures.shiftId}`, '280', '提交实盘')
  await promptBox(page, '差异原因', '虚构找零误差', '确认提交')
  await expect(page.locator('.el-tag').filter({ hasText: '待确认' })).toBeVisible()
  // 经理：全部班次里确认该班次，然后关账固化日报。
  await login(page, 'HOTEL_MANAGER')
  await page.goto('/cashier')
  await page.getByLabel('查看范围', { exact: true }).selectOption('all')
  await page.getByRole('button', { name: '查询班次', exact: true }).click()
  const submitted = page.locator('.shift-row').filter({ hasText: `#${fixtures.shiftId}` })
  await expect(submitted).toContainText('实盘 ¥280.00')
  await submitted.getByRole('button', { name: '确认班次', exact: true }).click()
  await confirmBox(page, '确认收银班次', '确认班次')
  await expect(page.locator('.el-tag').filter({ hasText: '已确认' })).toBeVisible()
  await page.locator('.day-row').filter({ hasText: dayDate }).getByRole('button', { name: '关账', exact: true }).click()
  await confirmBox(page, `关账 ${dayDate}`, '开始关账')
  await expect(page.locator('.day-row').filter({ hasText: dayDate })).toContainText('已关账')
  // 日报：只有经理可见，快照数字与分母“数据不足”标记直接展示。
  await page.getByRole('button', { name: '营业日报', exact: true }).click()
  await expect(page).toHaveURL(/\/reports\/daily/)
  const report = page.locator('.report-row').filter({ hasText: dayDate })
  await expect(report).toContainText('净应收 ¥288.00')
  await report.locator('.report-head').click()
  await expect(report).toContainText('房费收入 ¥288.00')
  await expect(report).toContainText('现金收 ¥206.00')
  await expect(report).toContainText('出租率 100%')
  // 客房岗位无收银权限，路由守卫拦下。
  await login(page, 'HOUSEKEEPING_STAFF')
  await page.goto('/cashier')
  await expect(page).toHaveURL(/\/forbidden/)
  await page.goto('/reports/daily')
  await expect(page).toHaveURL(/\/forbidden/)
})
