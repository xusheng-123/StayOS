import { test, expect, type Page } from '@playwright/test'
const password=process.env.STAYOS_E2E_PASSWORD
if(!password) throw new Error('Runtime test credentials required')
const rooms=JSON.parse(process.env.STAYOS_SYSTEM_FIXTURES || '{}') as Record<string,{id:number;number:string}>
async function login(page:Page,role='HOTEL_MANAGER') {await page.goto('/login');await page.getByLabel('账号',{exact:true}).fill(`system_test_${role.toLowerCase()}`);await page.getByLabel('密码',{exact:true}).fill(password!);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page).not.toHaveURL(/\/login/)}
async function post(page:Page,path:string,body:unknown) {const csrf=(await(await page.request.get('/api/auth/csrf')).json()).data;return page.request.post(path,{data:body,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':crypto.randomUUID()}})}
async function get(page:Page,path:string){const r=await page.request.get(path);expect(r.status()).toBe(200);return(await r.json()).data}
for(const role of ['ADMIN','HOTEL_MANAGER','HOUSEKEEPING_MANAGER','FRONT_DESK','HOUSEKEEPING_STAFF']) test(`${role}: 集中审计菜单路由及HTTP范围`,async({page})=>{
 await login(page,role);const allowed=['ADMIN','HOTEL_MANAGER','HOUSEKEEPING_MANAGER'].includes(role)
 await expect(page.getByRole('link',{name:/操作记录/})).toHaveCount(allowed?1:0);await page.goto('/audit-logs')
 if(!allowed){await expect(page.getByText('无权访问此工作区',{exact:true})).toBeVisible();expect((await page.request.get('/api/audit-logs')).status()).toBe(403);return}
 await expect(page.getByRole('heading',{name:'操作记录',exact:true})).toBeVisible();const options=await get(page,'/api/audit-logs/options')
 expect('SECURITY' in options.modules).toBe(role==='ADMIN');expect('STAY' in options.modules).toBe(role==='HOTEL_MANAGER');expect((await page.request.get(`/api/audit-logs?module=${role==='ADMIN'?'STAY':'ACCOUNT'}`)).status()).toBe(403)
 await expect(page.getByRole('button',{name:'批量导出'})).toHaveCount(0)
})
test('真实操作分页、筛选、详情与空状态',async({page})=>{
 await login(page);await page.goto('/audit-logs');await expect(page.locator('.audit-record').first()).toBeVisible()
 await page.getByRole('combobox',{name:'审计操作类型',exact:true}).click();await page.getByRole('option',{name:'办理换房',exact:true}).click();await page.getByRole('button',{name:'查询操作记录',exact:true}).click()
 await expect(page.locator('.audit-record').first()).toContainText('办理换房');await page.locator('.audit-record').first().click();await expect(page.getByRole('dialog',{name:'操作记录详情',exact:true})).toContainText('ROOM_CHANGE');await expect(page.getByRole('dialog',{name:'操作记录详情',exact:true})).not.toContainText('documentCiphertext')
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog',{name:'操作记录详情',exact:true})).not.toBeVisible();const query=await page.request.get('/api/audit-logs?from=2000-01-01T00%3A00%3A00Z&to=2000-02-01T00%3A00%3A00Z');expect((await query.json()).data.total).toBe(0)
 await page.route('**/api/audit-logs?*',route=>route.fulfill({json:{success:true,data:{items:[],total:0,page:1,pageSize:20}}}));await page.getByRole('button',{name:'查询操作记录',exact:true}).click();await expect(page.getByText('没有符合条件的授权操作记录',{exact:true})).toBeVisible()
})
test('服务不可用显示中文错误并可刷新恢复',async({page})=>{
 await login(page);await page.route('**/api/audit-logs?*',route=>route.fulfill({status:503,json:{success:false,code:'SERVICE_UNAVAILABLE',message:'服务暂时不可用，请稍后重试'}}));await page.goto('/audit-logs');await expect(page.getByRole('alert').filter({hasText:'服务暂时不可用'})).toBeVisible();await expect(page.locator('.audit-record')).toHaveCount(0)
 await page.unroute('**/api/audit-logs?*');await page.getByRole('button',{name:'刷新操作记录',exact:true}).click();await expect(page.locator('.audit-record').first()).toBeVisible()
})
test('通过人员分房页面一次安排两房并由员工执行',async({page})=>{
 await login(page,'HOUSEKEEPING_MANAGER');await page.goto('/housekeeping/assignment');await page.getByRole('button',{name:'选择员工 换房测试HOUSEKEEPING_STAFF',exact:true}).click()
 await page.getByLabel('分房房号搜索',{exact:true}).fill('S8_ASSIGN');await page.getByRole('button',{name:'筛选房间',exact:true}).click();await page.getByRole('checkbox',{name:`分配房间 ${rooms.ASSIGN_A!.number}`,exact:true}).check();await page.getByRole('checkbox',{name:`分配房间 ${rooms.ASSIGN_B!.number}`,exact:true}).check()
 await expect(page.getByTestId('selected-room-numbers')).toContainText(rooms.ASSIGN_A!.number);await expect(page.getByTestId('selected-room-numbers')).toContainText(rooms.ASSIGN_B!.number);await page.getByRole('button',{name:'确认分配',exact:true}).click();await page.getByRole('dialog',{name:'确认批量分房',exact:true}).getByRole('button',{name:'确认本批分房',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'分房成功'})).toBeVisible()
 await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page).toHaveURL(/\/login/);await login(page,'HOUSEKEEPING_STAFF');await page.goto('/housekeeping/tasks');await expect(page.locator('body')).toContainText(rooms.ASSIGN_A!.number);await expect(page.locator('body')).toContainText(rooms.ASSIGN_B!.number)
 const tasks=await get(page,'/api/housekeeping-tasks?pageSize=100');for(const room of [rooms.ASSIGN_A!,rooms.ASSIGN_B!]){const task=tasks.items.find((t:{roomId:number})=>t.roomId===room.id);expect(task.assignedTo).toBe(Number(process.env.STAYOS_SYSTEM_STAFF_ID));expect((await post(page,`/api/housekeeping-tasks/${task.id}/start`,{version:task.version})).status()).toBe(200);expect((await post(page,`/api/housekeeping-tasks/${task.id}/complete`,{version:task.version+1})).status()).toBe(200);expect((await get(page,`/api/rooms/${room.id}`)).canCheckIn).toBe(true)}
})
async function report(page:Page,name:string){await page.goto('/room-issues');await page.getByRole('button',{name:'上报异常',exact:true}).click();const d=page.getByRole('dialog',{name:'上报房间异常',exact:true});await d.getByRole('combobox',{name:'异常所属房间',exact:true}).fill(rooms[name]!.number);await page.getByRole('option',{name:new RegExp('^'+rooms[name]!.number+' ·')}).click();await d.getByLabel('异常描述',{exact:true}).fill('虚构可靠性故障，不包含身份信息');return d}
test('异常提交先丢响应再冲突时仍保留原标识',async({page})=>{
 await login(page,'HOUSEKEEPING_MANAGER');let count=0;const keys:string[]=[]
 await page.route('**/api/room-issues',async route=>{if(route.request().method()!=='POST')return route.continue();keys.push(route.request().headers()['idempotency-key']!);count++;if(count===1){await route.fetch();return route.fulfill({status:503,json:{success:false,code:'SERVICE_UNAVAILABLE',message:'结果尚未确认'}})}if(count===2)return route.fulfill({status:409,json:{success:false,code:'REQUEST_IN_PROGRESS',message:'请核对上次结果'}});return route.continue()})
 const d=await report(page,'REPORT_RETRY');await d.getByRole('button',{name:'确认提交',exact:true}).click();await expect(d.getByRole('button',{name:'重试原请求',exact:true})).toBeVisible();await d.getByRole('button',{name:'重试原请求',exact:true}).click();await expect(d.getByRole('button',{name:'重试原请求',exact:true})).toBeVisible();await d.getByRole('button',{name:'重试原请求',exact:true}).click();await expect(d).not.toBeVisible();expect(new Set(keys).size).toBe(1);expect((await get(page,`/api/room-issues?roomId=${rooms.REPORT_RETRY!.id}`)).total).toBe(1)
})
test('异常成功回执损坏不误报成功，同标识重试确认',async({page})=>{
 await login(page,'HOUSEKEEPING_MANAGER');let first=true;await page.route('**/api/room-issues',async route=>{if(route.request().method()==='POST'&&first){first=false;await route.fetch();return route.fulfill({json:{success:true,data:{}}})}return route.continue()});const d=await report(page,'REPORT_INVALID');await d.getByRole('button',{name:'确认提交',exact:true}).click();await expect(d).toContainText('回执不完整');await d.getByRole('button',{name:'重试原请求',exact:true}).click();await expect(d).not.toBeVisible();expect((await get(page,`/api/room-issues?roomId=${rooms.REPORT_INVALID!.id}`)).total).toBe(1)
})
test('入住成功回执损坏时不丢失标识或误报成功',async({page})=>{
 await login(page,'FRONT_DESK');const keys:string[]=[];let first=true
 await page.route('**/api/stays',async route=>{if(route.request().method()!=='POST')return route.continue();keys.push(route.request().headers()['idempotency-key']!);if(first){first=false;await route.fetch();return route.fulfill({json:{success:true,data:{}}})}return route.continue()})
 await page.goto('/stays/new');await page.getByLabel('入住选房房号',{exact:true}).fill(rooms.MOBILE_TARGET!.number);await page.getByRole('button',{name:'查询房间',exact:true}).click();await page.getByRole('button',{name:`选择房间 ${rooms.MOBILE_TARGET!.number}`,exact:true}).click()
 await page.getByLabel('住客 1 姓名',{exact:true}).fill('虚构回执住客');await page.getByRole('combobox',{name:'住客 1 证件类型',exact:true}).click();await page.getByRole('listbox',{name:'住客 1 证件类型',exact:true}).getByRole('option',{name:'其他证件',exact:true}).click();await page.getByLabel('住客 1 证件号码',{exact:true}).fill('S8-RECEIPT-FICTIONAL')
 const day=new Date(Date.now()+172800000).toLocaleDateString('en-CA',{timeZone:'Asia/Shanghai'});await page.getByRole('combobox',{name:'预计退房时间',exact:true}).fill(`${day} 12:00`);await page.getByRole('heading',{name:'办理入住',exact:true}).click();await page.getByRole('button',{name:'确认入住',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'入住回执不完整'})).toBeVisible();expect(await page.evaluate(()=>Object.keys(sessionStorage).some(k=>k.startsWith('stayos:check-in-pending:')))).toBe(true)
 await page.getByRole('button',{name:/重试原入住/}).click();await expect(page).toHaveURL(/\/stays\/\d+$/);expect(new Set(keys).size).toBe(1);const rows=await get(page,`/api/stays?roomNumber=${rooms.MOBILE_TARGET!.number}`);expect(rows.total).toBe(1)
})
for(const area of ['rooms','checkin','checkout','change','assignment','staff','issues','audit'])test(`390px手机视口：${area}`,async({page})=>{
 await login(page,area==='staff'?'HOUSEKEEPING_STAFF':'HOTEL_MANAGER');await page.setViewportSize({width:390,height:844})
 const paths:Record<string,string>={rooms:'/rooms',checkin:'/stays/new',assignment:'/housekeeping/assignment',staff:'/housekeeping/tasks',issues:'/room-issues',audit:'/audit-logs'}
 if(area==='checkout'||area==='change'){const room=rooms[area==='checkout'?'MOBILE_CHECKOUT':'MOBILE_CHANGE']!;const r=await post(page,'/api/stays',{roomId:room.id,expectedCheckOutAt:new Date(Date.now()+86400000).toISOString(),guests:[{name:'虚构手机住客',documentType:'OTHER',documentNumber:'S8-MOBILE-FICTIONAL',primary:true}]});expect(r.status()).toBe(200);const stay=(await r.json()).data;await page.goto(`/stays/${stay.id}`);await page.getByRole('button',{name:area==='checkout'?'办理退房':'办理换房',exact:true}).click();await expect(page.getByRole('dialog').first()).toBeVisible()}
 else await page.goto(paths[area]!);await expect(page.locator('h1').first()).toBeVisible();await page.waitForTimeout(150);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 if(area==='audit')await page.screenshot({path:'../.local/phase8/audit-mobile.png',fullPage:true,animations:'disabled'})
})
