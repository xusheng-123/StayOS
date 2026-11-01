<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElSelect, ElOption, ElDatePicker, ElPagination, ElDrawer, ElEmpty } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import type { PageResult } from '../types/room'
type Entry = { id:number; operatorId:number|null; operatorName:string|null; operatorUsername:string|null; action:string; targetType:string; targetId:number|null; result:string; createdAt:string }
type Options = { modules:Record<string,string[]>; operators:{id:number;displayName:string;username:string}[];coverage:string }
const moduleNames:Record<string,string> = {SECURITY:'登录与安全',ACCOUNT:'账号管理',CONFIGURATION:'房间基础配置',STAY:'入住、退房、换房与续住',HANDOVER:'交接班',HOUSEKEEPING:'清洁任务',ISSUE:'房间异常',MAINTENANCE:'维修停用与恢复',BILLING:'账务',CASHIER:'收银与营业日',RESERVATION:'预订与未来库存',CHANNEL:'渠道连接'}
const actionNames:Record<string,string> = {LOGIN:'登录',LOGOUT:'退出',ACCESS_DENIED:'拒绝访问',REQUEST_FAILED:'请求失败',ADMIN_BOOTSTRAP:'初始化管理员',USER_CREATE:'创建账号',USER_SET_ENABLED:'启用或禁用账号',USER_SET_ROLES:'调整角色',USER_RESET_PASSWORD:'重置密码',ROOM_CREATE:'新增房间',ROOM_UPDATE:'修改房间',ROOM_SET_ENABLED:'启停房间',ROOM_TYPE_CREATE:'新增房型',ROOM_TYPE_UPDATE:'修改房型',ROOM_TYPE_SET_ENABLED:'启停房型',CHECK_IN:'办理入住',CHECK_OUT:'办理退房',ROOM_CHANGE:'办理换房',GUEST_CORRECT: '住客资料更正', STAY_EXTEND:'续住与预计离店调整',HANDOVER_CREATE:'创建交班草稿',HANDOVER_UPDATE:'修改交班草稿',HANDOVER_SUBMIT:'提交交班',HANDOVER_REASSIGN:'改派接班人',HANDOVER_ACKNOWLEDGE:'确认接班',HK_MANUAL_CREATE:'人工清洁建单',HK_ASSIGN:'分配清洁任务',HK_REASSIGN:'重新分配',HK_START:'开始清洁',HK_COMPLETE:'完成清洁',ISSUE_REPORT:'上报异常',ISSUE_START:'开始处理异常',ISSUE_RESOLVE:'解决异常',ISSUE_SEVERITY:'调整严重程度',MAINTENANCE_STOP:'维修停用',MAINTENANCE_RESUME:'解除维修停用',FOLIO_OPEN:'开立客账',FOLIO_CLOSE:'客账结账',FOLIO_REOPEN:'重开客账',CHARGE_POST:'费用入账',CHARGE_ADJUST:'费用调整',PAYMENT_RECEIVE:'登记收款',PAYMENT_REFUND:'登记退款',SERVICE_ORDER_CREATE:'登记服务消费',SERVICE_ORDER_UPDATE:'修改服务消费',SERVICE_ORDER_FULFILL:'服务履约入账',SERVICE_ORDER_CANCEL:'取消服务消费',BILLING_POLICY_UPDATE:'更新财务政策',BILLING_RATE_PLAN_UPDATE:'更新房价方案',BILLING_RATE_DAY_UPDATE:'更新特殊日价',BILLING_CATALOG_UPDATE:'更新服务价目',CASHIER_SHIFT_OPEN:'开启收银班次',CASHIER_SHIFT_SUBMIT:'提交收银班次实盘',CASHIER_SHIFT_CONFIRM:'确认收银班次',BUSINESS_DAY_CLOSE:'营业日关账',RESERVATION_CREATE:'创建预订',RESERVATION_MODIFY:'修改预订',RESERVATION_CONFIRM:'确认预订',RESERVATION_CANCEL:'取消预订',RESERVATION_EXPIRE_RELEASE:'到期保留单自动释放',RESERVATION_NO_SHOW:'标记未到店',RESERVATION_ASSIGN_ROOM:'预订分配房间',RESERVATION_CHECK_IN:'预订转入住',RESERVATION_DEPOSIT_RECEIVE:'预订收订金',RESERVATION_DEPOSIT_REFUND:'预订退订金',INVENTORY_BLOCK_CREATE:'新建库存停售',INVENTORY_BLOCK_RELEASE:'解除库存停售',CHANNEL_CONNECTION_CREATE:'创建渠道连接',CHANNEL_CONNECTION_UPDATE:'修改渠道连接',CHANNEL_MAPPING_CREATE:'新增渠道映射',CHANNEL_MAPPING_DELETE:'删除渠道映射',CHANNEL_INBOUND_RETRY:'入站事件重新入队',CHANNEL_OUTBOX_RETRY:'出站事件重新入队',CHANNEL_PROCESS_NOW:'立即处理渠道事件',CHANNEL_RECONCILE:'执行渠道对账',CHANNEL_CALLBACK_SIGNATURE:'回调签名验证失败'}
const results:Record<string,string> = {SUCCESS:'成功',FAILURE:'失败',DENIED:'已拒绝'}
const options=ref<Options>(), list=ref<PageResult<Entry>>(), detail=ref<Entry>(), drawer=ref(false), error=ref(''), detailError=ref(''), loading=ref(false)
const filters=ref({module:'',action:'',operatorId:'' as number|'',result:''}), range=ref<[string,string]>(), page=ref(1)
let generation=0, detailGeneration=0
function message(e:unknown) { return e instanceof Error?e.message:'操作记录加载失败，请稍后重试' }
function actions() { return filters.value.module ? options.value?.modules[filters.value.module] || [] : [...new Set(Object.values(options.value?.modules || {}).flat())].sort() }
async function load(reset=false) {
 if(reset) page.value=1
 const current=++generation; loading.value=true;error.value=''
 const q=new URLSearchParams({page:String(page.value),pageSize:'20'})
 for(const [key,value] of Object.entries(filters.value)) if(value!=='') q.set(key,String(value))
 if(range.value) { q.set('from',new Date(range.value[0]+'T00:00:00+08:00').toISOString());q.set('to',new Date(new Date(range.value[1]+'T00:00:00+08:00').getTime()+86400000).toISOString()) }
 try { const data=await api<PageResult<Entry>>(`/api/audit-logs?${q}`);if(current===generation) list.value=data }
 catch(e) { if(current===generation) { error.value=message(e);list.value=undefined } }
 finally { if(current===generation) loading.value=false }
}
async function show(id:number) {
 const current=++detailGeneration;drawer.value=true;detail.value=undefined;detailError.value=''
 try { const data=await api<Entry>(`/api/audit-logs/${id}`);if(current===detailGeneration) detail.value=data } catch(e) { if(current===detailGeneration) detailError.value=message(e) }
}
onMounted(async()=>{try { options.value=await api<Options>('/api/audit-logs/options');await load() } catch(e) {error.value=message(e)} })
</script>
<template>
 <section class="rooms-page audit-page">
  <div class="page-heading"><div><p class="eyebrow muted">OPERATION HISTORY</p><h1>操作记录</h1><p class="subtle">只读查询当前岗位获准查看的真实操作，时间按北京时间显示。</p></div><el-button :loading="loading" @click="load()">刷新操作记录</el-button></div>
  <el-alert v-if="options" :title="options.coverage" type="info" :closable="false" class="stay-alert" />
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <form class="audit-filters catalog-panel" @submit.prevent="load(true)">
   <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" aria-label="操作日期范围" />
   <el-select v-model="filters.module" aria-label="审计业务模块" placeholder="全部授权模块" clearable @change="filters.action='';load(true)"><el-option v-for="(_,key) in options?.modules" :key="key" :label="moduleNames[key] || key" :value="key" /></el-select>
   <el-select v-model="filters.action" aria-label="审计操作类型" placeholder="全部操作" clearable><el-option v-for="action in actions()" :key="action" :label="actionNames[action] || action" :value="action" /></el-select>
   <el-select v-model="filters.operatorId" aria-label="审计操作人员" placeholder="全部授权操作人员" clearable filterable><el-option v-for="u in options?.operators" :key="u.id" :label="`${u.displayName} · ${u.username}`" :value="u.id" /></el-select>
   <el-select v-model="filters.result" aria-label="审计操作结果" placeholder="全部结果" clearable><el-option v-for="(name,key) in results" :key="key" :label="name" :value="key" /></el-select>
   <el-button native-type="submit" type="primary" :loading="loading">查询操作记录</el-button>
  </form>
  <div v-loading="loading" class="catalog-panel audit-records">
   <div class="table-caption"><h2>真实操作事件</h2><span class="subtle">{{ list?.total ?? 0 }} 条 · 默认最近30天</span></div>
   <el-empty v-if="list && !list.items.length" description="没有符合条件的授权操作记录" />
   <button v-for="item in list?.items" :key="item.id" class="audit-record" :aria-label="`查看操作记录 ${item.id}`" @click="show(item.id)">
    <span><strong>{{ actionNames[item.action] || item.action }}</strong><span class="subtle">{{ localTime(item.createdAt) }}</span></span>
    <span>{{ item.operatorName || '未识别操作人' }}<small>{{ item.operatorUsername || '登录前或无法确定账号的事件' }}</small></span>
    <span class="subtle">{{ item.targetType }}{{ item.targetId ? ` #${item.targetId}` : '' }}</span><el-tag :type="item.result==='SUCCESS'?'success':'warning'">{{ results[item.result] }}</el-tag>
   </button>
   <el-pagination v-if="list" v-model:current-page="page" :total="list.total" :page-size="20" layout="prev, pager, next" @current-change="load()" />
  </div>
  <el-drawer v-model="drawer" title="操作记录详情" size="min(520px, 94vw)">
   <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
   <dl v-if="detail" class="audit-detail"><dt>记录编号</dt><dd>#{{ detail.id }}</dd><dt>操作</dt><dd>{{ actionNames[detail.action] || detail.action }} · {{ detail.action }}</dd><dt>经办人</dt><dd>{{ detail.operatorName || '未识别操作人' }} · {{ detail.operatorUsername || '无账号' }}</dd><dt>发生时间（北京时间）</dt><dd>{{ localTime(detail.createdAt) }}</dd><dt>业务对象</dt><dd>{{ detail.targetType }} #{{ detail.targetId ?? '未记录' }}</dd><dt>操作结果</dt><dd>{{ results[detail.result] }}</dd></dl>
   <p class="subtle">此处不提供住客资料或完整请求内容；具体业务历史仍按原模块权限查询。</p>
  </el-drawer>
 </section>
</template>
<style scoped>
.audit-filters {display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:20px}.audit-filters :deep(.el-date-editor){width:100%;min-width:0}.audit-record {font:inherit;text-align:left;background:transparent;border:0;border-bottom:1px solid #e9eee8;width:100%;padding:16px 0;display:grid;grid-template-columns:2fr 1.5fr 1.5fr auto;gap:12px;cursor:pointer;color:inherit}.audit-record span{min-width:0;overflow-wrap:anywhere}.audit-record strong,.audit-record small,.audit-record .subtle{display:block}.audit-record small{color:#788278;margin-top:5px}.audit-detail{overflow-wrap:anywhere}.audit-detail dt{font-size:12px;color:#788278;margin-top:22px}.audit-detail dd{margin:6px 0}.audit-records .el-pagination{margin-top:20px}@media(max-width:750px){.audit-filters{grid-template-columns:minmax(0,1fr)}.audit-record{grid-template-columns:repeat(2,minmax(0,1fr))}.audit-record :deep(.el-tag){justify-self:start;max-width:100%}.audit-page{min-width:0}}
</style>
