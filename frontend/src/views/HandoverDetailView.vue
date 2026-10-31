<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElDialog, ElMessageBox, ElOption, ElSelect } from 'element-plus'
import { ApiError } from '../api/client'
import { auth } from '../stores/auth'
import { acknowledgeHandover, handoverCandidates, handoverDetail, handoverReceipt, reassignHandover, submitHandover } from '../api/handover'
import { localTime } from '../api/rooms'
import HandoverFormDialog from '../components/HandoverFormDialog.vue'
import { handoverCategoryNames, handoverEventNames, handoverScopeNames, handoverStatusNames,
  type HandoverCandidate, type HandoverDetail, type HandoverOperation } from '../types/handover'

const route = useRoute()
const detail = ref<HandoverDetail>(), loading = ref(false), error = ref(''), success = ref(''), busy = ref(false)
const editDialog = ref<InstanceType<typeof HandoverFormDialog>>()
const reassignVisible = ref(false), reassignError = ref(''), reassignReceiver = ref<number>(), reassignReason = ref('')
const candidates = ref<HandoverCandidate[]>()
const pending = ref<{ key: string; op: HandoverOperation } | null>(null), recovered = ref<{ key: string; op: HandoverOperation } | null>(null)
const markerName = `stayos:handover-action-pending:${auth.user!.id}`
const printedAt = ref('')
const id = computed(() => Number(route.params.id))
const isOwner = computed(() => detail.value?.handoverById === auth.user?.id)
const isReceiver = computed(() => detail.value?.receiverId === auth.user?.id)
const statusTag = computed(() => detail.value?.status === 'DRAFT' ? 'info' : detail.value?.status === 'SUBMITTED' ? 'warning' : 'success')
function message(e: unknown) { return e instanceof Error ? e.message : '操作失败，请稍后重试' }
function clearMarker() { sessionStorage.removeItem(markerName); pending.value = null; recovered.value = null }
function restoreMarker() {
  pending.value = null; recovered.value = null
  try {
    const saved = sessionStorage.getItem(markerName)
    if (!saved) return
    const marker = JSON.parse(saved) as { key?: unknown; op?: unknown; handoverId?: unknown }
    if (marker.handoverId !== id.value || typeof marker.key !== 'string' || (marker.op !== 'HANDOVER_SUBMIT' && marker.op !== 'HANDOVER_ACKNOWLEDGE')) return
    recovered.value = { key: marker.key, op: marker.op }
  } catch { recovered.value = null }
}
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try { const data = await handoverDetail(id.value); if (current === generation) { detail.value = data; restoreMarker() } }
  catch (cause) { if (current === generation) { detail.value = undefined; error.value = message(cause) } }
  finally { if (current === generation) loading.value = false }
}
watch(id, () => { if (Number.isSafeInteger(id.value) && id.value > 0) { restoreMarker(); void load() } })
onMounted(() => { if (Number.isSafeInteger(id.value) && id.value > 0) { restoreMarker(); void load() } })

async function runAction(op: 'HANDOVER_SUBMIT' | 'HANDOVER_ACKNOWLEDGE') {
  if (!detail.value || busy.value) return
  const version = detail.value.version
  const submit = op === 'HANDOVER_SUBMIT'
  try {
    await ElMessageBox.confirm(submit
      ? '提交后交班摘要与事项将按提交时快照冻结，不能再修改；接班人确认后记录归档。是否提交？'
      : '确认接班表示你已阅读并接收全部交班内容，确认后本记录不可再修改。是否确认接班？',
      submit ? '提交交接' : '确认接班', { confirmButtonText: submit ? '确认提交' : '确认接班', cancelButtonText: '再核对一下', type: 'warning' })
  } catch { return }
  const key = crypto.randomUUID()
  try { sessionStorage.setItem(markerName, JSON.stringify({ key, op, handoverId: id.value })); pending.value = { key, op } }
  catch { error.value = '浏览器无法保存安全重试标识，请检查浏览器存储设置后重试'; return }
  success.value = ''
  await perform(op, key, version)
}
async function perform(op: HandoverOperation, key: string, version: number) {
  busy.value = true; error.value = ''
  try {
    detail.value = op === 'HANDOVER_SUBMIT' ? await submitHandover(id.value, key, version) : await acknowledgeHandover(id.value, key, version)
    clearMarker()
    success.value = op === 'HANDOVER_SUBMIT' ? '交班已提交，摘要与事项已按快照冻结' : '接班已确认，本记录已归档'
  } catch (cause) {
    const uncertain = !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    error.value = message(cause) + (uncertain ? '。结果尚未确认，请查询原操作结果，不要重复提交。' : '')
    if (!uncertain) clearMarker()
  } finally { busy.value = false }
}
async function queryResult() {
  const marker = pending.value || recovered.value
  if (!marker || busy.value) return
  busy.value = true; error.value = ''
  try {
    const data = await handoverReceipt(marker.key, marker.op)
    if (data.id !== id.value) throw new ApiError(200, 'INVALID_RESPONSE', '查询结果与当前交班不一致，请核对交班列表')
    detail.value = data; clearMarker(); success.value = '已确认原操作结果'
  } catch (cause) { error.value = message(cause) + '。未查到结果不代表操作已失败，请核对交班状态。' }
  finally { busy.value = false }
}
async function endRecovery() {
  try { await ElMessageBox.confirm('请先核对交班当前状态。结束重试只清除本机标识，不撤销或重新提交原操作。是否已完成核对？', '核对操作结果', {
    confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留' }) } catch { return }
  clearMarker()
}
function editDraft() { if (detail.value) editDialog.value?.open(detail.value.scope, detail.value) }
async function openReassign() {
  if (!detail.value) return
  reassignError.value = ''; reassignReceiver.value = undefined; reassignReason.value = ''
  try { candidates.value = (await handoverCandidates(detail.value.scope)).filter(c => c.id !== auth.user!.id && c.id !== detail.value!.receiverId) }
  catch (cause) { error.value = message(cause); return }
  reassignVisible.value = true
}
async function confirmReassign() {
  if (!detail.value || busy.value) return
  if (!reassignReceiver.value || !reassignReason.value.trim()) { reassignError.value = '请选择新的接班人并填写改派原因（1—1000字）'; return }
  busy.value = true; reassignError.value = ''
  try {
    detail.value = await reassignHandover(id.value, { version: detail.value.version, receiverId: reassignReceiver.value, reason: reassignReason.value.trim() })
    reassignVisible.value = false; success.value = '已改派接班人，原接班人不再看到确认入口'
  } catch (cause) { reassignError.value = message(cause) } finally { busy.value = false }
}
async function printSheet() {
  if (busy.value || !detail.value) return
  busy.value = true; error.value = ''
  try {
    // 打印前重新向服务器校验访问权限与最新快照。
    detail.value = await handoverDetail(id.value)
    printedAt.value = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
    await nextTick()
    window.print()
  } catch (cause) { error.value = message(cause) } finally { busy.value = false }
}
function edited(updated: HandoverDetail) { if (updated?.id === id.value) detail.value = updated; success.value = '草稿已保存' }
</script>

<template>
 <section class="rooms-page handover-detail-page">
  <div class="page-heading"><div><p class="eyebrow muted">SHIFT HANDOVER</p><h1>交班详情 <span class="subtle">#{{ id }}</span></h1>
   <p class="subtle">草稿可修改；提交后按快照冻结；只有接班人本人可以确认接班。</p></div>
   <div class="handover-heading-actions">
    <el-button @click="printSheet" :loading="busy">打印交接单</el-button>
    <template v-if="detail">
     <el-button v-if="isOwner && detail.status === 'DRAFT'" :disabled="busy" @click="editDraft">编辑草稿</el-button>
     <el-button v-if="isOwner && detail.status === 'DRAFT'" type="primary" :loading="busy" @click="runAction('HANDOVER_SUBMIT')">提交交接</el-button>
     <el-button v-if="isOwner && detail.status === 'SUBMITTED'" :disabled="busy" @click="openReassign">改派接班人</el-button>
     <el-button v-if="isReceiver && detail.status === 'SUBMITTED'" type="primary" :loading="busy" @click="runAction('HANDOVER_ACKNOWLEDGE')">确认接班</el-button>
    </template>
   </div></div>
  <el-alert v-if="pending || recovered" type="warning" :closable="false" class="stay-alert"
    title="有待核对的交接或确认操作。结果尚未确认前请不要重复提交。" />
  <div v-if="pending || recovered" class="handover-recovery catalog-panel"><p>原操作结果未确认。{{ recovered ? '本机保留原请求标识。' : '' }}</p>
   <div><el-button :loading="busy" @click="queryResult">查询原操作结果</el-button><el-button :disabled="busy" @click="endRecovery">核对后结束重试</el-button></div></div>
  <el-alert v-if="success" :title="success" type="success" :closable="false" class="stay-alert" />
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <div v-loading="loading" class="handover-body">
   <template v-if="detail">
    <div class="catalog-panel handover-meta">
     <div class="table-caption"><h2>基本信息</h2><el-tag :type="statusTag">{{ handoverStatusNames[detail.status] }}</el-tag></div>
     <dl><dt>范围</dt><dd>{{ handoverScopeNames[detail.scope] }}</dd>
      <dt>交班人</dt><dd>{{ detail.handoverByName }}</dd>
      <dt>接班人</dt><dd>{{ detail.receiverName }}</dd>
      <dt>班次</dt><dd>{{ localTime(detail.shiftStartAt) }} 至 {{ localTime(detail.shiftEndAt) }}</dd>
      <dt>版本</dt><dd>{{ detail.version }}</dd>
      <dt>创建时间</dt><dd>{{ localTime(detail.createdAt) }}</dd>
      <dt>提交时间</dt><dd>{{ detail.submittedAt ? localTime(detail.submittedAt) : '—' }}</dd>
      <dt>确认时间</dt><dd>{{ detail.acknowledgedAt ? localTime(detail.acknowledgedAt) : '—' }}</dd></dl>
     <h3>交班摘要</h3><p class="handover-summary-text">{{ detail.summary || '（无摘要）' }}</p>
    </div>
    <div class="catalog-panel handover-items-panel">
     <div class="table-caption"><h2>交班事项（{{ detail.items.length }} 条）</h2><span class="subtle">按提交时快照冻结</span></div>
     <ol class="handover-item-list"><li v-for="item in detail.items" :key="item.id">
      <div><el-tag size="small">{{ handoverCategoryNames[item.category] }}</el-tag><span v-if="item.sourceId" class="subtle">来源 #{{ item.sourceId }}</span></div>
      <p>{{ item.content }}</p></li></ol>
     <p v-if="!detail.items.length" class="subtle">本班没有登记事项。</p>
     <p class="subtle">事项只保留类别、说明与来源编号，不含住客证件、电话等敏感资料。</p>
    </div>
    <div class="catalog-panel handover-events-panel">
     <div class="table-caption"><h2>流转记录</h2><span class="subtle">创建、修改、提交、改派与确认全程留痕</span></div>
     <ol class="handover-event-list"><li v-for="event in detail.events" :key="event.id">
      <strong>{{ handoverEventNames[event.eventType] }}</strong>
      <span class="subtle">{{ event.actorName }} · {{ localTime(event.createdAt) }}</span>
      <p v-if="event.reason" class="handover-event-reason">{{ event.reason }}</p></li></ol>
    </div>
   </template>
  </div>
  <HandoverFormDialog ref="editDialog" @completed="edited" />
  <el-dialog v-model="reassignVisible" title="改派接班人" width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
   <el-alert v-if="reassignError" :title="reassignError" type="error" :closable="false" show-icon />
   <el-form label-position="top" @submit.prevent="confirmReassign">
    <el-form-item label="新的接班人" required><el-select v-model="reassignReceiver" aria-label="新的接班人" filterable placeholder="选择接班员工" :disabled="busy">
     <el-option v-for="c in candidates" :key="c.id" :value="c.id" :label="`${c.displayName} · ${c.username}`" /></el-select>
     <span class="field-help">新接班人须启用且拥有该范围交班权限；已确认的交班不能改派。</span></el-form-item>
    <el-form-item label="改派原因" required><el-input v-model="reassignReason" aria-label="改派原因" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="busy" /></el-form-item>
   </el-form>
   <template #footer><div class="handover-actions">
    <el-button :disabled="busy" @click="reassignVisible = false">取消</el-button>
    <el-button type="primary" :loading="busy" @click="confirmReassign">确认改派</el-button></div></template>
  </el-dialog>
 </section>
 <Teleport to="body">
  <div v-if="detail" class="handover-print" aria-hidden="true">
   <h1>交接班记录</h1>
   <p class="handover-print-sub">{{ handoverScopeNames[detail.scope] }}范围 · 编号 #{{ detail.id }} · {{ handoverStatusNames[detail.status] }} · 版本 {{ detail.version }}</p>
   <table class="handover-print-meta"><tbody>
    <tr><th>交班人</th><td>{{ detail.handoverByName }}</td><th>接班人</th><td>{{ detail.receiverName }}</td></tr>
    <tr><th>班次开始</th><td>{{ localTime(detail.shiftStartAt) }}</td><th>班次结束</th><td>{{ localTime(detail.shiftEndAt) }}</td></tr>
    <tr><th>创建时间</th><td>{{ localTime(detail.createdAt) }}</td><th>提交时间</th><td>{{ detail.submittedAt ? localTime(detail.submittedAt) : '—' }}</td></tr>
    <tr><th>确认时间</th><td>{{ detail.acknowledgedAt ? localTime(detail.acknowledgedAt) : '—' }}</td><th>打印时间</th><td>{{ printedAt }}</td></tr>
   </tbody></table>
   <h2>交班摘要</h2><p class="handover-print-summary">{{ detail.summary || '（无摘要）' }}</p>
   <h2>交班事项（{{ detail.items.length }} 条）</h2>
   <table class="handover-print-items"><thead><tr><th class="handover-print-no">#</th><th>类别</th><th>内容</th></tr></thead>
    <tbody><tr v-for="item in detail.items" :key="item.id"><td>{{ item.position + 1 }}</td><td>{{ handoverCategoryNames[item.category] }}</td><td>{{ item.content }}</td></tr></tbody></table>
   <p v-if="!detail.items.length">本班没有登记事项。</p>
   <h2>流转记录</h2>
   <ul class="handover-print-events"><li v-for="event in detail.events" :key="event.id">{{ localTime(event.createdAt) }} · {{ event.actorName }} · {{ handoverEventNames[event.eventType] }}{{ event.reason ? ` · ${event.reason}` : '' }}</li></ul>
   <p class="handover-print-footnote">本记录不含住客证件、电话等敏感资料；打印件仅为协同备忘，以系统记录为准。</p>
  </div>
 </Teleport>
</template>
<style scoped>
.handover-detail-page { min-width: 0; }
.handover-heading-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.handover-recovery { margin-bottom: 20px; display: grid; gap: 12px; }
.handover-recovery p { margin: 0; }
.handover-meta dl { display: grid; grid-template-columns: max-content minmax(0, 1fr) max-content minmax(0, 1fr); gap: 10px 16px; margin: 16px 0; }
.handover-meta dt { color: #788278; font-size: 13px; }
.handover-meta dd { margin: 0; overflow-wrap: anywhere; }
.handover-meta h3 { margin-top: 18px; }
.handover-summary-text { white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px 0 0; }
.handover-body { display: grid; gap: 20px; }
.handover-item-list { padding-left: 0; list-style: none; display: grid; gap: 14px; margin: 16px 0; }
.handover-item-list p { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.handover-item-list .subtle { margin-left: 8px; }
.handover-event-list { padding-left: 22px; display: grid; gap: 10px; margin: 16px 0; }
.handover-event-list strong { display: block; }
.handover-event-list .subtle { display: block; margin-top: 4px; }
.handover-event-reason { margin: 6px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.handover-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.handover-actions .el-button { margin-left: 0; min-height: 44px; }
@media (max-width: 700px) {
 .handover-meta dl { grid-template-columns: max-content minmax(0, 1fr); }
 .handover-heading-actions { width: 100%; }
}
</style>
<style>
/* 打印视图：Teleport 到 body，打印时隐藏整个应用，仅输出交接单。 */
.handover-print { display: none; }
@media print {
  #app { display: none !important; }
  .handover-print { display: block !important; background: #fff; color: #000; font-size: 12px; line-height: 1.6; padding: 8mm; }
  .handover-print h1 { font-size: 18px; margin: 0 0 4px; }
  .handover-print h2 { font-size: 14px; margin: 14px 0 6px; border-bottom: 1px solid #000; padding-bottom: 2px; }
  .handover-print-sub { margin: 0 0 10px; color: #444; }
  .handover-print-meta, .handover-print-items { width: 100%; border-collapse: collapse; margin: 8px 0; }
  .handover-print-meta th, .handover-print-meta td, .handover-print-items th, .handover-print-items td { border: 1px solid #666; padding: 4px 8px; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
  .handover-print-meta th, .handover-print-items th { background: #f2f2f2; font-weight: 600; white-space: nowrap; }
  .handover-print-no { width: 2.5em; }
  .handover-print-summary { white-space: pre-wrap; overflow-wrap: anywhere; margin: 4px 0; }
  .handover-print-events { padding-left: 1.4em; margin: 4px 0; }
  .handover-print-footnote { margin-top: 16px; color: #444; border-top: 1px solid #666; padding-top: 6px; }
}
</style>
