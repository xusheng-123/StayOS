<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElDatePicker, ElDialog, ElMessageBox, ElSelect, ElOption } from 'element-plus'
import { ApiError } from '../api/client'
import { auth, can } from '../stores/auth'
import { createHandover, handoverCandidates, handoverReceipt, updateHandover } from '../api/handover'
import { handoverCategoryNames, handoverScopeNames, type HandoverCandidate, type HandoverDetail,
  type HandoverItemCategory, type HandoverScope } from '../types/handover'

const emit = defineEmits<{ completed: [detail: HandoverDetail] }>()
const visible = ref(false), busy = ref(false), error = ref(''), result = ref<HandoverDetail | null>(null)
const mode = ref<'create' | 'edit'>('create'), editing = ref<HandoverDetail | null>(null)
const scope = ref<HandoverScope>('FRONT_DESK'), receiverId = ref<number>(), start = ref(''), end = ref(''), summary = ref('')
const items = reactive<{ category: HandoverItemCategory; content: string; sourceId?: number }[]>([])
const candidates = ref<HandoverCandidate[]>([])
const pending = ref<{ key: string; body: string } | null>(null), orphan = ref('')
const locked = computed(() => busy.value || !!pending.value || !!orphan.value)
const storageKey = `stayos:handover-create-pending:${auth.user!.id}`
const scopes = computed(() => (['FRONT_DESK', 'HOUSEKEEPING'] as HandoverScope[]).filter(s => can(s === 'FRONT_DESK' ? 'handover:front-desk' : 'handover:housekeeping')))
let uncertain = false
function defaultTimes() {
  const from = new Date(Math.floor(Date.now() / 60000) * 60000), to = new Date(from.getTime() + 8 * 3600_000)
  const text = (d: Date) => d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).replaceAll('/', '-')
  start.value = text(from); end.value = text(to)
}
function resetForm() {
  receiverId.value = undefined; summary.value = ''; items.splice(0); defaultTimes()
}
function toIso(value: string) {
  // Input is always interpreted in the hotel's timezone, including on overseas browsers.
  return new Date(`${value.replace(' ', 'T')}+08:00`).toISOString()
}
function open(next: HandoverScope, existing?: HandoverDetail) {
  if (busy.value) return
  if (pending.value || orphan.value) { visible.value = true; return }
  error.value = ''; result.value = null; uncertain = false
  if (existing) {
    mode.value = 'edit'; editing.value = existing; scope.value = existing.scope
    receiverId.value = existing.receiverId; summary.value = existing.summary
    items.splice(0, items.length, ...existing.items.map(i => ({ category: i.category, content: i.content, sourceId: i.sourceId ?? undefined })))
    start.value = new Date(existing.shiftStartAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).replaceAll('/', '-')
    end.value = new Date(existing.shiftEndAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).replaceAll('/', '-')
  } else {
    mode.value = 'create'; editing.value = null; scope.value = next; resetForm()
  }
  visible.value = true
  void loadCandidates(scope.value)
}
defineExpose({ open })
async function loadCandidates(next: HandoverScope) {
  candidates.value = []
  try { candidates.value = (await handoverCandidates(next)).filter(c => c.id !== auth.user!.id) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '接班人列表加载失败' }
}
function switchScope(next: HandoverScope) {
  if (mode.value === 'edit' || locked.value) return
  scope.value = next; receiverId.value = undefined; void loadCandidates(next)
}
function addItem() { if (items.length < 50) items.push({ category: 'CUSTOM', content: '' }) }
function removeItem(index: number) { items.splice(index, 1) }
function validate(): string {
  if (!receiverId.value) return '请选择接班人'
  if (!start.value || !end.value) return '请填写班次起止时间'
  const from = Date.parse(toIso(start.value)), to = Date.parse(toIso(end.value))
  if (!Number.isFinite(from) || !Number.isFinite(to)) return '班次时间不完整，请重新选择'
  if (to <= from) return '班次结束时间必须晚于开始时间'
  if (items.some(i => !i.content.trim())) return '每条事项内容需填写完整，空事项请删除'
  return ''
}
function completed(detail: HandoverDetail) {
  if (!detail || !Number.isSafeInteger(detail.id) || detail.id < 1 || !Number.isSafeInteger(detail.version)
      || !Array.isArray(detail.items) || !Array.isArray(detail.events))
    throw new ApiError(200, 'INVALID_RESPONSE', '交班响应不完整，请查询或重试原操作')
  result.value = detail; forget(); resetForm(); emit('completed', detail)
}
function forget() { sessionStorage.removeItem(storageKey); pending.value = null; orphan.value = ''; uncertain = false }
async function submit() {
  if (busy.value || orphan.value || result.value) return
  error.value = ''
  if (mode.value === 'edit' && editing.value) {
    const problem = validate()
    if (problem) { error.value = problem; return }
    busy.value = true
    try {
      completed(await updateHandover(editing.value.id, {
        version: editing.value.version, receiverId: receiverId.value!, shiftStartAt: toIso(start.value), shiftEndAt: toIso(end.value),
        summary: summary.value.trim(), items: items.map(i => ({ category: i.category, content: i.content.trim(), sourceId: i.sourceId })),
      }))
    } catch (cause) { error.value = cause instanceof Error ? cause.message : '草稿保存失败，请稍后重试' }
    finally { busy.value = false }
    return
  }
  if (!pending.value) {
    const problem = validate()
    if (problem) { error.value = problem; return }
    const body = {
      scope: scope.value, receiverId: receiverId.value!, shiftStartAt: toIso(start.value), shiftEndAt: toIso(end.value),
      summary: summary.value.trim(), items: items.map(i => ({ category: i.category, content: i.content.trim(), sourceId: i.sourceId })),
    }
    const next = { key: crypto.randomUUID(), body: JSON.stringify(body) }
    try { sessionStorage.setItem(storageKey, next.key) }
    catch { error.value = '无法保存安全重试标识，请检查浏览器存储后重试'; return }
    pending.value = next
  }
  busy.value = true
  try {
    const request = pending.value
    completed(await createHandover(request.key, JSON.parse(request.body)))
  } catch (cause) {
    uncertain ||= !(cause instanceof ApiError) || cause.status === 0 || cause.status === 401 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE'
    error.value = (cause instanceof Error ? cause.message : '交班结果尚未确认')
      + (uncertain ? '。请查询或重试原请求，不要重复新建交班。' : '')
    if (!uncertain) { forget(); resetForm(); emit('completed', result.value!) }
  } finally { busy.value = false }
}
async function queryResult() {
  const key = pending.value?.key || orphan.value
  if (!key || busy.value) return
  busy.value = true; error.value = ''
  try { completed(await handoverReceipt(key, 'HANDOVER_CREATE')) }
  catch (cause) { error.value = (cause instanceof Error ? cause.message : '查询失败') + '。未查到结果不代表操作已失败，请核对交班列表。' }
  finally { busy.value = false }
}
async function acknowledge() {
  try { await ElMessageBox.confirm('请先在交班列表核对原交班记录。结束重试只清除本机标识，不撤销或重新提交。是否已完成核对？', '核对交班结果', {
    confirmButtonText: '已核对，结束重试', cancelButtonText: '继续保留',
  }) } catch { return }
  forget(); resetForm(); visible.value = false; emit('completed', result.value ?? ({} as HandoverDetail))
}
function closed() {
  if (pending.value) { orphan.value = pending.value.key; pending.value = null }
  resetForm(); editing.value = null
}
onMounted(() => {
  try {
    const key = sessionStorage.getItem(storageKey)
    if (key) { orphan.value = key; visible.value = true; error.value = '发现上次未确认的交班创建，请查询原交班结果。刷新后不会自动重新提交。' }
  } catch { error.value = '浏览器存储不可用，请检查后再办理交班' }
})
</script>

<template>
  <el-dialog v-model="visible" :title="result ? '交班创建成功' : mode === 'edit' ? '修改交班草稿' : '新建交班'" width="min(640px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="closed">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="result">
      <p>交班记录 #{{ result.id }} 已保存，当前状态：{{ result.status === 'DRAFT' ? '草稿' : result.status === 'SUBMITTED' ? '待接班确认' : '接班已确认' }}。</p>
      <p class="subtle">提交后摘要与事项将冻结；可在交班详情中提交、改派或确认接班。</p>
    </template>
    <template v-else-if="!orphan">
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="交班范围" required>
          <el-select v-if="mode === 'create' && scopes.length > 1" :model-value="scope" aria-label="交班范围" :disabled="locked" @update:model-value="switchScope">
            <el-option v-for="s in scopes" :key="s" :label="handoverScopeNames[s]" :value="s" />
          </el-select>
          <span v-else>{{ handoverScopeNames[scope] }}</span>
        </el-form-item>
        <el-form-item label="接班人" required>
          <el-select v-model="receiverId" aria-label="接班人" filterable placeholder="选择接班员工" :disabled="locked">
            <el-option v-for="c in candidates" :key="c.id" :value="c.id" :label="`${c.displayName} · ${c.username}`" />
          </el-select>
          <span class="field-help">接班人须启用且拥有该范围交班权限；不能把班交给自己。</span>
        </el-form-item>
        <el-form-item label="班次起止时间（北京时间）" required>
          <div class="handover-times">
            <el-date-picker v-model="start" type="datetime" aria-label="班次开始时间" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm:ss" placeholder="开始时间" :disabled="locked" />
            <span class="handover-time-sep">至</span>
            <el-date-picker v-model="end" type="datetime" aria-label="班次结束时间" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm:ss" placeholder="结束时间" :disabled="locked" />
          </div>
        </el-form-item>
        <el-form-item label="交班摘要"><el-input v-model="summary" aria-label="交班摘要" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="locked" /></el-form-item>
        <fieldset class="handover-items" :disabled="locked">
          <legend>交班事项（最多50条，按提交时快照冻结）</legend>
          <div v-for="(item, index) in items" :key="index" class="handover-item-row">
            <el-select v-model="item.category" aria-label="事项类别" class="handover-item-category">
              <el-option v-for="(name, key) in handoverCategoryNames" :key="key" :label="name" :value="key" />
            </el-select>
            <el-input v-model="item.content" aria-label="事项内容" maxlength="500" show-word-limit placeholder="1—500字，不要填写证件、电话等住客敏感资料" />
            <el-button :aria-label="`删除事项${index + 1}`" @click="removeItem(index)">删除</el-button>
          </div>
          <el-button :disabled="items.length >= 50" @click="addItem">添加事项</el-button>
          <p class="field-help">事项只保留类别、说明与来源编号，不复制住客证件、电话等资料；提交后不能修改。</p>
        </fieldset>
      </el-form>
    </template>
    <template #footer><div class="handover-actions">
      <el-button v-if="pending || orphan" :disabled="busy" @click="acknowledge">核对后结束重试</el-button>
      <el-button :disabled="busy" @click="visible = false">{{ result ? '关闭' : '暂时关闭' }}</el-button>
      <el-button v-if="(pending || orphan) && !result" :loading="busy" @click="queryResult">查询原交班结果</el-button>
      <el-button v-if="!orphan && !result" type="primary" :loading="busy" @click="submit">{{ mode === 'edit' ? '保存草稿' : pending ? '重试原交班请求' : '创建交班草稿' }}</el-button>
    </div></template>
  </el-dialog>
</template>

<style scoped>
.handover-times { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; width: 100%; }
.handover-times .el-date-editor { flex: 1 1 180px; min-width: 0; }
.handover-time-sep { color: #788278; }
.handover-items { border: 1px solid #dde7df; border-radius: 8px; padding: 14px; margin: 0 0 18px; display: grid; gap: 10px; }
.handover-items legend { font-size: 13px; padding: 0 6px; }
.handover-item-row { display: grid; grid-template-columns: 150px minmax(0, 1fr) auto; gap: 8px; align-items: start; }
.handover-item-row :deep(.el-input) { width: 100%; }
.handover-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.handover-actions .el-button { margin-left: 0; min-height: 44px; }
.el-alert, p, .handover-item-row { overflow-wrap: anywhere; }
@media (max-width: 650px) {
  .handover-item-row { grid-template-columns: minmax(0, 1fr); }
  .handover-item-category { width: 100%; }
}
</style>
