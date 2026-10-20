<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElDialog, ElDescriptions, ElDescriptionsItem, ElMessageBox, ElPagination, ElEmpty } from 'element-plus'
import { api, ApiError } from '../api/client'
import { roomOptions, roomQuery, localTime, floorName } from '../api/rooms'
import { auth } from '../stores/auth'
import { changeReasonNames, type Stay, type RoomChange, type RoomChangeInput } from '../types/stay'
import type { Room, RoomFilters, RoomType, Floor, PageResult } from '../types/room'
import type { RoomIssue as Issue } from '../types/issue'
const emit = defineEmits<{ completed: [] }>()
const visible = ref(false), busy = ref(false), loading = ref(false), error = ref(''), stay = ref<Stay | null>(null)
const filters = ref<RoomFilters>({ roomNumber: '' }), page = ref(1), rooms = ref<Room[]>([]), total = ref(0)
const types = ref<RoomType[]>([]), floors = ref<Floor[]>([]), target = ref<Room | null>(null), issues = ref<Issue[]>([])
const reasonType = ref<RoomChangeInput['reasonType']>('GUEST_REQUEST'), reason = ref(''), issueId = ref<number | undefined>()
const pending = ref<{ key: string; stayId: number; body: RoomChangeInput } | null>(null), orphan = ref(''), result = ref<RoomChange | null>(null)
const locked = computed(() => busy.value || pending.value !== null || Boolean(orphan.value))
const storageKey = `stayos:room-change-pending:${auth.user!.id}`
let generation = 0, uncertain = false
function message(e: unknown) { return e instanceof Error ? e.message : '操作失败，请稍后重试' }
async function load(reset = false) {
 if (!stay.value) return
 if (reset) page.value = 1
 const current = ++generation; loading.value = true
 try {
  const r = await api<PageResult<Room>>(`/api/stays/${stay.value.id}/room-change/available-rooms?${roomQuery(filters.value, page.value, 8)}`)
  if (current === generation) { rooms.value = r.items; total.value = r.total }
 } catch (e) { if (current === generation) { error.value = message(e); rooms.value = []; total.value = 0 } }
 finally { if (current === generation) loading.value = false }
}
async function open(s: Stay) {
 if (locked.value) { visible.value = true; return }
 stay.value = s; target.value = null; reason.value = ''; reasonType.value = 'GUEST_REQUEST'; issueId.value = undefined; issues.value = []
 result.value = null; error.value = ''; filters.value = { roomNumber: '' }; page.value = 1; visible.value = true
 try {
  const options = await roomOptions(); types.value = options.types; floors.value = options.floors
  // All associated issues are real source-room records; no guest data is requested.
  let n = 1; const collected: Issue[] = []
  while (true) { const r = await api<PageResult<Issue>>(`/api/room-issues?roomId=${s.roomId}&pageSize=100&page=${n++}`); collected.push(...r.items); if (collected.length >= r.total || !r.items.length) break }
  issues.value = collected
  await load()
 } catch (e) { error.value = message(e) }
}
defineExpose({ open })
watch(reasonType, () => { if (!locked.value) issueId.value = undefined })
function forget() { sessionStorage.removeItem(storageKey); pending.value = null; orphan.value = ''; uncertain = false }
function verify(r: RoomChange) {
 if (!r || !Number.isSafeInteger(r.id) || !Number.isSafeInteger(r.taskId) || r.id < 1 || r.taskId < 1 || !r.changedAt || !Number.isFinite(Date.parse(r.changedAt))) throw new ApiError(200, 'INVALID_RESPONSE', '换房响应不完整，请查询或重试原操作')
 if (pending.value && (r.stayRecordId !== pending.value.stayId || r.oldSegmentId !== pending.value.body.segmentId || r.newRoomId !== pending.value.body.targetRoomId)) throw new ApiError(200, 'INVALID_RESPONSE', '换房结果不匹配，请保留原标识核查')
}
async function submit() {
 if (busy.value || orphan.value || result.value) return
 error.value = ''
 if (!pending.value) {
  if (!stay.value || !target.value || !reason.value.trim()) { error.value = '请选择目标房间并填写换房说明'; return }
  busy.value = true
  try { await ElMessageBox.confirm(`${stay.value.roomNumber} → ${target.value.roomNumber}。原因：${reason.value.trim()}。原房将释放并创建清洁任务，住客在新房继续入住。`, '确认正式换房', { confirmButtonText: '确认换房', cancelButtonText: '继续核对' }) } catch { busy.value = false; return }
  busy.value = false
  const p = { key: crypto.randomUUID(), stayId: stay.value.id, body: { segmentId: stay.value.segmentId, targetRoomId: target.value.id, reasonType: reasonType.value, reason: reason.value.trim(), ...(reasonType.value === 'ROOM_ISSUE' && issueId.value ? { issueId: issueId.value } : {}) } }
  try { sessionStorage.setItem(storageKey, p.key) } catch { error.value = '无法保存安全重试标识，请检查浏览器存储'; return }
  pending.value = p
 }
 busy.value = true
 try {
  const p = pending.value
  const r = await api<RoomChange>(`/api/stays/${p.stayId}/room-changes`, { method: 'POST', headers: { 'Idempotency-Key': p.key }, body: JSON.stringify(p.body) })
  verify(r); result.value = r; forget(); reason.value = ''; emit('completed')
 } catch (e) {
  uncertain ||= !(e instanceof ApiError) || e.status === 0 || e.status === 401 || e.status >= 500 || e.code === 'INVALID_RESPONSE'
  error.value = message(e) + (uncertain ? '。结果尚未确认，请重试原请求或查询原操作结果。' : '')
  if (!uncertain) { forget(); target.value = null; void load() }
 } finally { busy.value = false }
}
async function queryResult() {
 const key = pending.value?.key || orphan.value
 if (!key || busy.value) return
 busy.value = true; error.value = ''
 try { const r = await api<RoomChange>(`/api/stays/room-changes/result/${key}`); verify(r); result.value = r; forget(); reason.value = ''; emit('completed') }
 catch (e) { error.value = message(e) + '。未查到结果不代表已失败，请核对入住和换房历史。' }
 finally { busy.value = false }
}
async function acknowledge() {
 try { await ElMessageBox.confirm('请先刷新入住详情及换房历史，确认上次结果。结束重试只清除本机标识，不撤销操作，也不重新执行换房。是否已完成核对？', '核对换房结果', { confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留' }) } catch { return }
 forget(); visible.value = false; emit('completed')
}
onMounted(() => { const key = sessionStorage.getItem(storageKey); if (key) { orphan.value = key; visible.value = true; error.value = '发现尚未确认的换房操作，请查询原操作结果。刷新后不会自动重新提交。' } })
</script>
<template>
 <el-dialog v-model="visible" :title="result ? '换房成功' : '办理换房'" width="min(860px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" class="change-dialog">
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <template v-if="result">
   <el-alert title="换房成功，原房清洁任务已创建" type="success" :closable="false" show-icon />
   <el-descriptions :column="1" border class="change-summary"><el-descriptions-item label="入住记录">#{{ result.stayRecordId }} · 继续入住</el-descriptions-item><el-descriptions-item label="原房间">{{ result.oldRoomNumber }} · 待打扫</el-descriptions-item><el-descriptions-item label="新房间">{{ result.newRoomNumber }} · 已入住</el-descriptions-item><el-descriptions-item label="实际换房时间">{{ localTime(result.changedAt) }}</el-descriptions-item><el-descriptions-item label="经办人">{{ result.operatorName }}</el-descriptions-item><el-descriptions-item label="原房清洁任务">#{{ result.taskId }} · 已创建</el-descriptions-item></el-descriptions>
   <p class="subtle">原房异常和维修停用记录继续保留；清洁完成后仍需满足全部入住条件。</p>
   <router-link :to="`/stays/${result.stayRecordId}`" @click="visible = false"><el-button>查看本次入住历史</el-button></router-link><router-link to="/rooms" @click="visible = false"><el-button>查看房态图</el-button></router-link>
  </template>
  <template v-else-if="!orphan && stay">
   <h3>当前入住</h3><el-descriptions :column="1" border><el-descriptions-item label="当前房间">{{ stay.roomNumber }} · {{ stay.roomTypeName }}</el-descriptions-item><el-descriptions-item label="主要联系人">{{ stay.primaryGuestName }}</el-descriptions-item><el-descriptions-item label="实际登记人数">{{ stay.guestCount }}</el-descriptions-item><el-descriptions-item label="实际入住">{{ localTime(stay.checkInAt) }}</el-descriptions-item><el-descriptions-item label="预计退房">{{ localTime(stay.expectedCheckOutAt) }}</el-descriptions-item></el-descriptions>
   <h3>选择目标房间</h3><p class="subtle">仅显示后端判定可入住的房间；提交时将再次验证。跨房型不涉及费用计算。</p>
   <form class="change-filters" @submit.prevent="load(true)"><label>目标房号<el-input v-model="filters.roomNumber" aria-label="换房目标房号" maxlength="20" :disabled="locked" /></label><label>目标楼层<select v-model="filters.floor" aria-label="换房目标楼层" :disabled="locked"><option :value="undefined">全部楼层</option><option v-for="f in floors" :key="f.floor" :value="f.floor">{{ floorName(f.floor) }}</option></select></label><label>目标房型<select v-model="filters.roomTypeId" aria-label="换房目标房型" :disabled="locked"><option :value="undefined">全部房型</option><option v-for="t in types" :key="t.id" :value="t.id">{{ t.typeName }}</option></select></label><el-button native-type="submit" :disabled="locked" :loading="loading">筛选目标房</el-button></form>
   <div v-loading="loading" class="change-rooms"><button v-for="r in rooms" :key="r.id" type="button" :aria-label="`换入房间 ${r.roomNumber}`" :aria-pressed="target?.id === r.id" :class="{ selected: target?.id === r.id }" :disabled="locked || !r.canCheckIn" @click="target = r"><strong>{{ r.roomNumber }} · {{ floorName(r.floor) }}</strong><span>{{ r.roomTypeName }}</span><span>已清洁 · 可入住</span></button></div>
   <el-empty v-if="!loading && !rooms.length" description="暂无符合条件的目标房间，请调整筛选或先处理房态" :image-size="60" />
   <el-pagination v-if="total > 8" small layout="prev, pager, next" :total="total" :page-size="8" v-model:current-page="page" :disabled="locked" @current-change="load()" />
   <p v-if="target" class="change-selected">本次换房：{{ stay.roomNumber }} → {{ target.roomNumber }} · {{ target.roomTypeName }}</p>
   <div class="change-reason"><label>换房原因类型<select v-model="reasonType" aria-label="换房原因类型" :disabled="locked"><option v-for="(name, code) in changeReasonNames" :key="code" :value="code">{{ name }}</option></select></label><label v-if="reasonType === 'ROOM_ISSUE'">关联原房异常（选填）<select v-model="issueId" aria-label="关联原房异常" :disabled="locked"><option :value="undefined">不关联异常</option><option v-for="i in issues" :key="i.id" :value="i.id">#{{ i.id }} · {{ i.description }}</option></select></label><label>换房说明（必填）<el-input v-model="reason" type="textarea" aria-label="换房说明" maxlength="1000" :rows="3" show-word-limit :disabled="locked" placeholder="请填写换房说明，不要填写住客证件号码或电话" /></label></div>
  </template>
  <template #footer><div class="change-footer"><el-button v-if="pending || orphan" :disabled="busy" @click="queryResult">查询原换房结果</el-button><el-button v-if="pending || orphan" :disabled="busy" @click="acknowledge">核对后结束重试</el-button><el-button :disabled="busy" @click="visible = false">{{ result ? '关闭' : '暂时关闭' }}</el-button><el-button v-if="!result && !orphan" type="primary" :loading="busy" :disabled="!pending && (!target || !reason.trim())" @click="submit">{{ pending ? '重试原换房请求' : '确认办理换房' }}</el-button></div></template>
 </el-dialog>
</template>
<style scoped>
.change-summary { margin: 18px 0; }.change-filters { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)) auto; align-items: end; gap: 12px; margin: 14px 0; }
.change-filters label,.change-reason label { display: grid; gap: 7px; min-width: 0; }.change-filters select,.change-reason select { width: 100%; height: 34px; padding: 5px 8px; border: 1px solid #dcdfe6; border-radius: 5px; background: white; min-width: 0; }
.change-rooms { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 10px; }.change-rooms button { display: grid; text-align: left; gap: 6px; border: 1px solid #dcdfe6; border-radius: 8px; background: white; padding: 14px; color: #35463e; cursor: pointer; overflow-wrap: anywhere; min-height: 90px; }.change-rooms button.selected { border-color: #387a63; background: #edf7f1; outline: 1px solid #387a63; }.change-rooms button:disabled { cursor: default; opacity: .7; }.change-reason { display: grid; gap: 14px; margin-top: 18px; }.change-selected { padding: 12px; background: #edf7f1; border-radius: 6px; }.change-footer { display: flex; flex-wrap: wrap; justify-content: end; gap: 8px; }.change-footer .el-button { margin-left: 0; }.el-alert { overflow-wrap: anywhere; }
@media(max-width:600px) { .change-filters { grid-template-columns: 1fr 1fr; }.change-rooms { grid-template-columns: 1fr 1fr; }.change-footer { justify-content: stretch; }.change-footer .el-button { flex: 1 1 45%; min-height: 40px; } }
</style>
