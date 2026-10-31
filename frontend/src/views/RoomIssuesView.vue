<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElDialog, ElDrawer, ElPagination, ElSelect, ElOption, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import { auth, can } from '../stores/auth'
import { localTime, floorName } from '../api/rooms'
import type { Room, Floor, PageResult } from '../types/room'
import { issueTypes, issueStates, severities, issueActions, type RoomIssue, type IssueLog, type MaintenanceStop } from '../types/issue'
const route = useRoute(), manage = computed(() => can('issue:resolve'))
const filters = ref({ roomNumber: typeof route.query.roomNumber === 'string' ? route.query.roomNumber.slice(0,20) : '', floor:'', issueType:'', severity:'', status:'' }), page = ref(1)
const selectedRoomFilter = ref(Number(route.query.roomId) || undefined)
const floors = ref<Floor[]>([]), result = ref<PageResult<RoomIssue>>(), stats = ref<{open:number; inProgress:number; critical:number; maintenanceRooms:number}>()
const loading = ref(false), error = ref(''), success = ref('')
const detail = ref<RoomIssue>(), logs = ref<IssueLog[]>([]), stops = ref<MaintenanceStop[]>([]), drawer = ref(false), detailError = ref(''), detailBusy = ref(false)
const maintenanceDialog=ref(false), maintenanceRoomId=ref<number>(), maintenanceStops=ref<MaintenanceStop[]>([]), maintenanceError=ref(''), maintenanceBusy=ref(false)
const maintenanceActive=computed(()=>maintenanceStops.value.find(s=>!s.resumedAt))
const activeStop = computed(() => stops.value.find(s => !s.resumedAt))
const dialog = ref(false), operation = ref('report'), reason = ref(''), severity = ref('LOW'), type = ref('FACILITY'), description = ref(''), notes = ref('')
const roomId = ref<number>(), rooms = ref<Room[]>([]), roomLoading = ref(false), busy = ref(false), operationError = ref('')
const pending = ref<{key:string; path:string; op:string; body:unknown; uncertain?:boolean} | null>(null)
const markerName = `stayos-room-operation-${auth.user?.id}`
const recovered = ref<{key:string;op:string}|null>(null), markerError = ref('')
const blocked = computed(() => !!pending.value || !!recovered.value || !!markerError.value)
let generation=0, detailGeneration=0, roomGeneration=0
function message(e:unknown) { return e instanceof Error ? e.message : '操作失败，请稍后重试' }
async function load(reset=false) {
 if(reset) page.value=1
 const g=++generation;loading.value=true;error.value=''
 const q=new URLSearchParams({page:String(page.value),pageSize:'20'})
 for(const [k,v] of Object.entries(filters.value)) if(v) q.set(k,v)
 if(selectedRoomFilter.value) q.set('roomId',String(selectedRoomFilter.value))
 try { const [items,count]=await Promise.all([api<PageResult<RoomIssue>>(`/api/room-issues?${q}`),api<typeof stats.value>('/api/room-issues/statistics')]);if(g===generation) { result.value=items;stats.value=count } }
 catch(e) { if(g===generation) { error.value=message(e);result.value=undefined } } finally { if(g===generation) loading.value=false }
}
async function show(id:number) {
 const g=++detailGeneration;drawer.value=true;detailBusy.value=true;detailError.value='';detail.value=undefined;logs.value=[];stops.value=[]
 try { const item=await api<RoomIssue>(`/api/room-issues/${id}`); const [history,maintenance]=await Promise.all([api<IssueLog[]>(`/api/room-issues/${id}/history`),api<MaintenanceStop[]>(`/api/rooms/${item.roomId}/maintenance-history`)]);if(g===detailGeneration) { detail.value=item;logs.value=history;stops.value=maintenance } }
 catch(e) { if(g===detailGeneration) detailError.value=message(e) } finally { if(g===detailGeneration) detailBusy.value=false }
}
async function searchRooms(query='') {
 const g=++roomGeneration;roomLoading.value=true
 try { const data=await api<PageResult<Room>>(`/api/rooms?pageSize=100&roomNumber=${encodeURIComponent(query)}`);if(g===roomGeneration) rooms.value=data.items }
 catch(e) { if(g===roomGeneration) operationError.value=message(e) } finally { if(g===roomGeneration) roomLoading.value=false }
}
function open(action:string) {
 if(blocked.value) return
 operation.value=action;reason.value='';operationError.value='';severity.value=action==='severity'?detail.value?.severity||'LOW':'LOW';type.value='FACILITY';description.value='';notes.value=''
 roomId.value=action==='stop' ? detail.value?.roomId : undefined
 dialog.value=true;if(action==='report' || action==='standalone-stop') void searchRooms()
}
const title = computed(() => ({report:'上报房间异常',start:'开始处理异常',resolve:'确认解决异常',severity:'调整严重程度',stop:'人工维修停用','standalone-stop':'人工维修停用',resume:'解除维修停用','standalone-resume':'解除维修停用'}[operation.value] || '业务操作'))
function close() { if(!busy.value && !pending.value) dialog.value=false }
function clearMarker() { sessionStorage.removeItem(markerName);pending.value=null;recovered.value=null;markerError.value='' }
async function submit() {
 if(busy.value) return
 operationError.value=''
 if(!pending.value) {
  let path='',op='',body:unknown
  if(operation.value==='report') {
   if(!roomId.value || !description.value.trim()) { operationError.value='请选择房间并填写异常描述';return }
   path='/api/room-issues';op='ISSUE_REPORT';body={roomId:roomId.value,issueType:type.value,severity:severity.value,description:description.value,notes:notes.value}
  } else if(operation.value==='stop' || operation.value==='standalone-stop') {
   if(!roomId.value || !reason.value.trim()) { operationError.value='请选择房间并填写停用原因';return }
   path=`/api/rooms/${roomId.value}/maintenance-stop`;op='MAINTENANCE_STOP';body={reason:reason.value,...(operation.value==='stop'&&detail.value?{issueId:detail.value.id}:{})}
  } else if(operation.value==='standalone-resume') {
   if(!maintenanceRoomId.value || !maintenanceActive.value || !reason.value.trim()) { operationError.value='请选择有效停用记录并填写恢复说明';return }
   path=`/api/rooms/${maintenanceRoomId.value}/maintenance-resume`;op='MAINTENANCE_RESUME';body={stopId:maintenanceActive.value.id,reason:reason.value}
  } else {
   if(!detail.value) return
   if(operation.value!=='start' && !reason.value.trim()) { operationError.value='请填写处理结论或操作原因';return }
   if(operation.value==='resume') {
    if(!activeStop.value) { operationError.value='当前没有有效人工停用记录';return }
    path=`/api/rooms/${detail.value.roomId}/maintenance-resume`;op='MAINTENANCE_RESUME';body={stopId:activeStop.value.id,reason:reason.value}
   } else { path=`/api/room-issues/${detail.value.id}/${operation.value}`;op='ISSUE_'+operation.value.toUpperCase();body={version:detail.value.version,reason:reason.value,...(operation.value==='severity'?{severity:severity.value}:{})} }
  }
  try { const key=crypto.randomUUID();sessionStorage.setItem(markerName,JSON.stringify({key,op}));pending.value={key,path,op,body} }
  catch { operationError.value='浏览器无法保存安全重试标识，请检查浏览器存储设置后重试';return }
 }
 busy.value=true
 try {
  const p=pending.value!;const receipt=await api<{id:number;operatedAt:string}>(p.path,{method:'POST',headers:{'Idempotency-Key':p.key},body:JSON.stringify(p.body)})
  if(!receipt || !Number.isSafeInteger(receipt.id) || receipt.id<1 || !Number.isFinite(Date.parse(receipt.operatedAt))) throw new ApiError(0,'INVALID_RESPONSE','服务器回执不完整，请保留原请求标识确认结果')
  clearMarker();dialog.value=false;success.value='操作成功，房态已按真实业务记录重新计算';await load();if(drawer.value&&detail.value) await show(detail.value.id);if(maintenanceDialog.value) await loadMaintenance()
 } catch(e) {
  const uncertain=!!pending.value?.uncertain || !(e instanceof ApiError) || e.status===0 || e.status===401 || e.status>=500 || e.code==='INVALID_RESPONSE'
  operationError.value=message(e)+(uncertain?'。结果尚未确认，请重试原请求或查询原操作结果。':'')
  if(uncertain && pending.value) pending.value.uncertain=true
  if(!uncertain) clearMarker()
 } finally { busy.value=false }
}
async function recoverResult() {
 const marker=pending.value||recovered.value;if(!marker) return
 busy.value=true;markerError.value=''
 try {
  await api(`/api/room-operations/${marker.op}/${marker.key}`);clearMarker();dialog.value=false;success.value='已确认原操作成功';await load();if(drawer.value&&detail.value) await show(detail.value.id);if(maintenanceDialog.value) await loadMaintenance()
 } catch(e) { markerError.value=message(e) } finally { busy.value=false }
}
async function endRecovery() {
 try { await ElMessageBox.confirm('请先刷新并核对异常、维修记录，确认原操作结果。结束本地恢复不会撤销已成功的业务；再次提交须经过重新确认。','结束结果核对',{confirmButtonText:'已核对记录，结束恢复',cancelButtonText:'继续核对',type:'warning'});clearMarker();dialog.value=false;await load() } catch { /* keep the marker */ }
}
let maintenanceGeneration=0
async function loadMaintenance() {
 const g=++maintenanceGeneration;maintenanceStops.value=[];maintenanceError.value=''
 if(!maintenanceRoomId.value) return
 maintenanceBusy.value=true
 try { const rows=await api<MaintenanceStop[]>(`/api/rooms/${maintenanceRoomId.value}/maintenance-history`);if(g===maintenanceGeneration) maintenanceStops.value=rows }
 catch(e) { if(g===maintenanceGeneration) maintenanceError.value=message(e) } finally { if(g===maintenanceGeneration) maintenanceBusy.value=false }
}
function openMaintenance() { maintenanceDialog.value=true;void searchRooms();void loadMaintenance() }
onMounted(async () => {
 try { const saved=sessionStorage.getItem(markerName);if(saved) { const m=JSON.parse(saved);if(typeof m.key!=='string'||typeof m.op!=='string') throw new Error();recovered.value=m } }
 catch { markerError.value='原请求标识读取失败，请先核对业务记录再结束恢复' }
 try { floors.value=await api<Floor[]>('/api/floors') } catch(e) { error.value=message(e) }
 await load()
})
</script>
<template>
<section class="issues-page">
 <div class="page-heading"><div><p class="eyebrow muted">ROOM CARE</p><h1>房间异常</h1><p class="subtle">{{ manage ? '处理异常与维修停用，保留每次操作记录。' : can('stay:manage') ? '上报并查询房间问题，入住判断以最新后端状态为准。' : '上报工作中发现的问题，查看自己提交的异常记录。' }}</p></div><div class="issue-actions"><el-button :disabled="blocked" @click="open('report')">上报异常</el-button><el-button v-if="manage" :disabled="blocked" @click="open('standalone-stop')">人工维修停用</el-button><el-button @click="openMaintenance">维修记录与恢复</el-button><el-button :loading="loading" @click="load()">刷新异常</el-button></div></div>
 <el-alert v-if="success" :title="success" type="success" :closable="false" />
 <el-alert v-if="error" :title="error" type="error" :closable="false" />
 <div v-if="recovered || markerError" class="issue-recovery"><p>有待核对的操作结果。{{ markerError }}</p><el-button :loading="busy" @click="recoverResult">查询原操作结果</el-button><el-button :disabled="busy" @click="endRecovery">核对后结束恢复</el-button></div>
 <div class="issue-metrics" aria-label="异常统计"><div class="room-metric"><span>{{ can('stay:manage') || manage ? '未解决异常' : '我上报的未解决异常' }}</span><strong>{{ stats ? stats.open+stats.inProgress : '—' }}</strong></div><div class="room-metric"><span>处理中</span><strong>{{ stats?.inProgress ?? '—' }}</strong></div><div class="room-metric"><span>未解决严重异常</span><strong>{{ stats?.critical ?? '—' }}</strong></div><div class="room-metric"><span>全店维修停用房间</span><strong>{{ stats?.maintenanceRooms ?? '—' }}</strong></div></div>
 <form class="issue-filters" @submit.prevent="load(true)"><label>异常房号搜索<input v-model="filters.roomNumber" maxlength="20" /></label><label>异常楼层<select v-model="filters.floor" aria-label="异常楼层"><option value="">全部楼层</option><option v-for="f in floors" :key="f.floor" :value="f.floor">{{ floorName(f.floor) }}</option></select></label><label>异常类型<select v-model="filters.issueType" aria-label="异常类型"><option value="">全部类型</option><option v-for="(v,k) in issueTypes" :key="k" :value="k">{{ v }}</option></select></label><label>严重程度<select v-model="filters.severity" aria-label="严重程度"><option value="">全部程度</option><option v-for="(v,k) in severities" :key="k" :value="k">{{ v }}</option></select></label><label>处理状态<select v-model="filters.status" aria-label="处理状态"><option value="">全部状态</option><option v-for="(v,k) in issueStates" :key="k" :value="k">{{ v }}</option></select></label><el-button native-type="submit">筛选异常</el-button><el-button @click="filters={roomNumber:'',floor:'',issueType:'',severity:'',status:''};selectedRoomFilter=undefined;load(true)">重置筛选</el-button></form>
 <p v-if="selectedRoomFilter" class="subtle">当前仅查看所选房间的授权异常；重置筛选可查看其他房间。</p>
 <div v-loading="loading" class="issue-list"><article v-for="i in result?.items" :key="i.id" class="issue-card"><div class="issue-card-heading"><strong>{{ i.roomNumber }}</strong><el-tag :type="i.severity==='CRITICAL'?'danger':'info'">{{ severities[i.severity] }}</el-tag><el-tag>{{ issueStates[i.status] }}</el-tag></div><p class="subtle">{{ floorName(i.floor) }} · {{ i.roomTypeName }} · {{ issueTypes[i.issueType] }}</p><p class="issue-text">{{ i.description }}</p><p class="subtle">{{ i.reporterName }} · {{ localTime(i.reportedAt) }}</p><el-button @click="show(i.id)">查看异常 {{ i.id }}</el-button></article><p v-if="result?.total===0 && !loading">暂无符合条件的异常记录。</p></div>
 <el-pagination v-if="result && result.total>20" layout="prev,pager,next" :total="result.total" :page-size="20" v-model:current-page="page" @current-change="load()" />
 <el-drawer v-model="drawer" title="异常详情与处理" size="min(600px,100vw)"><div v-loading="detailBusy"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="detail"><h2>{{ detail.roomNumber }} · {{ issueTypes[detail.issueType] }}</h2><p>{{ severities[detail.severity] }} · {{ issueStates[detail.status] }}</p><p class="issue-text">{{ detail.description }}</p><p v-if="detail.notes" class="issue-text">备注：{{ detail.notes }}</p><p class="subtle">上报：{{ detail.reporterName }} · {{ localTime(detail.reportedAt) }}</p><p v-if="detail.resolution" class="issue-text">解决说明：{{ detail.resolution }}</p><div v-if="manage" class="issue-actions"><el-button v-if="detail.status==='OPEN'" :disabled="blocked" @click="open('start')">开始处理</el-button><el-button v-if="detail.status!=='RESOLVED'" :disabled="blocked" @click="open('resolve')">确认解决</el-button><el-button v-if="detail.status!=='RESOLVED'" :disabled="blocked" @click="open('severity')">调整严重程度</el-button><el-button v-if="!activeStop" :disabled="blocked" @click="open('stop')">维修停用</el-button><el-button v-else :disabled="blocked" @click="open('resume')">解除维修停用</el-button></div>
 <h3>异常操作历史</h3><ol class="issue-history"><li v-for="l in logs" :key="l.id"><strong>{{ issueActions[l.action] }} · {{ issueStates[l.toStatus] }}</strong><p>{{ l.operatorName }} · {{ localTime(l.operatedAt) }}</p><p>{{ l.fromSeverity ? severities[l.fromSeverity]+' → ' : '' }}{{ severities[l.toSeverity] }}</p><p class="issue-text">{{ l.reason }}</p></li></ol><h3>本房间维修停用记录</h3><article v-for="s in stops" :key="s.id" class="issue-stop"><strong>{{ s.resumedAt ? '已解除' : '有效停用' }}</strong><p class="issue-text">{{ s.reason }}</p><p>{{ s.stoppedByName }} · {{ localTime(s.stoppedAt) }}</p><p v-if="s.resumedAt" class="issue-text">恢复：{{ s.resumedByName }} · {{ localTime(s.resumedAt) }} · {{ s.resumeReason }}</p></article><p v-if="!stops.length">暂无人工维修停用记录。</p></template></div></el-drawer>
 <el-dialog v-model="maintenanceDialog" title="房间维修记录" width="min(600px,94vw)"><label class="issue-form">查询维修记录房间<el-select v-model="maintenanceRoomId" aria-label="查询维修记录房间" filterable remote :remote-method="searchRooms" :loading="roomLoading" @change="loadMaintenance"><el-option v-for="r in rooms" :key="r.id" :value="r.id" :label="r.roomNumber+' · '+r.roomTypeName" /></el-select></label><div v-loading="maintenanceBusy"><el-alert v-if="maintenanceError" :title="maintenanceError" type="error" :closable="false"/><article v-for="s in maintenanceStops" :key="s.id" class="issue-stop"><strong>{{ s.resumedAt?'已解除':'有效停用' }}</strong><p class="issue-text">{{ s.reason }}</p><p>{{ s.stoppedByName }} · {{ localTime(s.stoppedAt) }}</p><p v-if="s.resumedAt" class="issue-text">恢复：{{ s.resumedByName }} · {{ localTime(s.resumedAt) }} · {{ s.resumeReason }}</p></article><p v-if="maintenanceRoomId&&!maintenanceBusy&&!maintenanceStops.length&&!maintenanceError">该房间暂无人工维修停用记录。</p><el-button v-if="manage&&maintenanceActive" :disabled="blocked" @click="open('standalone-resume')">解除所选房间维修停用</el-button></div></el-dialog>
 <el-dialog :model-value="dialog" :title="title" width="min(560px,94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy&&!pending" :show-close="!busy&&!pending" @close="close">
 <el-alert v-if="markerError" :title="markerError" type="warning" :closable="false" /><el-alert v-if="operationError" :title="operationError" type="error" :closable="false" />
 <form class="issue-form" @submit.prevent="submit"><fieldset :disabled="busy||!!pending"><p class="subtle">记录服务器操作时间。请勿填写住客身份证号码、电话等敏感信息。</p>
 <label v-if="operation==='report'||operation==='standalone-stop'">异常所属房间<el-select v-model="roomId" aria-label="异常所属房间" filterable remote :remote-method="searchRooms" :loading="roomLoading" placeholder="输入房号查询"><el-option v-for="r in rooms" :key="r.id" :value="r.id" :label="r.roomNumber+' · '+r.roomTypeName" /></el-select></label>
 <p v-else>房间：{{ operation==='standalone-resume' ? rooms.find(r=>r.id===maintenanceRoomId)?.roomNumber : detail?.roomNumber }}</p>
 <template v-if="operation==='report'"><label>上报异常类型<select v-model="type" aria-label="上报异常类型"><option v-for="(v,k) in issueTypes" :key="k" :value="k">{{ v }}</option></select></label><label>上报严重程度<select v-model="severity" aria-label="上报严重程度"><option v-for="(v,k) in severities" :key="k" :value="k">{{ v }}</option></select></label><label>异常描述<textarea v-model="description" maxlength="2000" rows="4" /></label><label>异常备注（选填）<textarea v-model="notes" maxlength="1000" rows="2" /></label></template>
 <template v-else><label v-if="operation==='severity'">新的严重程度<select v-model="severity" aria-label="新的严重程度"><option v-for="(v,k) in severities" :key="k" :value="k">{{ v }}</option></select></label><label>{{ operation==='start'?'处理备注（选填）':'操作原因或处理结论' }}<textarea v-model="reason" maxlength="1000" rows="4" /></label><p v-if="(operation==='resume'||operation==='standalone-resume')">仍存在严重异常时将拒绝解除；解除停用不会自动完成清洁或结束入住。</p><p v-if="operation==='resolve'">解决异常不会自动解除独立人工停用，也不会修改清洁和入住记录。</p></template></fieldset>
 <div class="issue-actions"><el-button native-type="submit" :loading="busy">{{ pending ? '重试原请求' : '确认提交' }}</el-button><el-button v-if="pending" :loading="busy" @click="recoverResult">查询原操作结果</el-button><el-button v-if="pending" :disabled="busy" @click="endRecovery">核对后结束恢复</el-button><el-button v-else :disabled="busy" @click="close">取消</el-button></div></form>
 </el-dialog>
</section>
</template>
<style scoped>
.issues-page{min-width:0}.issue-actions{display:flex;gap:10px;flex-wrap:wrap}.issue-actions .el-button{margin-left:0;min-height:42px}.issue-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:22px 0}.issue-filters{display:flex;flex-wrap:wrap;gap:12px;align-items:end;margin-bottom:24px}.issue-filters label,.issue-form label{display:flex;flex-direction:column;gap:8px;font-size:13px}.issue-filters input,.issue-filters select,.issue-form select,.issue-form textarea{font:inherit;color:inherit;max-width:100%;min-height:42px;border:1px solid #cbd6ce;border-radius:6px;padding:9px;background:#fff}.issue-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-bottom:24px}.issue-card,.issue-stop,.issue-recovery{background:#fff;border:1px solid #dde7df;border-radius:10px;padding:20px;overflow-wrap:anywhere}.issue-card-heading{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.issue-card-heading strong{font-size:25px}.issue-card p{margin:12px 0}.issue-text{white-space:pre-wrap;overflow-wrap:anywhere}.issue-history{padding-left:22px}.issue-history li{padding:8px 0}.issue-history p,.issue-stop p{margin:8px 0;font-size:13px}.issue-form fieldset{border:0;padding:0;margin:0 0 20px;min-width:0;display:grid;gap:18px}.issue-form .el-select{width:100%}.issue-form textarea{width:100%;resize:vertical}.issues-page h3{margin-top:28px}.issue-recovery{margin:16px 0;border-color:#d9ad51}
@media(max-width:700px){.issue-list{grid-template-columns:1fr}.issue-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.issue-filters label{flex:1 1 135px;min-width:0}.issue-filters select,.issue-filters input{width:100%}.issue-card{padding:16px}.issue-actions{gap:8px}.issue-actions .el-button{min-height:44px}.issue-stop{padding:12px}}
</style>
