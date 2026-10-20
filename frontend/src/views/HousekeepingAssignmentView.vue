<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElSelect, ElOption, ElDialog, ElMessageBox, ElPagination } from 'element-plus'
import { api, ApiError } from '../api/client'
import { auth } from '../stores/auth'
import { floorName, localTime } from '../api/rooms'
import type { AssignmentEmployee, AssignmentRoom, AssignmentReceipt } from '../types/assignment'
import type { HousekeepingTask, TaskLog } from '../types/housekeeping'
import type { PageResult } from '../types/room'
const employees = ref<AssignmentEmployee[]>([]), employee = ref<AssignmentEmployee | null>(null)
const tasks = ref<HousekeepingTask[]>([]), result = ref<PageResult<AssignmentRoom> | null>(null)
const floors = ref<{ floor: number }[]>([]), types = ref<{ id: number; typeName: string }[]>([])
const floor = ref<number>(), roomType = ref<number>(), search = ref(''), page = ref(1)
const selected = ref<AssignmentRoom[]>([]), error = ref(''), success = ref(''), loading = ref(false), busy = ref(false), dialog = ref(false), reason = ref('')
const detail = ref<HousekeepingTask | null>(null), history = ref<TaskLog[]>([]), detailOpen = ref(false)
const statusNames: Record<string, string> = { AVAILABLE_MANUAL: '可分配 · 自动建单', AVAILABLE_TASK: '可分配 · 已有任务', ASSIGNED_SELF: '已分配给此员工', ASSIGNED_OTHER: '已分配给其他员工', IN_PROGRESS: '正在打扫', CLEAN: '已清洁 · 无需分配', OCCUPIED: '已入住 · 不可分配', BLOCKED: '状态不符 · 不可分配' }
type Pending = { key: string; employeeId: number; roomIds: number[]; body?: { assigneeId: number; roomIds: number[]; reason: string }; uncertain: boolean }
const pending = ref<Pending | null>(null), broken = ref(false)
const marker = `stayos-batch-assignment-${auth.user?.id}`
const blocked = computed(() => !!pending.value || broken.value || busy.value)
const selectedNumbers = computed(() => selected.value.map(r => r.roomNumber).join('、'))
let generation = 0
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请稍后重试' }
async function loadEmployees() {
  employees.value = await api<AssignmentEmployee[]>('/api/housekeeping-tasks/assignment-employees')
  if (employee.value) employee.value = employees.value.find(e => e.id === employee.value?.id) ?? null
}
async function loadRooms(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  const q = new URLSearchParams({ page: String(page.value), pageSize: '100', roomNumber: search.value })
  if (employee.value) q.set('assigneeId', String(employee.value.id))
  if (floor.value !== undefined) q.set('floor', String(floor.value))
  if (roomType.value !== undefined) q.set('roomTypeId', String(roomType.value))
  try {
    const [rooms, assigned] = await Promise.all([api<PageResult<AssignmentRoom>>(`/api/housekeeping-tasks/assignment-rooms?${q}`), employee.value ? api<HousekeepingTask[]>(`/api/housekeeping-tasks/assignment-employees/${employee.value.id}/tasks`) : Promise.resolve([])])
    if (current === generation) { result.value = rooms; tasks.value = assigned }
  } catch (cause) { if (current === generation) { result.value = null; tasks.value = []; error.value = message(cause) } }
  finally { if (current === generation) loading.value = false }
}
async function choose(item: AssignmentEmployee) {
  if (blocked.value) return
  employee.value = item; selected.value = []; success.value = ''; await loadRooms(true)
}
function checked(room: AssignmentRoom) { return selected.value.some(r => r.roomId === room.roomId) }
function toggle(room: AssignmentRoom) {
  if (blocked.value || !employee.value || !room.assignable) return
  if (checked(room)) selected.value = selected.value.filter(r => r.roomId !== room.roomId)
  else if (selected.value.length < 100) selected.value.push(room)
  else error.value = '每批最多选择 100 间房'
}
async function selectAll() {
  if (blocked.value || !employee.value || loading.value) return
  loading.value = true; error.value = ''
  try {
    const chosen = new Map(selected.value.map(r => [r.roomId, r]))
    let index = 1
    while (true) {
      const q = new URLSearchParams({ page: String(index), pageSize: '100', roomNumber: search.value, assigneeId: String(employee.value.id) })
      if (floor.value !== undefined) q.set('floor', String(floor.value))
      if (roomType.value !== undefined) q.set('roomTypeId', String(roomType.value))
      const rooms = await api<PageResult<AssignmentRoom>>(`/api/housekeeping-tasks/assignment-rooms?${q}`)
      for (const room of rooms.items) if (room.assignable) chosen.set(room.roomId, room)
      if (chosen.size > 100) { error.value = '当前筛选可分配房间超过本批 100 间上限，请缩小筛选范围；原选择保留'; return }
      if (index * 100 >= rooms.total || rooms.items.length === 0) break
      index++
    }
    selected.value = [...chosen.values()]
  } catch (cause) { error.value = message(cause) }
  finally { loading.value = false }
}
function confirm() { if (selected.value.length && employee.value && !blocked.value) { reason.value = ''; error.value = ''; dialog.value = true } }
async function refresh() { try { await loadEmployees(); selected.value = []; await loadRooms() } catch (cause) { error.value = message(cause) } }
function clearMarker() { sessionStorage.removeItem(marker); pending.value = null; broken.value = false }
async function accepted(receipt: AssignmentReceipt, value: Pending) {
  const ids = receipt?.items?.map(r => r.roomId)
  if (!receipt || !Number.isSafeInteger(receipt.id) || receipt.id < 1 || receipt.assigneeId !== value.employeeId || !Array.isArray(ids) || ids.length !== value.roomIds.length || new Set(ids).size !== ids.length || !value.roomIds.every(id => ids.includes(id)) || !Number.isFinite(Date.parse(receipt.createdAt))) throw new ApiError(0, 'INVALID_RESPONSE', '分房响应不完整，请查询结果或重试原批次')
  clearMarker(); dialog.value = false; selected.value = []; success.value = `分房成功：${receipt.items.length} 间房，批次 #${receipt.id}`; await refresh()
}
async function submit() {
  if (busy.value || broken.value) return
  if (!pending.value) {
    if (!employee.value || !selected.value.length) return
    const ids = selected.value.map(r => r.roomId)
    const value: Pending = { key: crypto.randomUUID(), employeeId: employee.value.id, roomIds: ids, body: { assigneeId: employee.value.id, roomIds: ids, reason: reason.value.trim() }, uncertain: false }
    try { sessionStorage.setItem(marker, JSON.stringify({ key: value.key, employeeId: value.employeeId, roomIds: ids })) }
    catch { error.value = '无法保存请求标识，本次分房尚未发送'; return }
    pending.value = value
  }
  const value = pending.value
  if (!value.body) { error.value = '页面已刷新，请先查询原批次结果'; return }
  busy.value = true; error.value = ''
  try { await accepted(await api<AssignmentReceipt>('/api/housekeeping-tasks/batch-assign', { method: 'POST', headers: { 'Idempotency-Key': value.key }, body: JSON.stringify(value.body) }), value) }
  catch (cause) {
    const uncertain = !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    error.value = message(cause)
    if (uncertain || value.uncertain) value.uncertain = true
    else { clearMarker(); dialog.value = false; selected.value = []; const text = error.value; await refresh(); error.value = text }
  } finally { busy.value = false }
}
async function queryResult() {
  if (!pending.value || busy.value) return
  busy.value = true
  try { await accepted(await api<AssignmentReceipt>(`/api/housekeeping-tasks/assignment-results/${pending.value.key}`), pending.value) }
  catch (cause) { error.value = `${message(cause)}。尚未查到成功结果不代表请求已取消，请稍后查询或重试原批次` }
  finally { busy.value = false }
}
async function endRetry() {
  try { await ElMessageBox.confirm('请先查询原批次结果并核对员工当前房间。结束重试只清除本机提示，不会撤销已成功分房。', '核对后结束重试', { confirmButtonText: '已核对，结束重试', cancelButtonText: '继续核对' }) } catch { return }
  try { clearMarker(); dialog.value = false; await refresh() } catch (cause) { error.value = message(cause) }
}
async function showTask(id: number) {
  try { [detail.value, history.value] = await Promise.all([api<HousekeepingTask>(`/api/housekeeping-tasks/${id}`), api<TaskLog[]>(`/api/housekeeping-tasks/${id}/history`)]); detailOpen.value = true }
  catch (cause) { error.value = message(cause) }
}
onMounted(async () => {
  try {
    const raw = sessionStorage.getItem(marker)
    if (raw) {
      const value = JSON.parse(raw) as Pending
      if (!/^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.test(value.key) || !Number.isSafeInteger(value.employeeId) || !Array.isArray(value.roomIds) || value.roomIds.length < 1 || value.roomIds.length > 100 || !value.roomIds.every(id => Number.isSafeInteger(id) && id > 0)) throw new Error('invalid marker')
      pending.value = { ...value, body: undefined, uncertain: true }
    }
  } catch { broken.value = true }
  try {
    await loadEmployees()
    const [fs, ts] = await Promise.all([api<{ floor: number }[]>('/api/floors'), api<PageResult<{ id: number; typeName: string }>>('/api/room-types?pageSize=100')])
    floors.value = fs; types.value = ts.items; await loadRooms()
  } catch (cause) { error.value = message(cause) }
})
</script>
<template>
  <section class="rooms-page assignment-page">
    <div class="page-heading"><div><p class="eyebrow muted">HOUSEKEEPING / ASSIGNMENT</p><h1>人员分房</h1><p class="subtle">先选择员工，再勾选多间房。没有任务的合法待打扫房间将自动建单。</p></div><el-button :disabled="busy" @click="refresh">刷新分房数据</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="success" :title="success" type="success" :closable="false" show-icon />
    <div v-if="pending || broken" class="assignment-warning"><el-alert title="上次批量分房结果尚未确认，请查询原批次或安全重试。" type="warning" :closable="false" /><div class="assignment-actions"><el-button v-if="pending" :loading="busy" @click="queryResult">查询原批次结果</el-button><el-button v-if="pending?.body" :loading="busy" @click="submit">重试原批次</el-button><el-button :disabled="busy" @click="endRetry">核对后结束重试</el-button></div></div>
    <h2>选择执行员工</h2>
    <div class="employee-grid"><button v-for="item in employees" :key="item.id" type="button" :aria-label="`选择员工 ${item.displayName}`" :aria-pressed="employee?.id === item.id" :disabled="blocked" class="employee-card" @click="choose(item)"><strong>{{ item.displayName }}</strong><span>{{ item.username }} · #{{ item.id }} · 已启用</span><div class="employee-counts"><span>待开始 <b>{{ item.pending }}</b></span><span>清洁中 <b>{{ item.inProgress }}</b></span><span>今日完成 <b>{{ item.completedToday }}</b></span></div></button></div>
    <p v-if="!employees.length" class="subtle">暂无启用且具备清洁执行资格的员工。</p>
    <div class="assignment-columns">
      <aside class="assignment-current"><h2>{{ employee ? `${employee.displayName} · 当前负责房间` : '请先选择员工' }}</h2><p class="subtle">只显示尚未完成任务；已开始的任务不能被普通批量分房抢占。</p><article v-for="task in tasks" :key="task.id" class="assigned-room"><strong>{{ task.roomNumber }} · {{ task.status === 'IN_PROGRESS' ? '正在打扫' : '尚未开始' }}</strong><p>{{ floorName(task.floor) }} · {{ task.roomTypeName }} · {{ task.source === 'MANUAL' ? '人工建单' : task.source === 'ROOM_CHANGE' ? '换房清洁' : '退房清洁' }}</p><small>分配：{{ task.assignedAt ? localTime(task.assignedAt) : '—' }}<br />开始：{{ task.startedAt ? localTime(task.startedAt) : '—' }}</small><el-button text @click="showTask(task.id)">任务详情</el-button></article><p v-if="employee && !tasks.length">该员工暂无未完成任务。</p></aside>
      <div class="assignment-choices" v-loading="loading"><h2>选择需要清洁的房间</h2>
        <form class="room-filters" @submit.prevent="loadRooms(true)"><div class="filter-field"><label for="assignment-search">分房房号搜索</label><el-input id="assignment-search" v-model="search" maxlength="20" clearable /></div><div class="filter-field"><label>分房楼层</label><el-select v-model="floor" aria-label="分房楼层" clearable placeholder="全部楼层"><el-option v-for="f in floors" :key="f.floor" :label="floorName(f.floor)" :value="f.floor" /></el-select></div><div class="filter-field"><label>分房房型</label><el-select v-model="roomType" aria-label="分房房型" clearable placeholder="全部房型"><el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" /></el-select></div><el-button native-type="submit">筛选房间</el-button></form>
        <div class="assignment-actions"><el-button :disabled="!employee || blocked || loading" @click="selectAll">全选当前筛选可分配房间</el-button><el-button :disabled="blocked" @click="selected = []">清空选择</el-button><span>筛选共 {{ result?.total ?? '—' }} 间 · 每批最多 100 间</span></div>
        <div class="assignment-room-grid"><label v-for="room in result?.items ?? []" :key="room.roomId" class="assignment-room" :class="{ selected: checked(room), unavailable: !room.assignable }"><input type="checkbox" :aria-label="`分配房间 ${room.roomNumber}`" :checked="checked(room)" :disabled="!room.assignable || !employee || blocked || loading" @change="toggle(room)" /><span><strong>{{ room.roomNumber }}</strong><small>{{ floorName(room.floor) }} · {{ room.roomTypeName }}</small><b class="assignment-state">{{ statusNames[room.assignmentState] || '不可分配' }}</b><small v-if="room.assigneeName">负责人：{{ room.assigneeName }}</small><small v-if="!room.enabled">档案停用（允许清洁，不能入住）</small><small v-if="room.hasBlockingIssue">维修阻止（清洁不会解除）</small></span></label></div>
        <el-pagination v-if="result && result.total > 100" layout="prev, pager, next" :total="result.total" :page-size="100" v-model:current-page="page" @current-change="loadRooms()" />
        <p v-if="result && !result.total">没有符合筛选条件的房间。</p>
      </div>
    </div>
    <div class="assignment-selection"><strong>已选 {{ selected.length }} 间房</strong><p data-testid="selected-room-numbers">{{ selectedNumbers || '尚未选择房间' }}</p><div class="assignment-actions"><el-button v-for="room in selected" :key="room.roomId" :disabled="blocked" size="small" @click="toggle(room)">取消 {{ room.roomNumber }}</el-button></div><el-button type="primary" :disabled="!employee || !selected.length || blocked || loading" @click="confirm">确认分配</el-button></div>
    <el-dialog v-model="dialog" title="确认批量分房" width="min(560px, 94vw)" :close-on-click-modal="!blocked" :close-on-press-escape="!blocked" :show-close="!blocked"><p>目标员工：<strong>{{ employee?.displayName }}</strong></p><p>本次分配：<strong>{{ selected.length }} 间房</strong></p><p class="assignment-numbers">分配房间：{{ selectedNumbers }}</p><label for="batch-reason">分房备注（选填）</label><el-input id="batch-reason" v-model="reason" type="textarea" :disabled="blocked" maxlength="1000" placeholder="仅填写清洁安排，不要填写住客证件或电话" /><p class="subtle">整批成功或全部失败；已有未分配任务将直接使用，其余合法待打扫房间自动创建人工任务。</p><el-alert v-if="error" :title="error" type="error" :closable="false" /><template #footer><el-button :disabled="busy" @click="dialog = false">{{ pending ? '先查询结果' : '取消' }}</el-button><el-button v-if="!pending || pending.body" type="primary" :loading="busy" @click="submit">{{ pending ? '重试原批次' : '确认本批分房' }}</el-button></template></el-dialog>
    <el-dialog v-model="detailOpen" title="分房任务详情" width="min(560px, 94vw)"><template v-if="detail"><p>{{ detail.roomNumber }} · {{ detail.roomTypeName }} · {{ detail.assigneeName }}</p><p>来源：{{ detail.source }} · 状态：{{ detail.status }}</p><h3>操作历史</h3><ol><li v-for="log in history" :key="log.id">{{ log.action }} · {{ log.operatorName }} · {{ localTime(log.operatedAt) }}<p>{{ log.fromAssigneeName || '未分配' }} → {{ log.toAssigneeName || '未分配' }} {{ log.reason }}</p></li></ol></template></el-dialog>
  </section>
</template>
<style scoped>
.assignment-page { min-width:0 }.employee-grid { display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px;margin:16px 0 24px }.employee-card { text-align:left;border:1px solid #dce4df;border-radius:14px;padding:18px;background:white;color:inherit;cursor:pointer;font:inherit;min-width:0 }.employee-card[aria-pressed=true] { outline:2px solid #34745b;background:#f1f8f4 }.employee-card>strong { display:block;font-size:19px }.employee-card>span { display:block;font-size:12px;margin-top:8px;overflow-wrap:anywhere;color:#68776f }.employee-counts { display:flex;flex-wrap:wrap;gap:14px;margin-top:18px;font-size:13px }.employee-counts b { display:block;font-size:22px;margin-top:5px }.assignment-columns { display:grid;grid-template-columns:minmax(240px,1fr) minmax(0,2fr);gap:22px;align-items:start }.assignment-current,.assignment-choices,.assignment-selection { border:1px solid #dce4df;border-radius:14px;background:white;padding:20px;min-width:0 }.assigned-room { border-top:1px solid #e3e9e5;padding:16px 0;overflow-wrap:anywhere }.assigned-room p { font-size:13px }.assigned-room small { line-height:1.8;color:#68776f }.assignment-room-grid { display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));gap:10px }.assignment-room { display:flex;gap:12px;align-items:flex-start;border:1px solid #dce4df;border-radius:10px;padding:14px;min-height:95px;cursor:pointer;min-width:0 }.assignment-room.selected { background:#eef7f1;border-color:#34745b }.assignment-room.unavailable { background:#f5f5f3;color:#6d7771;cursor:default }.assignment-room input { width:22px;height:22px;flex:none;accent-color:#34745b }.assignment-room strong { font-size:22px;display:block;overflow-wrap:anywhere }.assignment-room small,.assignment-state { display:block;margin-top:8px;font-size:12px;overflow-wrap:anywhere }.assignment-room>span { min-width:0 }.assignment-state { color:#34745b }.unavailable .assignment-state { color:#6d7771 }.assignment-actions { display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:14px 0 }.assignment-actions .el-button { margin-left:0 }.assignment-selection { position:sticky;bottom:12px;margin-top:18px;box-shadow:0 5px 24px #18251c10;z-index:2 }.assignment-selection p,.assignment-numbers { overflow-wrap:anywhere;max-height:80px;overflow:auto }.assignment-warning { margin:18px 0 }
@media(max-width:900px) { .assignment-columns { grid-template-columns:1fr }.assignment-current { max-height:360px;overflow:auto } }
@media(max-width:640px) { .employee-grid { grid-template-columns:1fr }.assignment-current,.assignment-choices,.assignment-selection { padding:14px }.assignment-room-grid { grid-template-columns:repeat(2,minmax(0,1fr)) }.assignment-room { padding:10px;gap:7px }.assignment-page :deep(.el-button) { min-height:44px }.assignment-selection { position:static }.assignment-room strong { font-size:20px } }
</style>
