import { test, expect, type Page } from '@playwright/test'
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_RESERVATION_FIXTURES || '{}') as Record<string, string | number>
if (!password || !fixtures.reservationId || !fixtures.typeId || !fixtures.roomId) throw new Error('Run via isolated JUnit reservation launcher')

async function login(page: Page, role = 'FRONT_DESK') {
  await page.context().clearCookies()
  await page.goto('/login'); await page.getByLabel('账号', { exact: true }).fill(`reservation_test_${role.toLowerCase()}`)
  await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/workspaces\//)
}

const typeRow = (page: Page) => page.locator('.availability-grid tbody tr').filter({ hasText: String(fixtures.typeCode ?? '') })

test('预订浏览器全流程：确认→分房→订金→停售演练→转入住', async ({ page }) => {
  const arrival = String(fixtures.arrival), departure = String(fixtures.departure)
  // 前台：列表里能看到这张保留单，详情页展示区间与联系人掩码。
  await login(page)
  await page.goto('/reservations')
  await expect(page.getByRole('link', { name: /预订管理/ })).toBeVisible()
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: String(fixtures.reservationId), exact: true }) })
  await expect(row).toContainText('保留单')
  await expect(row).toContainText(String(fixtures.typeName))
  await row.getByRole('button', { name: '查看详情' }).click()
  await expect(page).toHaveURL(new RegExp(`/reservations/${fixtures.reservationId}`))
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const detailTag = page.locator('.el-descriptions__body').getByText('保留单', { exact: true })
  await expect(detailTag).toBeVisible()
  await expect(page.locator('.el-descriptions__body')).toContainText('13****34')
  // 确认预订 → 状态变更为已确认。
  await page.getByRole('button', { name: '确认预订', exact: true }).click()
  await expect(page.locator('.el-descriptions__body').getByText('已确认', { exact: true })).toBeVisible()
  // 分配建议房间。
  await page.getByRole('button', { name: '分配房间' }).click()
  const assignBox = page.getByRole('dialog', { name: '分配建议房间', exact: true })
  await expect(assignBox).toBeVisible()
  await assignBox.getByLabel('建议房间', { exact: true }).click()
  await page.getByRole('option', { name: new RegExp(String(fixtures.roomNumber)) }).first().click()
  await assignBox.getByRole('button', { name: '保存分配', exact: true }).click()
  await expect(assignBox).toBeHidden()
  await expect(page.locator('.el-descriptions__body')).toContainText(String(fixtures.roomNumber))
  // 收订金：真实资金记录，列表出现收款行。
  await page.getByRole('button', { name: '收订金' }).click()
  const depositBox = page.getByRole('dialog', { name: '收订金', exact: true })
  await depositBox.getByLabel('金额（元）', { exact: true }).fill('200')
  await depositBox.getByRole('button', { name: '登记收款', exact: true }).click()
  await expect(depositBox).toBeHidden()
  await expect(page.locator('.el-table').first()).toContainText('200.00')
  // 可售页：整段可订为 1（两间房、已确认一单）。
  await page.goto('/availability')
  await expect(page.getByRole('link', { name: /可售与库存/ })).toBeVisible()
  await expect(typeRow(page)).toContainText('整段可订 1')
  // 经理：停售演练——区间内归零，解除后恢复。
  await login(page, 'HOTEL_MANAGER')
  await page.goto('/availability')
  await page.getByRole('button', { name: '新建停售' }).click()
  const blockBox = page.getByRole('dialog', { name: '新建停售', exact: true })
  await blockBox.getByLabel('停售房型').click()
  await page.getByRole('option', { name: new RegExp(String(fixtures.typeCode)) }).first().click()
  await blockBox.getByLabel('停售开始日').fill(arrival); await page.keyboard.press('Enter')
  await blockBox.getByLabel('停售结束日（不含）').fill(departure); await page.keyboard.press('Enter')
  await blockBox.getByLabel('原因').fill('计划装修停售（浏览器演练）')
  await blockBox.getByRole('button', { name: '创建停售', exact: true }).click()
  await expect(blockBox).toBeHidden()
  await expect(typeRow(page)).toContainText('停售')
  await expect(typeRow(page)).toContainText('整段可订 0')
  const blockRow = page.locator('.catalog-panel').filter({ hasText: '停售计划' }).locator('tr').filter({ hasText: '计划装修停售（浏览器演练）' })
  await blockRow.getByRole('button', { name: '解除停售' }).click()
  // 默认只列生效中的停售：解除后行从列表消失，网格可售量同步恢复。
  await expect(blockRow).toHaveCount(0)
  await expect(typeRow(page)).toContainText('整段可订 1')
  // 前台：预订转入住——填实际房间、预计退房与主要住客，成功后进入入住详情。
  await login(page)
  await page.goto(`/reservations/${fixtures.reservationId}`)
  await page.getByRole('button', { name: '办理入住' }).click()
  const checkInBox = page.getByRole('dialog', { name: '预订转入住', exact: true })
  await expect(checkInBox).toBeVisible()
  await checkInBox.getByLabel('主要住客', { exact: true }).fill('预订测试住客甲')
  await checkInBox.getByLabel('证件号码', { exact: true }).fill('110101199001012341')
  await checkInBox.getByLabel('预计退房时间（北京时间）', { exact: true }).fill(`${departure} 12:00`); await page.keyboard.press('Enter')
  await checkInBox.getByRole('button', { name: '确认入住', exact: true }).click()
  await expect(page).toHaveURL(/\/stays\/\d+/)
  await expect(page.getByText('预订测试住客甲').first()).toBeVisible()
  // 客房岗位无预订权限，路由守卫拦下。
  await login(page, 'HOUSEKEEPING_STAFF')
  await page.goto('/reservations')
  await expect(page).toHaveURL(/\/forbidden/)
})
