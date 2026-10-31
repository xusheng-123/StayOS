<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElDatePicker, ElDescriptions, ElDescriptionsItem, ElDialog, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import { localTime } from '../api/rooms'
import { auth } from '../stores/auth'
import { refreshOperations } from '../stores/operations'
import type { Stay, StayExtension, StayExtensionInput } from '../types/stay'

const emit = defineEmits<{ completed: [] }>()
const visible = ref(false), busy = ref(false), error = ref('')
const selected = ref<Stay | null>(null), departure = ref(''), reason = ref('')
const result = ref<StayExtension | null>(null), orphan = ref('')
const pending = ref<{ key: string; stayId: number; body: StayExtensionInput } | null>(null)
const locked = computed(() => busy.value || !!pending.value || !!orphan.value)
const storageKey = `stayos:extension-pending:${auth.user!.id}`
let uncertain = false

function open(stay: Stay) {
  if (busy.value) return
  if (pending.value || orphan.value) { visible.value = true; return }
  selected.value = stay; error.value = ''; result.value = null; reason.value = ''
  departure.value = ''; visible.value = true
}
defineExpose({ open })
function forget() {
  sessionStorage.removeItem(storageKey); pending.value = null; orphan.value = ''; uncertain = false
}
function verify(receipt: StayExtension) {
  if (!receipt || !Number.isSafeInteger(receipt.id) || receipt.id < 1 || !Number.isSafeInteger(receipt.stayRecordId)
      || !Number.isSafeInteger(receipt.stayVersion) || receipt.stayVersion < 1
      || !Number.isFinite(Date.parse(receipt.oldExpectedCheckOutAt)) || !Number.isFinite(Date.parse(receipt.newExpectedCheckOutAt))
      || !Number.isFinite(Date.parse(receipt.operatedAt)))
    throw new ApiError(200, 'INVALID_RESPONSE', '续住响应不完整，请查询或重试原操作')
  if (pending.value && (receipt.stayRecordId !== pending.value.stayId || receipt.segmentId !== pending.value.body.segmentId
      || Date.parse(receipt.newExpectedCheckOutAt) !== Date.parse(pending.value.body.newExpectedCheckOutAt)
      || receipt.stayVersion !== pending.value.body.version + 1))
    throw new ApiError(200, 'INVALID_RESPONSE', '续住响应与原请求不匹配，请保留原标识核查')
}
function completed(receipt: StayExtension) {
  verify(receipt); result.value = receipt; forget(); reason.value = ''; departure.value = ''
  emit('completed'); void refreshOperations()
}
async function submit() {
  if (busy.value || orphan.value || result.value) return
  error.value = ''
  if (!pending.value) {
    if (!selected.value || !departure.value || !reason.value.trim()) { error.value = '请选择新的预计退房时间并填写调整原因'; return }
    // Input is always interpreted in the hotel's timezone, including on overseas browsers.
    const time = new Date(`${departure.value.replace(' ', 'T')}+08:00`)
    if (!Number.isFinite(time.getTime()) || time.getTime() <= Date.now()) { error.value = '新的预计退房时间须晚于当前时间，最终以服务器校验为准'; return }
    if (Math.floor(time.getTime() / 1000) === Math.floor(Date.parse(selected.value.expectedCheckOutAt) / 1000)) { error.value = '新的预计退房时间与原时间相同，无需提交'; return }
    const next = { key: crypto.randomUUID(), stayId: selected.value.id, body: {
      segmentId: selected.value.segmentId, version: selected.value.version,
      newExpectedCheckOutAt: time.toISOString(), reason: reason.value.trim(),
    } }
    try { sessionStorage.setItem(storageKey, next.key) }
    catch { error.value = '无法保存安全重试标识，请检查浏览器存储后重试'; return }
    pending.value = next
  }
  busy.value = true
  try {
    const request = pending.value
    completed(await api<StayExtension>(`/api/stays/${request.stayId}/extensions`, {
      method: 'POST', headers: { 'Idempotency-Key': request.key }, body: JSON.stringify(request.body),
    }))
  } catch (cause) {
    uncertain ||= !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    error.value = (cause instanceof Error ? cause.message : '续住结果尚未确认')
      + (uncertain ? '。请重试原请求或查询原续住结果，不要直接再次办理。' : '')
    if (!uncertain) { forget(); selected.value = null; reason.value = ''; departure.value = ''; emit('completed') }
  } finally { busy.value = false }
}
async function queryResult() {
  const key = pending.value?.key || orphan.value
  if (!key || busy.value) return
  busy.value = true; error.value = ''
  try { completed(await api<StayExtension>(`/api/stays/extensions/result/${encodeURIComponent(key)}`)) }
  catch (cause) { error.value = (cause instanceof Error ? cause.message : '查询失败') + '。未查到结果不代表原操作已失败，请核对入住和续住历史。' }
  finally { busy.value = false }
}
async function acknowledge() {
  try {
    await ElMessageBox.confirm('请先刷新入住详情并核对续住历史。结束重试只清除本机标识，不撤销或重新执行续住。是否已完成核对？', '核对续住结果', {
      confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留',
    })
  } catch { return }
  forget(); selected.value = null; reason.value = ''; departure.value = ''; visible.value = false; emit('completed')
}
function closed() { if (!pending.value) { selected.value = null; reason.value = ''; departure.value = '' } }
onMounted(() => {
  const key = sessionStorage.getItem(storageKey)
  if (key) { orphan.value = key; visible.value = true; error.value = '发现上次尚未确认的续住操作，请查询原续住结果。刷新后不会自动重新提交。' }
})
</script>

<template>
  <el-dialog v-model="visible" :title="result ? '续住办理成功' : '办理续住与预计离店调整'" width="min(620px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="closed">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="result">
      <el-alert title="预计退房时间已更新" type="success" :closable="false" show-icon />
      <el-descriptions :column="1" border class="extension-summary">
        <el-descriptions-item label="入住记录">#{{ result.stayRecordId }} · {{ result.roomNumber }}</el-descriptions-item>
        <el-descriptions-item label="原预计退房">{{ localTime(result.oldExpectedCheckOutAt) }}</el-descriptions-item>
        <el-descriptions-item label="新预计退房">{{ localTime(result.newExpectedCheckOutAt) }}</el-descriptions-item>
        <el-descriptions-item label="办理时间">{{ localTime(result.operatedAt) }}</el-descriptions-item>
        <el-descriptions-item label="办理员工">{{ result.operatorName }}</el-descriptions-item>
      </el-descriptions>
      <p class="subtle">本次只调整离店计划，继续使用原入住和房间，不涉及收费。</p>
    </template>
    <template v-else-if="selected && !orphan">
      <el-descriptions :column="1" border class="extension-summary">
        <el-descriptions-item label="当前房间">{{ selected.roomNumber }} · {{ selected.roomTypeName }}</el-descriptions-item>
        <el-descriptions-item label="原预计退房">{{ localTime(selected.expectedCheckOutAt) }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="新的预计退房时间（北京时间）" required>
          <el-date-picker v-model="departure" type="datetime" aria-label="新的预计退房时间（北京时间）"
            value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm:ss" placeholder="选择新的预计退房时间" :disabled="locked" />
        </el-form-item>
        <el-form-item label="调整原因" required>
          <el-input v-model="reason" type="textarea" aria-label="调整原因" :rows="3" maxlength="1000" show-word-limit :disabled="locked" />
          <span class="field-help">可延长或缩短预计时间；不要填写证件、电话等敏感资料。</span>
        </el-form-item>
      </el-form>
      <p class="subtle">只修改离店计划，不会退房、换房、创建清洁任务或产生费用。</p>
    </template>
    <template #footer>
      <div class="extension-actions">
        <el-button v-if="pending || orphan" :disabled="busy" @click="acknowledge">核对后结束重试</el-button>
        <el-button :disabled="busy" @click="visible = false">{{ result ? '关闭' : '暂时关闭' }}</el-button>
        <el-button v-if="(pending || orphan) && !result" :loading="busy" @click="queryResult">查询原续住结果</el-button>
        <el-button v-if="!orphan && !result && selected" type="primary" :loading="busy" :disabled="!pending && (!departure || !reason.trim())" @click="submit">
          {{ pending ? '重试原续住请求' : '确认续住' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.extension-summary { margin: 18px 0; }
.extension-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.extension-actions .el-button { margin-left: 0; min-height: 40px; }
.el-date-editor { width: 100%; max-width: 100%; }
.el-alert { overflow-wrap: anywhere; }
@media (max-width: 650px) { .extension-actions .el-button { min-height: 44px; } }
</style>
