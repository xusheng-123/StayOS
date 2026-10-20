<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElDialog, ElDescriptions, ElDescriptionsItem, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import { localTime } from '../api/rooms'
import { auth } from '../stores/auth'
import type { Stay, CheckoutResult } from '../types/stay'

const emit = defineEmits<{ completed: [] }>()
const visible = ref(false), busy = ref(false), error = ref(''), selected = ref<Stay | null>(null)
const pending = ref<{ stayId: number; segmentId: number; key: string } | null>(null)
const result = ref<CheckoutResult | null>(null)
const invalidMarker = ref(false)
let uncertain = false
const storageKey = `stayos:check-out-pending:${auth.user!.id}`
function open(stay: Stay) {
  // A previous uncertain request must be resolved before choosing another checkout.
  if (pending.value || invalidMarker.value) { visible.value = true; return }
  selected.value = stay; result.value = null; error.value = ''; visible.value = true
}
defineExpose({ open })
async function acknowledge() {
  try { await ElMessageBox.confirm('请先在入住历史中核对该笔退房结果。结束重试只会清除本机标识，不会撤销或重新执行退房；如仍在住，请刷新后重新确认。是否已完成核对？', '核对退房结果', { confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留' }) }
  catch { return }
  sessionStorage.removeItem(storageKey); pending.value = null; invalidMarker.value = false; uncertain = false; selected.value = null; visible.value = false; emit('completed')
}
async function submit() {
  if (busy.value || result.value) return
  if (!pending.value) {
    if (!selected.value) return
    const request = { stayId: selected.value.id, segmentId: selected.value.segmentId, key: crypto.randomUUID() }
    try { sessionStorage.setItem(storageKey, JSON.stringify(request)) }
    catch { error.value = '无法保存安全重试标识，请检查浏览器存储后重试'; return }
    pending.value = request
  }
  busy.value = true; error.value = ''
  const request = pending.value
  try {
    const response = await api<CheckoutResult>(`/api/stays/${request.stayId}/check-out`, {
      method: 'POST', headers: { 'Idempotency-Key': request.key }, body: JSON.stringify({ segmentId: request.segmentId }),
    })
    if (!response || response.stayId !== request.stayId || response.segmentId !== request.segmentId || !Number.isSafeInteger(response.taskId) || response.taskId < 1 || !Number.isFinite(Date.parse(response.actualCheckOutAt)))
      throw new ApiError(200, 'INVALID_RESPONSE', '退房响应内容不完整，请重试原退房请求确认结果')
    result.value = response
    sessionStorage.removeItem(storageKey); pending.value = null; uncertain = false; emit('completed')
  } catch (cause) {
    const ambiguous = !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    uncertain ||= ambiguous
    error.value = cause instanceof Error ? cause.message : '退房结果尚未确认'
    // Only a definitely rejected FIRST attempt can abandon its marker automatically.
    if (!uncertain) { sessionStorage.removeItem(storageKey); pending.value = null; selected.value = null; emit('completed') }
  } finally { busy.value = false }
}
onMounted(async () => {
  const saved = sessionStorage.getItem(storageKey)
  if (!saved) return
  try {
    const p = JSON.parse(saved) as { stayId: number; segmentId: number; key: string }
    if (!Number.isSafeInteger(p.stayId) || p.stayId < 1 || !Number.isSafeInteger(p.segmentId) || p.segmentId < 1 || !/^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(p.key)) throw new Error('invalid marker')
    pending.value = p; uncertain = true; visible.value = true
    error.value = '检测到上次退房结果尚未确认，请重试原退房请求或先查看入住历史。'
    try { selected.value = await api<Stay>(`/api/stays/${p.stayId}`) } catch { /* Retry still uses the original IDs and key. */ }
  } catch { invalidMarker.value = true; error.value = '退房重试标识损坏，请先核对历史记录并联系管理员处理'; visible.value = true }
})
</script>
<template>
  <el-dialog v-model="visible" :title="result ? '退房成功' : '确认办理退房'" width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <template v-if="result">
      <el-alert title="退房成功，清洁任务创建成功" type="success" :closable="false" show-icon />
      <el-descriptions :column="1" border class="checkout-summary"><el-descriptions-item label="房间编号">{{ result.roomNumber }}</el-descriptions-item><el-descriptions-item label="实际退房时间">{{ localTime(result.actualCheckOutAt) }}</el-descriptions-item><el-descriptions-item label="退房后清洁状态">待打扫</el-descriptions-item><el-descriptions-item label="清洁任务">#{{ result.taskId }} · 已创建</el-descriptions-item></el-descriptions>
      <p class="subtle">房间需完成后续清洁流程后才能重新入住。</p>
      <router-link to="/stays/history" @click="visible = false"><el-button>查看历史入住</el-button></router-link>
      <router-link to="/rooms" @click="visible = false"><el-button>查看房态图</el-button></router-link>
    </template>
    <template v-else>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-descriptions v-if="selected" :column="1" border class="checkout-summary"><el-descriptions-item label="房号">{{ selected.roomNumber }}</el-descriptions-item><el-descriptions-item label="房型">{{ selected.roomTypeName }}</el-descriptions-item><el-descriptions-item label="主要联系人">{{ selected.primaryGuestName }}</el-descriptions-item><el-descriptions-item label="入住时间">{{ localTime(selected.checkInAt) }}</el-descriptions-item><el-descriptions-item label="预计退房时间">{{ localTime(selected.expectedCheckOutAt) }}</el-descriptions-item></el-descriptions>
      <p>确认后将结束这笔入住，房间变为待打扫，并自动创建退房清洁任务。</p>
      <p class="subtle">实际退房时间由服务器记录，本操作不涉及任何收费或结算。</p>
    </template>
    <template #footer><el-button v-if="pending || invalidMarker" :disabled="busy" @click="acknowledge">核对后结束重试</el-button><el-button :disabled="busy" @click="visible = false">{{ result ? '关闭' : '暂时关闭' }}</el-button><el-button v-if="!result" type="primary" :loading="busy" :disabled="!selected && !pending" @click="submit">{{ pending ? '重试原退房请求' : '确认退房' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>.checkout-summary { margin: 18px 0; } .el-alert { overflow-wrap: anywhere; }</style>
