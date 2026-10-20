import { test, expect, type Page } from '@playwright/test'
const password=process.env.STAYOS_E2E_PASSWORD
if(!password) throw new Error('Runtime test credentials required')
const rooms=JSON.parse(process.env.STAYOS_ISSUE_FIXTURES||'{}') as Record<string,{id:number;number:string}>
const [own,other]=(process.env.STAYOS_ISSUE_SCOPE||'').split(',').map(Number)
async function login(page:Page,role='HOUSEKEEPING_MANAGER') {
 await page.goto('/login');await page.getByLabel('账号',{exact:true}).fill(`issue_test_${role.toLowerCase()}`);await page.getByLabel('密码',{exact:true}).fill(password!);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page).not.toHaveURL(/\/login/)
}
async function post(page:Page,path:string,body:unknown,key=crypto.randomUUID()) {
 const csrf=(await (await page.request.get('/api/auth/csrf')).json()).data
 return page.request.post(path,{data:body,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':key}})
}
async function selectRoom(page:Page,label:string,number:string) {
 const field=page.getByRole('combobox',{name:label,exact:true});await field.fill(number)
 await page.getByRole('option',{name:number+' · 虚构清洁测试房型',exact:true}).click()
}
async function report(page:Page,name:string,severity='LOW') {
 await page.goto('/room-issues');await page.getByRole('button',{name:'上报异常',exact:true}).click()
 const dialog=page.getByRole('dialog',{name:'上报房间异常',exact:true});await selectRoom(page,'异常所属房间',rooms[name]!.number)
 await dialog.getByLabel('上报异常类型',{exact:true}).selectOption('PLUMBING');await dialog.getByLabel('上报严重程度',{exact:true}).selectOption(severity)
 await dialog.getByLabel('异常描述',{exact:true}).fill('虚构测试：水管漏水，请安排处理')
 const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/api/room-issues'))
 await dialog.getByRole('button',{name:'确认提交',exact:true}).click();const r=await response;expect(r.status()).toBe(200);const id=(await r.json()).data.issueId
 await expect(dialog).not.toBeVisible();await expect(page.getByRole('button',{name:`查看异常 ${id}`,exact:true})).toBeVisible();return id as number
}
async function submitAction(page:Page,button:string,title:string,reason='虚构测试：已修复并复核') {
 await page.getByRole('button',{name:button,exact:true}).click();const dialog=page.getByRole('dialog',{name:title,exact:true})
 await dialog.getByLabel(title==='开始处理异常'?'处理备注（选填）':'操作原因或处理结论',{exact:true}).fill(reason)
 await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await expect(dialog).not.toBeVisible()
}
for(const role of ['ADMIN','FRONT_DESK','HOUSEKEEPING_STAFF','HOUSEKEEPING_MANAGER','HOTEL_MANAGER']) {
 test(`${role}：异常菜单、路由及主管操作权限`,async({page})=>{
  await login(page,role);await expect(page.getByRole('link',{name:/房间异常/})).toHaveCount(role==='ADMIN'?0:1)
  await page.goto('/room-issues')
  if(role==='ADMIN') { await expect(page.getByText('无权访问此工作区',{exact:true})).toBeVisible();expect((await page.request.get('/api/room-issues')).status()).toBe(403) }
  else { await expect(page.getByRole('heading',{name:'房间异常',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'人工维修停用',exact:true})).toHaveCount(['HOUSEKEEPING_MANAGER','HOTEL_MANAGER'].includes(role)?1:0) }
 })
}
test('前台正常上报、真实筛选和服务器身份',async({page})=>{
 await login(page,'FRONT_DESK');const id=await report(page,'report','CRITICAL')
 await page.getByLabel('异常房号搜索',{exact:true}).fill(rooms.report!.number);await page.getByLabel('异常楼层',{exact:true}).selectOption('5');await page.getByLabel('异常类型',{exact:true}).selectOption('PLUMBING');await page.getByLabel('严重程度',{exact:true}).selectOption('CRITICAL');await page.getByLabel('处理状态',{exact:true}).selectOption('OPEN');await page.getByRole('button',{name:'筛选异常',exact:true}).click()
 await expect(page.locator('.issue-card')).toHaveCount(1);await page.getByRole('button',{name:`查看异常 ${id}`,exact:true}).click();const drawer=page.getByRole('dialog',{name:'异常详情与处理',exact:true})
 await expect(drawer).toContainText('上报异常');await expect(drawer.getByRole('button',{name:'确认解决',exact:true})).toHaveCount(0)
 const detail=(await (await page.request.get(`/api/room-issues/${id}`)).json()).data;expect(detail.reporterName).toBe('清洁测试FRONT_DESK');expect(detail.reportedAt).toBeTruthy()
})
test('主管完整处理、独立停用、拒绝提前恢复并保留历史',async({page})=>{
 await login(page);const id=await report(page,'workflow','CRITICAL');await page.getByRole('button',{name:`查看异常 ${id}`,exact:true}).click()
 await submitAction(page,'维修停用','人工维修停用');await submitAction(page,'开始处理','开始处理异常')
 await page.getByRole('button',{name:'解除维修停用',exact:true}).click();let dialog=page.getByRole('dialog',{name:'解除维修停用',exact:true});await dialog.getByLabel('操作原因或处理结论',{exact:true}).fill('尚未修复尝试恢复');await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await expect(dialog).toContainText('仍有未解决的严重异常');await dialog.getByRole('button',{name:'取消',exact:true}).click()
 await submitAction(page,'确认解决','确认解决异常');const before=(await (await page.request.get(`/api/rooms/${rooms.workflow!.id}`)).json()).data;expect(before.maintenanceStatus).toBe('OUT_OF_SERVICE')
 await submitAction(page,'解除维修停用','解除维修停用');const room=(await (await page.request.get(`/api/rooms/${rooms.workflow!.id}`)).json()).data;expect(room.maintenanceStatus).toBe('NORMAL');expect(room.cleaningStatus).toBe('DIRTY');expect(room.canCheckIn).toBe(false)
 const history=(await (await page.request.get(`/api/room-issues/${id}/history`)).json()).data;expect(history.map((l:{action:string})=>l.action)).toEqual(['REPORT','START','RESOLVE'])
 await expect(page.getByRole('dialog',{name:'异常详情与处理',exact:true})).toContainText('已解除')
})
test('没有异常记录的房间也可以人工停用及受控恢复',async({page})=>{
 await login(page);await page.goto('/room-issues');await page.getByRole('button',{name:'人工维修停用',exact:true}).click();let dialog=page.getByRole('dialog',{name:'人工维修停用',exact:true});await selectRoom(page,'异常所属房间',rooms.standalone!.number);await dialog.getByLabel('操作原因或处理结论',{exact:true}).fill('虚构：预防性检修');await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await expect(dialog).not.toBeVisible()
 await page.getByRole('button',{name:'维修记录与恢复',exact:true}).click();await selectRoom(page,'查询维修记录房间',rooms.standalone!.number);await expect(page.getByRole('button',{name:'解除所选房间维修停用',exact:true})).toBeVisible();await submitAction(page,'解除所选房间维修停用','解除维修停用')
 await expect(page.getByRole('dialog',{name:'房间维修记录',exact:true})).toContainText('已解除')
 const issues=(await (await page.request.get(`/api/room-issues?roomId=${rooms.standalone!.id}`)).json()).data;expect(issues.total).toBe(0)
})
test('服务员只能查询自己的异常，不能查看他人详情及历史',async({page})=>{
 await login(page,'HOUSEKEEPING_STAFF');await page.goto('/room-issues');await expect(page.getByRole('button',{name:`查看异常 ${own}`,exact:true})).toBeVisible();await expect(page.getByRole('button',{name:`查看异常 ${other}`,exact:true})).toHaveCount(0)
 expect((await page.request.get(`/api/room-issues/${other}`)).status()).toBe(404);expect((await page.request.get(`/api/room-issues/${other}/history`)).status()).toBe(404);expect((await post(page,`/api/room-issues/${own}/resolve`,{version:0,reason:'越权关闭'})).status()).toBe(403)
})
test('房态卡片及复用房间详情显示独立异常和阻止原因',async({page})=>{
 await login(page);const r=await post(page,'/api/room-issues',{roomId:rooms.roomDetail!.id,issueType:'SAFETY',severity:'CRITICAL',description:'虚构测试：房门安全问题'});expect(r.status()).toBe(200)
 await page.goto('/rooms');await page.getByLabel('房号搜索',{exact:true}).fill(rooms.roomDetail!.number);await page.getByLabel('房号搜索',{exact:true}).press('Enter');const card=page.getByRole('button',{name:`查看房间 ${rooms.roomDetail!.number}`,exact:true});await expect(card).toContainText('严重异常');await card.click();const drawer=page.getByRole('dialog',{name:'房间详情',exact:true});await expect(drawer).toContainText('存在未解决严重异常');await expect(drawer).toContainText('房门安全问题');await expect(drawer).toContainText('待打扫');await expect(drawer.getByRole('link',{name:'查看本房间异常记录'})).toBeVisible()
})
test('丢失上报响应后原标识重试只生成一条异常',async({page})=>{
 await login(page);await page.goto('/room-issues');await page.getByRole('button',{name:'上报异常',exact:true}).click();await selectRoom(page,'异常所属房间',rooms.network!.number);const dialog=page.getByRole('dialog',{name:'上报房间异常',exact:true});await dialog.getByLabel('异常描述',{exact:true}).fill('虚构网络重试测试')
 const keys:string[]=[];page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/api/room-issues')) keys.push(r.headers()['idempotency-key']!)})
 await page.route('**/api/room-issues',async route=>{if(route.request().method()==='POST'){expect((await route.fetch()).status()).toBe(200);await route.abort('failed')}else await route.continue()},{times:1})
 await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await expect(dialog.getByRole('button',{name:'重试原请求',exact:true})).toBeEnabled();await dialog.getByRole('button',{name:'重试原请求',exact:true}).click();await expect(dialog).not.toBeVisible();expect(keys).toHaveLength(2);expect(new Set(keys).size).toBe(1)
 const items=(await (await page.request.get(`/api/room-issues?roomId=${rooms.network!.id}`)).json()).data;expect(items.total).toBe(1)
})
test('刷新后查询原操作结果且不在浏览器保存描述正文',async({page})=>{
 await login(page);await page.goto('/room-issues');await page.getByRole('button',{name:'上报异常',exact:true}).click();await selectRoom(page,'异常所属房间',rooms.refresh!.number);const dialog=page.getByRole('dialog',{name:'上报房间异常',exact:true});await dialog.getByLabel('异常描述',{exact:true}).fill('虚构正文不保存在浏览器')
 await page.route('**/api/room-issues',async route=>{if(route.request().method()==='POST'){expect((await route.fetch()).status()).toBe(200);await route.abort('failed')}else await route.continue()},{times:1})
 await dialog.getByRole('button',{name:'确认提交',exact:true}).click();await expect(dialog.getByRole('button',{name:'重试原请求',exact:true})).toBeEnabled()
 const storage=await page.evaluate(()=>JSON.stringify({...sessionStorage}));expect(storage).not.toContain('虚构正文不保存在浏览器');await page.reload();await page.getByRole('button',{name:'查询原操作结果',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'已确认原操作成功'})).toBeVisible()
})
test('手机异常上报及主管处理无整页横向溢出',async({page})=>{
 await page.setViewportSize({width:390,height:844});await login(page);const id=await report(page,'mobile','MEDIUM');await page.getByRole('button',{name:`查看异常 ${id}`,exact:true}).click();await submitAction(page,'确认解决','确认解决异常');await expect(page.getByRole('dialog',{name:'异常详情与处理',exact:true})).toContainText('已解决')
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await page.screenshot({path:'../.local/phase6-mobile.png',fullPage:false,animations:'disabled'})
})
test('桌面筛选和列表布局正常且数据来自真实接口',async({page})=>{
 await page.setViewportSize({width:1440,height:1000});await login(page,'HOTEL_MANAGER');await page.goto('/room-issues');await expect(page.locator('.issue-card').first()).toBeVisible();const stats=(await (await page.request.get('/api/room-issues/statistics')).json()).data;await expect(page.getByLabel('异常统计')).toContainText(String(stats.maintenanceRooms));expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await page.screenshot({path:'../.local/phase6-desktop.png',fullPage:true,animations:'disabled'})
})
