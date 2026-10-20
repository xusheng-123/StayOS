<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElTable, ElTableColumn, ElPagination, ElSelect, ElOption, ElDrawer, ElDescriptions, ElDescriptionsItem, ElDialog, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import { localTime, floorName } from '../api/rooms'
import { auth, can } from '../stores/auth'
import type { PageResult, Room } from '../types/room'
import type { HousekeepingTask, TaskLog, TaskStatus, TaskStatistics, Employee } from '../types/housekeeping'
const names: Record<TaskStatus, string> = { PENDING: '待处理', IN_PROGRESS: '正在打扫', COMPLETED: '已完成' }
const actions: Record<string, string> = { MANUAL_CREATE: '人工创建', AUTO_CREATE: '自动创建', CLAIM: '领取任务', ASSIGN: '分配任务', REASSIGN: '重新分配', START: '开始打扫', COMPLETE: '完成清洁' }
const manage = computed(() => can('housekeeping:manage'))
const roomNumber = ref(''), floor = ref<number>(), status = ref<TaskStatus | ''>('PENDING'), scope = ref('ALL'), sort = ref('NEWEST'), assignee = ref<number>()
const floors = ref<{ floor: number; roomCount: number }[]>([]), employees = ref<Employee[]>([]), assigneeOptions = ref<Employee[]>([])
const page = ref(1), loading = ref(false), error = ref(''), success = ref(''), result = ref<PageResult<HousekeepingTask> | null>(null), stats = ref<TaskStatistics | null>(null)
const detail = ref<HousekeepingTask | null>(null), history = ref<TaskLog[]>([]), drawer = ref(false), detailLoading = ref(false), detailError = ref('')
const dialog = ref(false), operation = ref(''), selected = ref<HousekeepingTask | null>(null), reason = ref(''), target = ref<number>(), busy = ref(false), operationError = ref('')
const roomSearch = ref(''), roomOptions = ref<Room[]>([]), selectedRoom = ref<number>(), roomLoading = ref(false)
type Pending = { key: string; path: string; taskId?: number; roomId?: number; action: string; body?: Record<string, unknown>; uncertain: boolean }
const pending = ref<Pending | null>(null), brokenMarker = ref(false)
const blocked = computed(() => !!pending.value || brokenMarker.value)
const markerKey = `stayos-housekeeping-pending-${auth.user?.id}`
const metrics: { key: keyof TaskStatistics; label: string }[] = [{ key: 'pending', label: '待处理任务' }, { key: 'inProgress', label: '正在打扫' }, { key: 'completedToday', label: '今日完成' }, { key: 'mine', label: '我负责的未完成任务' }, { key: 'unassigned', label: '当前未分配任务' }]
let generation = 0, detailGeneration = 0, roomGeneration = 0
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请稍后重试' }
function typeName(task: HousekeepingTask) { return task.taskType === 'MANUAL_CLEANING' ? '人工清洁' : '退房清洁' }
function owned(task: HousekeepingTask) { return task.assignedTo === auth.user?.id }
function allowed(task: HousekeepingTask, action: string) {
  if (task.status === 'COMPLETED') return false
  if (action === 'START') return task.status === 'PENDING' && owned(task) && can('housekeeping:execute')
  if (action === 'COMPLETE') return task.status === 'IN_PROGRESS' && owned(task) && can('housekeeping:execute')
  return manage.value && (action === 'ASSIGN' ? task.assignedTo === null && task.status === 'PENDING' : task.assignedTo !== null)
}
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  const q = new URLSearchParams({ page: String(page.value), pageSize: '20', roomNumber: roomNumber.value, scope: scope.value, sort: sort.value })
  if (floor.value !== undefined) q.set('floor', String(floor.value))
  if (status.value) q.set('status', status.value)
  if (manage.value && assignee.value !== undefined) q.set('assigneeId', String(assignee.value))
  try {
    const [list, counts] = await Promise.all([api<PageResult<HousekeepingTask>>(`/api/housekeeping-tasks?${q}`), api<TaskStatistics>('/api/housekeeping-tasks/statistics')])
    if (current === generation) { result.value = list; stats.value = counts }
  } catch (cause) { if (current === generation) { error.value = message(cause); result.value = null; stats.value = null } }
  finally { if (current === generation) loading.value = false }
}
async function show(id: number) {
  const current = ++detailGeneration; drawer.value = true; detail.value = null; history.value = []; detailError.value = ''; detailLoading.value = true
  try {
    const [task, logs] = await Promise.all([api<HousekeepingTask>(`/api/housekeeping-tasks/${id}`), api<TaskLog[]>(`/api/housekeeping-tasks/${id}/history`)])
    if (current === detailGeneration) { detail.value = task; history.value = logs }
  } catch (cause) { if (current === detailGeneration) detailError.value = message(cause) }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
async function findRooms() {
  const current = ++roomGeneration; roomLoading.value = true
  try {
    const q = new URLSearchParams({ roomNumber: roomSearch.value, cleaningStatus: 'DIRTY', occupancyStatus: 'VACANT', pageSize: '100' })
    const list = await api<PageResult<Room>>(`/api/rooms?${q}`)
    if (current === roomGeneration) { roomOptions.value = list.items.filter(r => !r.hasOpenCleaningTask); selectedRoom.value = undefined }
  } catch (cause) { if (current === roomGeneration) operationError.value = message(cause) }
  finally { if (current === roomGeneration) roomLoading.value = false }
}
async function open(action: string, task?: HousekeepingTask) {
  if (blocked.value || busy.value) return
  operation.value = action; selected.value = task ?? null; reason.value = ''; target.value = undefined; selectedRoom.value = undefined; operationError.value = ''; success.value = ''; dialog.value = true
  if (action === 'MANUAL_CREATE') { roomSearch.value = ''; await findRooms() }
  if (action === 'ASSIGN' || action === 'REASSIGN') {
    try { employees.value = await api<Employee[]>('/api/housekeeping-tasks/employees') } catch (cause) { employees.value = []; operationError.value = message(cause) }
  }
}
function storeMarker(value: Pending) {
  // Only identifiers are persisted. Notes and other request contents stay in memory.
  sessionStorage.setItem(markerKey, JSON.stringify({ key: value.key, path: value.path, taskId: value.taskId, roomId: value.roomId, action: value.action }))
}
function restoreMarker() {
  try {
    const raw = sessionStorage.getItem(markerKey); if (!raw) return
    const item = JSON.parse(raw) as Pending
    if (typeof item.key !== 'string' || !/^[0-9a-f-]{36}$/i.test(item.key) || typeof item.action !== 'string' || !actions[item.action] || ['AUTO_CREATE', 'CLAIM'].includes(item.action) || typeof item.path !== 'string' || !/^\/api\/housekeeping-tasks\/(manual|[1-9]\d*\/(assign|reassign|start|complete))$/.test(item.path)) throw new Error('invalid marker')
    pending.value = { ...item, body: undefined, uncertain: true }
  } catch { brokenMarker.value = true }
}
async function submit() {
  if (busy.value) return
  if (!pending.value) {
    if (brokenMarker.value) return
    const manual = operation.value === 'MANUAL_CREATE'
    if (manual && !selectedRoom.value) { operationError.value = '请选择待打扫房间'; return }
    if (['MANUAL_CREATE', 'REASSIGN'].includes(operation.value) && !reason.value.trim()) { operationError.value = '请填写创建或重新分配原因'; return }
    if (['ASSIGN', 'REASSIGN'].includes(operation.value) && !target.value) { operationError.value = '请选择执行员工'; return }
    if (!manual && !selected.value) return
    const value: Pending = {
      key: crypto.randomUUID(), action: operation.value, taskId: selected.value?.id, roomId: selectedRoom.value, uncertain: false,
      path: manual ? '/api/housekeeping-tasks/manual' : `/api/housekeeping-tasks/${selected.value!.id}/${operation.value.toLowerCase()}`,
      body: manual ? { roomId: selectedRoom.value, reason: reason.value.trim() } : { version: selected.value!.version, ...(['ASSIGN', 'REASSIGN'].includes(operation.value) ? { assigneeId: target.value } : {}), reason: reason.value.trim() },
    }
    try { storeMarker(value) } catch { operationError.value = '无法保存请求标识，本次操作尚未发送，请检查浏览器存储设置'; return }
    pending.value = value
  }
  const value = pending.value
  if (!value.body) { operationError.value = '页面刷新后请先查询任务和历史，核对上次结果后再结束重试'; return }
  busy.value = true; operationError.value = ''; error.value = ''
  try {
    const receipt = await api<TaskLog>(value.path, { method: 'POST', headers: { 'Idempotency-Key': value.key }, body: JSON.stringify(value.body) })
    if (!receipt || !Number.isSafeInteger(receipt.id) || receipt.id < 1 || !Number.isSafeInteger(receipt.taskId) || receipt.taskId < 1 || receipt.action !== value.action || (value.taskId && receipt.taskId !== value.taskId) || !Number.isFinite(Date.parse(receipt.operatedAt))) throw new ApiError(0, 'INVALID_RESPONSE', '操作响应不完整，请重试原清洁请求确认结果')
    sessionStorage.removeItem(markerKey); pending.value = null; dialog.value = false
    success.value = `${actions[value.action]}成功 · 任务 #${receipt.taskId}`
    await load(); if (drawer.value) await show(receipt.taskId)
  } catch (cause) {
    operationError.value = message(cause)
    const uncertain = !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    if (uncertain || value.uncertain) value.uncertain = true
    else { sessionStorage.removeItem(markerKey); pending.value = null; dialog.value = false; error.value = operationError.value; await load(); error.value = operationError.value; if (drawer.value && value.taskId) await show(value.taskId) }
  } finally { busy.value = false }
}
async function endRetry() {
  try { await ElMessageBox.confirm('请先核对任务详情和操作历史。结束重试只清除本机提示，不会撤销或再次执行业务。确认已核对上次结果？', '核对后结束重试', { confirmButtonText: '已核对，结束重试', cancelButtonText: '继续核对' }) } catch { return }
  try { sessionStorage.removeItem(markerKey); pending.value = null; brokenMarker.value = false; dialog.value = false; operationError.value = ''; await load() }
  catch { error.value = '无法清除本机提示，请检查浏览器存储设置' }
}
function reset() { roomNumber.value = ''; floor.value = undefined; status.value = 'PENDING'; scope.value = 'ALL'; assignee.value = undefined; sort.value = 'NEWEST'; void load(true) }
onMounted(async () => {
  restoreMarker(); void load()
  try { floors.value = await api<{ floor: number; roomCount: number }[]>('/api/floors'); if (manage.value) { [employees.value, assigneeOptions.value] = await Promise.all([api<Employee[]>('/api/housekeeping-tasks/employees'), api<Employee[]>('/api/housekeeping-tasks/assignees')]) } }
  catch (cause) { error.value = message(cause) }
})
</script>
<template>
  <section class="rooms-page hk-page">
    <div class="page-heading"><div><p class="eyebrow muted">HOUSEKEEPING</p><h1>清洁任务工作台</h1><p class="subtle">主管分房后仍为待处理；完成清洁后，系统重新判断房间能否入住。</p></div><div class="heading-actions"><router-link v-if="manage" to="/housekeeping/assignment"><el-button type="primary">人员分房</el-button></router-link><el-button v-if="manage" type="primary" :disabled="blocked || busy" @click="open('MANUAL_CREATE')">创建人工清洁任务</el-button><el-button :loading="loading" @click="load()">刷新任务</el-button></div></div>
    <el-alert v-if="blocked" title="上次清洁操作结果尚未确认，请重试原请求或先查询任务详情和历史。" type="warning" :closable="false" show-icon />
    <div v-if="blocked" class="hk-pending"><el-button v-if="pending?.body" :loading="busy" @click="dialog = true; submit()">重试原清洁请求</el-button><el-button v-if="pending?.taskId" :disabled="busy" @click="show(pending.taskId)">查看上次任务</el-button><el-button :disabled="busy" @click="endRetry">核对后结束重试</el-button></div>
    <el-alert v-if="success" :title="success" type="success" :closable="false" show-icon />
    <div class="hk-statistics" aria-label="清洁任务统计"><div v-for="metric in metrics" :key="metric.key" class="room-metric"><span>{{ metric.label }}</span><strong :data-testid="`hk-stat-${metric.key}`">{{ stats?.[metric.key] ?? '—' }}</strong></div></div>
    <form class="room-filters" @submit.prevent="load(true)">
      <div class="filter-field"><label for="task-room">任务房号搜索</label><el-input id="task-room" v-model="roomNumber" clearable maxlength="20" /></div>
      <div class="filter-field"><label>任务楼层</label><el-select v-model="floor" aria-label="任务楼层" clearable placeholder="全部楼层"><el-option v-for="item in floors" :key="item.floor" :label="floorName(item.floor)" :value="item.floor" /></el-select></div>
      <div class="filter-field"><label>任务状态筛选</label><el-select v-model="status" aria-label="任务状态筛选"><el-option label="全部状态" value="" /><el-option v-for="(name, code) in names" :key="code" :label="name" :value="code" /></el-select></div>
      <div class="filter-field"><label>任务范围</label><el-select v-model="scope" aria-label="任务范围"><el-option :label="manage ? '全部任务' : '我的任务'" value="ALL" /><el-option label="我的任务" value="MINE" /><el-option v-if="manage" label="未分配任务池" value="UNASSIGNED" /></el-select></div>
      <div v-if="manage" class="filter-field"><label>任务负责人</label><el-select v-model="assignee" aria-label="任务负责人" clearable filterable placeholder="全部负责人"><el-option v-for="employee in assigneeOptions" :key="employee.id" :label="`${employee.displayName} · #${employee.id}`" :value="employee.id" /></el-select></div>
      <div class="filter-field"><label>创建时间排序</label><el-select v-model="sort" aria-label="创建时间排序"><el-option label="最新创建在前" value="NEWEST" /><el-option label="最早创建在前" value="OLDEST" /></el-select></div>
      <div class="filter-actions"><el-button native-type="submit" :loading="loading">查询任务</el-button><el-button @click="reset">重置</el-button></div>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel"><div class="table-caption"><h2>清洁任务</h2><span class="subtle">当前筛选共 {{ result?.total ?? '—' }} 条任务</span></div>
      <el-table class="hk-desktop" :data="result?.items ?? []" :empty-text="status === 'PENDING' ? '暂无待处理清洁任务' : '暂无符合条件的清洁任务'" style="width: 100%">
        <el-table-column prop="roomNumber" label="房号" min-width="100" /><el-table-column prop="floor" label="楼层" width="80" /><el-table-column prop="roomTypeName" label="房型" min-width="140" />
        <el-table-column label="任务类型" min-width="110"><template #default="{ row }">{{ typeName(row as HousekeepingTask) }}</template></el-table-column><el-table-column label="任务状态" min-width="110"><template #default="{ row }"><el-tag :type="row.status === 'COMPLETED' ? 'success' : row.status === 'IN_PROGRESS' ? 'primary' : 'warning'">{{ names[(row as HousekeepingTask).status] }}</el-tag></template></el-table-column>
        <el-table-column label="负责人" min-width="120"><template #default="{ row }">{{ row.assigneeName || '未分配' }}</template></el-table-column><el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ localTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" min-width="230" fixed="right"><template #default="{ row }"><el-button text @click="show(row.id)">任务详情</el-button><template v-for="action in ['ASSIGN', 'REASSIGN', 'START', 'COMPLETE']" :key="action"><el-button v-if="allowed(row as HousekeepingTask, action)" text :disabled="blocked || busy" @click="open(action, row as HousekeepingTask)">{{ actions[action] }}</el-button></template></template></el-table-column>
      </el-table>
      <div class="hk-mobile"><article v-for="task in result?.items ?? []" :key="task.id" class="hk-task-card" :aria-label="`清洁任务 ${task.roomNumber}`"><div class="hk-card-head"><strong>{{ task.roomNumber }}</strong><el-tag>{{ names[task.status] }}</el-tag></div><p>{{ floorName(task.floor) }} · {{ task.roomTypeName }}</p><p>{{ typeName(task) }} · 负责人：{{ task.assigneeName || '未分配' }}</p><small>{{ localTime(task.createdAt) }}</small><div class="hk-card-actions"><el-button @click="show(task.id)">任务详情</el-button><template v-for="action in ['ASSIGN', 'REASSIGN', 'START', 'COMPLETE']" :key="action"><el-button v-if="allowed(task, action)" type="primary" plain :disabled="blocked || busy" @click="open(action, task)">{{ actions[action] }}</el-button></template></div></article><p v-if="!result?.items.length && !loading" class="subtle">暂无符合条件的任务</p></div>
      <el-pagination v-if="result && result.total > 20" class="room-pagination" layout="prev, pager, next" :total="result.total" :page-size="20" v-model:current-page="page" @current-change="load()" />
    </div>
    <el-drawer v-model="drawer" title="清洁任务详情" size="min(560px, 94vw)"><div v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><template v-if="detail"><el-descriptions :column="1" border><el-descriptions-item label="任务编号">#{{ detail.id }}</el-descriptions-item><el-descriptions-item label="房号">{{ detail.roomNumber }}</el-descriptions-item><el-descriptions-item label="楼层">{{ floorName(detail.floor) }}</el-descriptions-item><el-descriptions-item label="房型">{{ detail.roomTypeName }}</el-descriptions-item><el-descriptions-item label="任务类型">{{ typeName(detail) }}</el-descriptions-item><el-descriptions-item label="任务来源">{{ detail.source === 'MANUAL' ? '主管人工创建' : '退房自动创建' }}</el-descriptions-item><el-descriptions-item label="任务状态">{{ names[detail.status] }}</el-descriptions-item><el-descriptions-item label="当前负责人">{{ detail.assigneeName || '未分配' }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ localTime(detail.createdAt) }}</el-descriptions-item><el-descriptions-item label="分配时间">{{ detail.assignedAt ? localTime(detail.assignedAt) : '—' }}</el-descriptions-item><el-descriptions-item label="开始时间">{{ detail.startedAt ? localTime(detail.startedAt) : '—' }}</el-descriptions-item><el-descriptions-item label="完成时间">{{ detail.completedAt ? localTime(detail.completedAt) : '—' }}</el-descriptions-item><el-descriptions-item label="任务备注">{{ detail.notes || '—' }}</el-descriptions-item><el-descriptions-item label="完成备注">{{ detail.completionNotes || '—' }}</el-descriptions-item></el-descriptions>
      <div class="hk-card-actions"><template v-for="action in ['ASSIGN', 'REASSIGN', 'START', 'COMPLETE']" :key="action"><el-button v-if="allowed(detail, action)" :disabled="blocked || busy" @click="open(action, detail)">{{ actions[action] }}</el-button></template></div>
      <h3>操作历史</h3><ol class="hk-history"><li v-for="log in history" :key="log.id"><strong>{{ actions[log.action] || log.action }}</strong><p>{{ log.fromStatus ? names[log.fromStatus] + ' → ' : '' }}{{ names[log.toStatus] }} · {{ log.operatorName }}</p><p v-if="log.fromAssignee || log.toAssignee">负责人：{{ log.fromAssigneeName || '未分配' }} → {{ log.toAssigneeName || '未分配' }}</p><p v-if="log.reason">{{ log.reason }}</p><small>{{ localTime(log.operatedAt) }}</small></li></ol></template></div><template #footer><el-button @click="drawer = false">关闭任务详情</el-button></template></el-drawer>
    <el-dialog v-model="dialog" :title="`确认${actions[operation] || '清洁操作'}`" width="min(520px, 94vw)" :close-on-click-modal="!blocked && !busy" :close-on-press-escape="!blocked && !busy" :show-close="!blocked && !busy">
      <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" />
      <template v-if="operation === 'MANUAL_CREATE'"><p>仅为空闲、待打扫且没有未完成任务的房间创建清洁任务。</p><div class="hk-room-search"><el-input v-model="roomSearch" aria-label="人工任务房号搜索" placeholder="按房号搜索" :disabled="blocked || busy" @keyup.enter="findRooms" /><el-button :loading="roomLoading" :disabled="blocked || busy" @click="findRooms">搜索房间</el-button></div><label class="hk-label">选择待打扫房间</label><el-select v-model="selectedRoom" aria-label="选择待打扫房间" filterable :disabled="blocked || busy || roomLoading" class="hk-full"><el-option v-for="room in roomOptions" :key="room.id" :label="`${room.roomNumber} · ${floorName(room.floor)} · ${room.roomTypeName}`" :value="room.id" /></el-select></template>
      <p v-else>房间 {{ selected?.roomNumber }} · {{ selected?.roomTypeName }}<br />当前负责人：{{ selected?.assigneeName || '未分配' }}</p>
      <template v-if="operation === 'ASSIGN' || operation === 'REASSIGN'"><label class="hk-label">新的执行员工</label><el-select v-model="target" aria-label="新的执行员工" filterable :disabled="blocked || busy" class="hk-full"><el-option v-for="employee in employees.filter(e => e.id !== selected?.assignedTo)" :key="employee.id" :label="`${employee.displayName} · #${employee.id}`" :value="employee.id" /></el-select></template>
      <template v-if="operation !== 'CLAIM' && operation !== 'START'"><label class="hk-label" for="hk-reason">{{ ['MANUAL_CREATE', 'REASSIGN'].includes(operation) ? '操作原因（必填）' : '操作备注（选填）' }}</label><el-input id="hk-reason" v-model="reason" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="blocked || busy" placeholder="仅填写清洁相关信息，请勿填写客人证件或联系电话" /></template>
      <p class="subtle">{{ operation === 'COMPLETE' ? '完成后房间恢复已清洁；维修、占用等条件仍会影响是否可入住。' : operation === 'REASSIGN' ? '接管会保留原执行记录和开始时间，原负责人将失去操作权限。' : operation === 'ASSIGN' ? '分配不会立即开始打扫。' : '状态与时间由服务器确认并记录。' }}</p>
      <template #footer><el-button v-if="pending" :disabled="busy" @click="dialog = false">先查询任务结果</el-button><el-button v-else :disabled="busy" @click="dialog = false">取消操作</el-button><el-button v-if="!pending || pending.body" type="primary" :loading="busy" @click="submit">{{ pending ? '重试原清洁请求' : `确认${actions[operation]}` }}</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.hk-statistics { display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin:24px 0 }
.hk-statistics .room-metric { border:1px solid var(--line,#e4e8ed);background:white;border-radius:12px;padding:16px;min-width:0 }
.hk-pending,.hk-room-search,.hk-card-actions { display:flex;flex-wrap:wrap;gap:10px;margin:14px 0 }
.hk-pending .el-button,.hk-card-actions .el-button { margin-left:0 }
.hk-room-search .el-input { flex:1;min-width:120px }.hk-label { display:block;margin:16px 0 8px }.hk-full { width:100% }
.hk-mobile { display:none }.hk-history { list-style:none;padding:0 }.hk-history li { padding:14px 0 14px 14px;border-left:3px solid #b9c8c2;border-bottom:1px solid #e8eceb;overflow-wrap:anywhere }.hk-history p { margin:7px 0 }.hk-history small { color:#68776f }
@media(max-width:900px) { .hk-statistics { grid-template-columns:repeat(3,minmax(0,1fr)) } }
@media(max-width:640px) { .hk-desktop { display:none }.hk-mobile { display:grid;gap:12px }.hk-statistics { grid-template-columns:repeat(2,minmax(0,1fr));gap:8px }.hk-statistics .room-metric { padding:12px }.hk-task-card { border:1px solid #e0e6e3;border-radius:12px;padding:16px;overflow-wrap:anywhere }.hk-card-head { display:flex;align-items:center;justify-content:space-between;gap:8px }.hk-card-head strong { font-size:28px }.hk-task-card p { margin:10px 0 }.hk-card-actions .el-button,.hk-pending .el-button { min-height:44px }.hk-page { min-width:0 }.hk-room-search { flex-wrap:nowrap } }
</style>
