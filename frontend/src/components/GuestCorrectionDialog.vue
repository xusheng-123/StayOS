<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElCheckbox, ElDialog, ElMessageBox, ElSelect, ElOption } from 'element-plus'
import { api, ApiError } from '../api/client'
import { auth } from '../stores/auth'
import type { Stay, StayGuest, GuestCorrection, GuestCorrectionInput } from '../types/stay'

const emit = defineEmits<{ completed: [] }>()
const visible = ref(false), busy = ref(false), error = ref(''), result = ref<GuestCorrection | null>(null)
const selected = ref<{ stayId: number; guest: StayGuest } | null>(null), orphan = ref('')
const fields = reactive({ name: false, document: false, phone: false })
const input = reactive({ name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', reason: '' })
const pending = ref<{ key: string; stayId: number; guestId: number; body: GuestCorrectionInput } | null>(null)
const locked = computed(() => busy.value || !!pending.value || !!orphan.value)
const storageKey = `stayos:guest-correction-pending:${auth.user!.id}`
let uncertain = false
function clearInput() {
  Object.assign(input, { name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', reason: '' })
  Object.assign(fields, { name: false, document: false, phone: false })
}
function open(stay: Stay, guest: StayGuest) {
  if (busy.value) return
  if (!pending.value && !orphan.value) { clearInput(); selected.value = { stayId: stay.id, guest }; error.value = ''; result.value = null }
  visible.value = true
}
defineExpose({ open })
function forget() { sessionStorage.removeItem(storageKey); pending.value = null; orphan.value = ''; uncertain = false }
function completed(receipt: GuestCorrection) {
  if (!receipt || !Number.isSafeInteger(receipt.id) || receipt.id < 1 || !Number.isSafeInteger(receipt.guestId)
      || !Number.isSafeInteger(receipt.stayRecordId) || !Number.isSafeInteger(receipt.guestVersion) || receipt.guestVersion < 1
      || !Array.isArray(receipt.changedFields) || !receipt.changedFields.length || !receipt.before || !receipt.after
      || (pending.value && (receipt.stayRecordId !== pending.value.stayId || receipt.guestId !== pending.value.guestId
        || receipt.guestVersion !== pending.value.body.version + 1)))
    throw new ApiError(200, 'INVALID_RESPONSE', '更正响应不完整，请查询或重试原操作')
  result.value = receipt; forget(); clearInput(); emit('completed')
}
async function submit() {
  if (busy.value || orphan.value || result.value) return
  error.value = ''
  if (!pending.value) {
    if (!selected.value || !input.reason.trim()) { error.value = '请填写更正原因'; return }
    const changes: GuestCorrectionInput['changes'] = {}
    if (fields.name) { if (!input.name.trim()) { error.value = '请填写更正后的姓名'; return }; changes.name = input.name.trim() }
    if (fields.document) {
      if (!input.documentNumber.trim()) { error.value = '请重新填写完整证件号码'; return }
      changes.documentType = input.documentType; changes.documentNumber = input.documentNumber.trim()
    }
    if (fields.phone) changes.phone = input.phone.trim()
    if (!Object.keys(changes).length) { error.value = '请选择需要更正的字段'; return }
    const next = { key: crypto.randomUUID(), stayId: selected.value.stayId, guestId: selected.value.guest.id,
      body: { version: selected.value.guest.version, changes, reason: input.reason.trim() } }
    try { sessionStorage.setItem(storageKey, next.key) }
    catch { error.value = '无法保存安全重试标识，请检查浏览器存储后重试'; return }
    pending.value = next
  }
  busy.value = true
  try {
    const request = pending.value
    completed(await api<GuestCorrection>(`/api/stays/${request.stayId}/guests/${request.guestId}/corrections`, {
      method: 'POST', headers: { 'Idempotency-Key': request.key }, body: JSON.stringify(request.body),
    }))
  } catch (cause) {
    uncertain ||= !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    error.value = (cause instanceof Error ? cause.message : '更正结果尚未确认')
      + (uncertain ? '。请查询或重试原请求，不要直接再次更正。' : '。请刷新资料后重新核对。')
    if (!uncertain) { forget(); clearInput(); selected.value = null; emit('completed') }
  } finally { busy.value = false }
}
async function queryResult() {
  const key = pending.value?.key || orphan.value
  if (!key || busy.value) return
  busy.value = true; error.value = ''
  try { completed(await api<GuestCorrection>(`/api/stays/guest-corrections/result/${encodeURIComponent(key)}`)) }
  catch (cause) { error.value = (cause instanceof Error ? cause.message : '查询失败') + '。未查到结果不代表操作已失败，请核对更正历史。' }
  finally { busy.value = false }
}
async function acknowledge() {
  try { await ElMessageBox.confirm('请先刷新入住详情并核对住客更正历史。结束重试只清除本机标识，不撤销或重新提交。是否已完成核对？', '核对更正结果', {
    confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留',
  }) } catch { return }
  forget(); clearInput(); selected.value = null; visible.value = false; emit('completed')
}
function closed() {
  // Closing removes sensitive payload from memory. Only the receipt key survives.
  if (pending.value) { orphan.value = pending.value.key; pending.value = null }
  clearInput(); selected.value = null
}
onMounted(() => {
  try { const key = sessionStorage.getItem(storageKey); if (key) { orphan.value = key; visible.value = true; error.value = '发现上次未确认的更正，请查询原更正结果。刷新后不会自动重新提交。' } }
  catch { error.value = '浏览器存储不可用，请检查后再办理更正' }
})
</script>

<template>
  <el-dialog v-model="visible" :title="result ? '住客资料更正成功' : '更正住客资料'" width="min(600px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="closed">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="result"><p>更正记录 #{{ result.id }} 已保存，住客资料已更新。</p><p class="subtle">可在对应住客下查看脱敏更正历史。</p></template>
    <template v-else-if="selected && !orphan">
      <p>当前登记：{{ selected.guest.name }} · {{ selected.guest.documentNumber }}</p>
      <p class="subtle">仅用于本次登记笔误更正，请先核验原住客身份。不能用此功能替换住客。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-checkbox v-model="fields.name" :disabled="locked">更正姓名</el-checkbox>
        <el-form-item v-if="fields.name" label="更正后的姓名" required><el-input v-model="input.name" aria-label="更正后的姓名" maxlength="100" :disabled="locked" autocomplete="off" /></el-form-item>
        <el-checkbox v-model="fields.document" :disabled="locked">更正证件</el-checkbox>
        <template v-if="fields.document">
          <el-form-item label="更正后的证件类型" required><el-select v-model="input.documentType" aria-label="更正后的证件类型" :disabled="locked"><el-option label="居民身份证" value="ID_CARD" /><el-option label="护照" value="PASSPORT" /><el-option label="其他证件" value="OTHER" /></el-select></el-form-item>
          <el-form-item label="更正后的完整证件号码" required><el-input v-model="input.documentNumber" aria-label="更正后的完整证件号码" maxlength="64" :disabled="locked" autocomplete="off" /></el-form-item>
        </template>
        <el-checkbox v-model="fields.phone" :disabled="locked">更正联系电话</el-checkbox>
        <el-form-item v-if="fields.phone" label="更正后的联系电话"><el-input v-model="input.phone" aria-label="更正后的联系电话" maxlength="30" :disabled="locked" autocomplete="off" /><span class="field-help">留空将清空原电话；未勾选则保留原电话。</span></el-form-item>
        <el-form-item label="更正原因" required><el-input v-model="input.reason" aria-label="更正原因" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="locked" /><span class="field-help">只描述笔误原因，不要填写证件、电话等敏感资料。</span></el-form-item>
      </el-form>
    </template>
    <template #footer><div class="correction-actions">
      <el-button v-if="pending || orphan" :disabled="busy" @click="acknowledge">核对后结束重试</el-button>
      <el-button :disabled="busy" @click="visible = false">{{ result ? '关闭' : '暂时关闭' }}</el-button>
      <el-button v-if="(pending || orphan) && !result" :loading="busy" @click="queryResult">查询原更正结果</el-button>
      <el-button v-if="selected && !orphan && !result" type="primary" :loading="busy" @click="submit">{{ pending ? '重试原更正请求' : '保存更正' }}</el-button>
    </div></template>
  </el-dialog>
</template>
<style scoped>
.el-checkbox { display: flex; min-height: 44px; }
.el-select { width: 100%; }
.correction-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.correction-actions .el-button { margin-left: 0; min-height: 44px; }
.el-alert, p { overflow-wrap: anywhere; }
</style>
