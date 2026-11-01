import { test, expect, type Page } from '@playwright/test'

// 渠道连接浏览器验收（V4.0）：真实浏览器走通道管理界面。
// 由 ChannelIntegrationTest.realBrowserAcceptance 启动（受控模拟供应商，凭据引用已在服务端解析）。
const password = process.env.STAYOS_E2E_PASSWORD!
const fixtures = JSON.parse(process.env.STAYOS_CHANNEL_FIXTURES || '{}') as {
  connectionId: number; roomTypeId: number; credentialRef: string; secretRef: string
}
if (!password || !fixtures.connectionId || !fixtures.credentialRef) throw new Error('Run via isolated JUnit channel launcher')

async function login(page: Page, username = 'channel_test_manager') {
  await page.context().clearCookies()
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/(workspaces\/|channels|forbidden|$)/)
}

test('经理浏览器全流程：导航→列表→详情→新建连接→新增映射→立即对账→操作记录', async ({ page }) => {
  await login(page)

  // 侧边导航出现 22 渠道连接，直达页面。
  await expect(page.getByRole('link', { name: /渠道连接/ })).toBeVisible()
  await page.getByRole('link', { name: /渠道连接/ }).click()
  await expect(page).toHaveURL(/\/channels$/)
  await expect(page.getByRole('heading', { name: '渠道连接', exact: true, level: 1 })).toBeVisible()

  // 连接列表出现主连接；打开详情。
  const mainRow = page.locator('tbody tr', { hasText: '测试渠道主连接' })
  await expect(mainRow).toBeVisible()
  await mainRow.getByRole('button', { name: '详情', exact: true }).click()
  await expect(page.getByText('凭据引用：', { exact: false })).toBeVisible()
  await expect(page.getByRole('tab', { name: /渠道映射/ })).toBeVisible()

  // 新建连接：仅保存凭据引用（值不入库）。
  await page.getByRole('button', { name: '新建连接', exact: true }).click()
  const createDialog = page.getByRole('dialog', { name: '新建渠道连接', exact: true })
  await expect(createDialog).toBeVisible()
  await createDialog.getByLabel('连接名称', { exact: true }).fill('测试渠道浏览器连接')
  await createDialog.getByLabel('凭据引用', { exact: true }).fill(fixtures.credentialRef)
  await createDialog.getByLabel('回调密钥引用', { exact: true }).fill(fixtures.secretRef)
  await createDialog.getByRole('button', { name: '创建连接', exact: true }).click()
  await expect(page.locator('tbody tr', { hasText: '测试渠道浏览器连接' })).toBeVisible()

  // 浏览器连接新增房型映射（每个连接对同一本地对象只允许一条映射：主连接已映射 CH_T）。
  await page.locator('tbody tr', { hasText: '测试渠道浏览器连接' }).getByRole('button', { name: '详情', exact: true }).click()
  await page.getByRole('button', { name: '新增映射', exact: true }).click()
  const mappingDialog = page.getByRole('dialog', { name: '新增渠道映射', exact: true })
  await expect(mappingDialog).toBeVisible()
  await mappingDialog.getByRole('combobox', { name: '本地房型', exact: true }).click()
  await page.getByRole('option', { name: /虚构渠道房型/ }).first().click()
  await mappingDialog.getByLabel('渠道编码', { exact: true }).fill('CH_BROWSER')
  await mappingDialog.getByRole('button', { name: '保存映射', exact: true }).click()
  await expect(page.locator('tbody tr', { hasText: 'CH_BROWSER' })).toBeVisible()

  // 立即对账 → 对账记录页签出现对账批次。
  await page.getByRole('button', { name: '立即对账', exact: true }).click()
  await page.getByRole('tab', { name: /对账记录/ }).click()
  await expect(page.locator('tbody tr').filter({ hasText: /完成|失败/ }).first()).toBeVisible()

  // 操作记录：渠道模块的动作可按模块过滤。
  await page.goto('/audit-logs')
  const moduleSelect = page.getByRole('combobox', { name: '审计业务模块' })
  await moduleSelect.click()
  const moduleOption = page.getByRole('option', { name: '渠道连接', exact: true })
  await expect(moduleOption).toBeVisible()
  await moduleOption.click()
  // 选中后筛选列表里出现渠道连接动作（列表为按钮行，不是表格）。
  await expect(page.locator('.audit-record', { hasText: '创建渠道连接' }).first()).toBeVisible({ timeout: 10_000 })
})

test('非授权角色不可见渠道功能且直达被拒', async ({ page }) => {
  await login(page, 'channel_test_front')
  await expect(page.getByRole('link', { name: /渠道连接/ })).toHaveCount(0)
  await page.goto('/channels')
  await expect(page).toHaveURL(/\/forbidden$/)
})
