<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElSelect, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag } from 'element-plus'
import { localTime } from '../api/rooms'
import { api } from '../api/client'
import type { PageResult, RoomType } from '../types/room'
import type { RatePlanView } from '../types/billing'
import {
  addChannelMapping, channelInbound, channelList, channelOutbox, channelProcessNow, channelReconcile,
  channelReconciliations, createChannel, deleteChannelMapping, retryChannelInbound, retryChannelOutbox, updateChannel,
} from '../api/channel'
import type {
  ChannelConnection, ChannelEventPage, ChannelInboundEvent, ChannelMapping, ChannelOutboxEvent, ChannelReconcileRun,
} from '../types/channel'

const statusNames: Record<string, string> = { ACTIVE: '启用', PAUSED: '已暂停', BLOCKED_EXTERNAL: '外部封控' }
const statusTags: Record<string, string> = { ACTIVE: 'success', PAUSED: 'warning', BLOCKED_EXTERNAL: 'danger' }
const eventNames: Record<string, string> = {
  ORDER_CREATE: '渠道建单', ORDER_MODIFY: '渠道改单', ORDER_CANCEL: '渠道取消', PRICE_UPDATE: '价格通知',
  RESERVATION_CREATED: '预订已创建', RESERVATION_CONFIRMED: '预订已确认', RESERVATION_MODIFIED: '预订已修改',
  RESERVATION_CANCELLED: '预订已取消', RESERVATION_CHECKED_IN: '已办入住', INVENTORY_BLOCKED: '库存停售', INVENTORY_RELEASED: '库存恢复',
}
const inboundStatusNames: Record<string, string> = { PENDING: '待处理', PROCESSED: '已处理', OBSOLETE: '乱序归档', MANUAL: '人工处理' }
const outboxStatusNames: Record<string, string> = { PENDING: '待投递', SENT: '已投递', MANUAL: '人工处理' }

const connections = ref<ChannelConnection[]>([]), selected = ref<ChannelConnection | null>(null)
const loading = ref(false), error = ref(''), acting = ref(false), saving = ref(false)
const tab = ref('mappings')
const mappings = ref<ChannelMapping[]>([])
const inbound = ref<ChannelEventPage<ChannelInboundEvent>>({ total: 0, items: [] })
const outbox = ref<ChannelEventPage<ChannelOutboxEvent>>({ total: 0, items: [] })
const runs = ref<ChannelReconcileRun[]>([])
const eventStatus = ref('ALL')
const createDialog = ref(false), editDialog = ref(false), mappingDialog = ref(false)
const createForm = ref({ supplierCode: 'SIMULATOR', name: '', baseUrl: '', credentialRef: '', callbackSecretRef: '', notes: '' })
const editForm = ref({ status: 'ACTIVE' as ChannelConnection['status'], baseUrl: '', credentialRef: '', callbackSecretRef: '', notes: '', version: 0 })
const mappingForm = ref({ kind: 'ROOM_TYPE' as 'ROOM_TYPE' | 'RATE_PLAN', localId: undefined as number | undefined, externalCode: '' })
const roomTypes = ref<RoomType[]>([]), ratePlans = ref<RatePlanView[]>([])

async function load() {
  loading.value = true; error.value = ''
  try { connections.value = await channelList() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '渠道连接加载失败' }
  finally { loading.value = false }
}
async function loadDetail() {
  const id = selected.value?.id
  if (!id) return
  error.value = ''
  try {
    const [mapRows, inPage, outPage, runRows] = await Promise.all([
      api<ChannelMapping[]>(`/api/channels/${id}/mappings`),
      channelInbound(id, eventStatus.value === 'ALL' ? 'ALL' : eventStatus.value),
      channelOutbox(id, eventStatus.value === 'ALL' ? 'ALL' : eventStatus.value),
      channelReconciliations(id),
    ])
    if (id !== selected.value?.id) return
    mappings.value = mapRows; inbound.value = inPage; outbox.value = outPage; runs.value = runRows
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '连接详情加载失败' }
}
async function refreshConnection() {
  const id = selected.value?.id
  await load()
  if (id) selected.value = connections.value.find(item => item.id === id) || null
}
function open(row: ChannelConnection) {
  selected.value = row; tab.value = 'mappings'; eventStatus.value = 'ALL'; void loadDetail()
}
function openCreate() {
  createForm.value = { supplierCode: 'SIMULATOR', name: '', baseUrl: '', credentialRef: '', callbackSecretRef: '', notes: '' }
  createDialog.value = true
}
async function submitCreate() {
  const form = createForm.value
  if (!form.name.trim() || !form.credentialRef.trim() || !form.callbackSecretRef.trim()) {
    ElMessage.error('请填写连接名称、凭据引用与回调密钥引用'); return
  }
  saving.value = true
  try {
    const created = await createChannel({ ...form, baseUrl: form.baseUrl.trim() || undefined, notes: form.notes.trim() || undefined })
    createDialog.value = false; ElMessage.success('渠道连接已创建（凭据仅保存引用，值不入库）')
    await load(); selected.value = created; void loadDetail()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '创建失败') }
  finally { saving.value = false }
}
function openEdit(row: ChannelConnection) {
  editForm.value = {
    status: row.status, baseUrl: row.baseUrl || '', credentialRef: row.credentialRef,
    callbackSecretRef: row.callbackSecretRef, notes: row.notes || '', version: row.version,
  }
  editDialog.value = true
}
async function submitEdit() {
  const form = editForm.value
  saving.value = true
  try {
    await updateChannel(selected.value!.id, {
      version: form.version, status: form.status, baseUrl: form.baseUrl.trim() || null,
      credentialRef: form.credentialRef.trim(), callbackSecretRef: form.callbackSecretRef.trim(), notes: form.notes.trim() || null,
    })
    editDialog.value = false; ElMessage.success('连接设置已保存')
    await refreshConnection(); await loadDetail()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '保存失败') }
  finally { saving.value = false }
}
async function toggle(row: ChannelConnection) {
  const pause = row.status === 'ACTIVE'
  try { await ElMessageBox.confirm(`确定${pause ? '暂停' : '恢复'}渠道连接“${row.name}”？暂停后回调仍会接收存档，但不再处理入站、也不投递出站事件。`, '确认操作', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  acting.value = true
  try {
    await updateChannel(row.id, { version: row.version, status: pause ? 'PAUSED' : 'ACTIVE' })
    ElMessage.success('连接状态已更新')
    if (selected.value?.id === row.id) await refreshConnection(); await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '操作失败') }
  finally { acting.value = false }
}
async function loadMappingOptions() {
  try {
    if (mappingForm.value.kind === 'ROOM_TYPE') {
      if (!roomTypes.value.length) {
        const page = await api<PageResult<RoomType>>('/api/room-types?page=1&pageSize=100&enabled=true')
        roomTypes.value = page.items
      }
    } else if (!ratePlans.value.length) ratePlans.value = await api<RatePlanView[]>('/api/billing/rate-plans')
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '本地对象加载失败') }
}
function openMapping() {
  mappingForm.value = { kind: 'ROOM_TYPE', localId: undefined, externalCode: '' }
  void loadMappingOptions(); mappingDialog.value = true
}
async function submitMapping() {
  const form = mappingForm.value
  if (!form.localId || !form.externalCode.trim()) { ElMessage.error('请选择本地对象并填写渠道编码'); return }
  saving.value = true
  try {
    await addChannelMapping(selected.value!.id, { kind: form.kind, localId: form.localId, externalCode: form.externalCode.trim() })
    mappingDialog.value = false; ElMessage.success('映射已保存'); await loadDetail()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '保存失败') }
  finally { saving.value = false }
}
async function removeMapping(row: ChannelMapping) {
  try { await ElMessageBox.confirm(`确定删除映射“${row.externalCode}”？已生成的预订单不受影响。`, '确认操作', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  acting.value = true
  try { await deleteChannelMapping(row.id); ElMessage.success('映射已删除'); await loadDetail() }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '删除失败') }
  finally { acting.value = false }
}
async function retryIn(row: ChannelInboundEvent) {
  acting.value = true
  try { await retryChannelInbound(row.id); ElMessage.success('已重新排队，请点击立即处理或等待后台工作器'); await loadDetail(); await refreshConnection() }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '重试失败') }
  finally { acting.value = false }
}
async function retryOut(row: ChannelOutboxEvent) {
  acting.value = true
  try { await retryChannelOutbox(row.id); ElMessage.success('已重新排队，请点击立即处理或等待后台工作器'); await loadDetail(); await refreshConnection() }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '重试失败') }
  finally { acting.value = false }
}
async function processNow() {
  if (!selected.value) return
  acting.value = true
  try {
    const result = await channelProcessNow(selected.value.id)
    ElMessage.success(`处理完成：入站 成功${result.inbound.processed}/乱序${result.inbound.obsolete}/失败${result.inbound.failed}，出站 投递${result.outbound.sent}/失败${result.outbound.failed}/跳过${result.outbound.skipped}`)
    await refreshConnection(); await loadDetail()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '处理失败') }
  finally { acting.value = false }
}
async function reconcile() {
  if (!selected.value) return
  acting.value = true
  try {
    const result = await channelReconcile(selected.value.id)
    runs.value = result.runs
    const latest = result.runs[0]
    if (latest?.status === 'COMPLETED') ElMessage.success(`对账完成：核对${latest.checkedCount}，本地缺${latest.missingRemoteCount}，渠道缺${latest.missingLocalCount}，不一致${latest.mismatchCount}`)
    else ElMessage.warning(latest?.lastError || '对账未完成，请稍后重试')
    await refreshConnection()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '对账失败') }
  finally { acting.value = false }
}
function changeEventStatus() { if (selected.value) void loadDetail() }
function mappingLabel(row: ChannelMapping) {
  return row.kind === 'ROOM_TYPE'
    ? `${row.localName || row.localId}${row.localCode ? `（${row.localCode}）` : ''}`
    : `${row.localName || row.localId}`
}
onMounted(load)
</script>

<template>
  <section class="rooms-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow muted">CHANNEL CONNECTIONS</p>
        <h1>渠道连接</h1>
        <p class="subtle">接入外部渠道：签名回调接收、映射、有序入站处理与出站投递全部留痕。真实供应商接入前保持外部封控。</p>
      </div>
      <el-button type="primary" @click="openCreate">新建连接</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-button v-if="error" class="retry-button" @click="load">重试</el-button>

    <div class="catalog-panel" v-loading="loading">
      <div class="table-caption"><h2>渠道连接</h2><span class="subtle">共 {{ connections.length }} 个</span></div>
      <el-table :data="connections" empty-text="暂无渠道连接，点击新建连接开始接入" @row-click="open" class="clickable-rows">
        <el-table-column prop="name" label="连接名称" min-width="140" />
        <el-table-column label="供应商" min-width="100"><template #default="{ row }">{{ (row as ChannelConnection).supplierCode === 'SIMULATOR' ? '模拟供应商' : (row as ChannelConnection).supplierCode }}</template></el-table-column>
        <el-table-column label="状态" min-width="95"><template #default="{ row }"><el-tag :type="(statusTags[(row as ChannelConnection).status] || 'info') as 'success' | 'warning' | 'danger' | 'info'">{{ statusNames[(row as ChannelConnection).status] || (row as ChannelConnection).status }}</el-tag></template></el-table-column>
        <el-table-column label="入站（待/人工）" min-width="115"><template #default="{ row }">{{ (row as ChannelConnection).pendingInbound }} / {{ (row as ChannelConnection).manualInbound }}</template></el-table-column>
        <el-table-column label="出站（待/人工）" min-width="115"><template #default="{ row }">{{ (row as ChannelConnection).pendingOutbox }} / {{ (row as ChannelConnection).manualOutbox }}</template></el-table-column>
        <el-table-column label="最近入站" min-width="150"><template #default="{ row }">{{ (row as ChannelConnection).lastInboundAt ? localTime((row as ChannelConnection).lastInboundAt as string) : '—' }}</template></el-table-column>
        <el-table-column label="最近出站" min-width="150"><template #default="{ row }">{{ (row as ChannelConnection).lastOutboundAt ? localTime((row as ChannelConnection).lastOutboundAt as string) : '—' }}</template></el-table-column>
        <el-table-column label="最近错误" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ (row as ChannelConnection).lastError || '—' }}</template></el-table-column>
        <el-table-column label="操作" min-width="150" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" @click.stop="open(row as ChannelConnection)">详情</el-button>
            <el-button text size="small" :disabled="acting" @click.stop="openEdit(row as ChannelConnection)">编辑</el-button>
            <el-button v-if="(row as ChannelConnection).status !== 'BLOCKED_EXTERNAL'" text size="small" :disabled="acting" @click.stop="toggle(row as ChannelConnection)">{{ (row as ChannelConnection).status === 'ACTIVE' ? '暂停' : '恢复' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template v-if="selected">
      <div class="page-heading detail-heading">
        <div><p class="eyebrow muted">CONNECTION #{{ selected.id }}</p><h2 class="detail-title">{{ selected.name }}<el-tag class="detail-tag" :type="(statusTags[selected.status] || 'info') as 'success' | 'warning' | 'danger' | 'info'">{{ statusNames[selected.status] || selected.status }}</el-tag></h2>
        <p class="subtle">凭据引用：{{ selected.credentialRef }} · 回调密钥引用：{{ selected.callbackSecretRef }}<span v-if="selected.lastSyncAt"> · 最近对账 {{ localTime(selected.lastSyncAt) }}</span></p></div>
        <div class="detail-actions"><el-button type="primary" :loading="acting" @click="processNow">立即处理</el-button><el-button :loading="acting" @click="reconcile">立即对账</el-button></div>
      </div>
      <div class="catalog-panel">
        <el-tabs v-model="tab">
          <el-tab-pane label="渠道映射" name="mappings">
            <div class="table-caption"><h2>编码映射</h2><el-button size="small" type="primary" @click="openMapping">新增映射</el-button></div>
            <el-table :data="mappings" empty-text="暂无映射：渠道下单需先建立渠道房型编码与本地房型的映射">
              <el-table-column label="类型" min-width="100"><template #default="{ row }">{{ (row as ChannelMapping).kind === 'ROOM_TYPE' ? '房型' : '价格计划' }}</template></el-table-column>
              <el-table-column label="渠道编码" min-width="140" prop="externalCode" />
              <el-table-column label="本地对象" min-width="180"><template #default="{ row }">{{ mappingLabel(row as ChannelMapping) }}</template></el-table-column>
              <el-table-column label="状态" min-width="80"><template #default="{ row }">{{ (row as ChannelMapping).active ? '生效' : '停用' }}</template></el-table-column>
              <el-table-column label="创建时间" min-width="150"><template #default="{ row }">{{ localTime((row as ChannelMapping).createdAt) }}</template></el-table-column>
              <el-table-column label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button text size="small" :disabled="acting" @click="removeMapping(row as ChannelMapping)">删除</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`入站事件（${inbound.total}）`" name="inbound">
            <div class="table-caption"><h2>签名回调事件</h2>
              <el-select v-model="eventStatus" aria-label="事件状态" style="width: 140px" @change="changeEventStatus">
                <el-option label="全部状态" value="ALL" /><el-option v-for="(name, key) in inboundStatusNames" :key="key" :label="name" :value="key" />
              </el-select></div>
            <el-table :data="inbound.items" empty-text="暂无入站事件：模拟回调可在集成测试或供应商沙箱中发起">
              <el-table-column label="事件" min-width="110"><template #default="{ row }">{{ eventNames[(row as ChannelInboundEvent).eventType] || (row as ChannelInboundEvent).eventType }}</template></el-table-column>
              <el-table-column label="外部事件号" min-width="180" prop="externalEventId" show-overflow-tooltip />
              <el-table-column label="序号" min-width="70" prop="sequenceNo" />
              <el-table-column label="状态" min-width="95"><template #default="{ row }"><el-tag :type="(row as ChannelInboundEvent).status === 'PROCESSED' ? 'success' : (row as ChannelInboundEvent).status === 'MANUAL' ? 'danger' : (row as ChannelInboundEvent).status === 'OBSOLETE' ? 'info' : 'warning'">{{ inboundStatusNames[(row as ChannelInboundEvent).status] || (row as ChannelInboundEvent).status }}</el-tag></template></el-table-column>
              <el-table-column label="次数" min-width="65" prop="attempts" />
              <el-table-column label="预订单" min-width="80"><template #default="{ row }">{{ (row as ChannelInboundEvent).resultReservationId ? `#${(row as ChannelInboundEvent).resultReservationId}` : '—' }}</template></el-table-column>
              <el-table-column label="最近错误" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ (row as ChannelInboundEvent).lastError || '—' }}</template></el-table-column>
              <el-table-column label="接收时间" min-width="150"><template #default="{ row }">{{ localTime((row as ChannelInboundEvent).receivedAt) }}</template></el-table-column>
              <el-table-column label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button v-if="(row as ChannelInboundEvent).status === 'MANUAL'" text size="small" :disabled="acting" @click="retryIn(row as ChannelInboundEvent)">重新入队</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`出站事件（${outbox.total}）`" name="outbox">
            <div class="table-caption"><h2>发件箱投递</h2>
              <el-select v-model="eventStatus" aria-label="投递状态" style="width: 140px" @change="changeEventStatus">
                <el-option label="全部状态" value="ALL" /><el-option v-for="(name, key) in outboxStatusNames" :key="key" :label="name" :value="key" />
              </el-select></div>
            <el-table :data="outbox.items" empty-text="暂无出站事件：渠道预订或库存停售产生业务变更后会进入发件箱">
              <el-table-column label="事件" min-width="120"><template #default="{ row }">{{ eventNames[(row as ChannelOutboxEvent).eventType] || (row as ChannelOutboxEvent).eventType }}</template></el-table-column>
              <el-table-column label="对象" min-width="130"><template #default="{ row }">{{ (row as ChannelOutboxEvent).aggregateType === 'RESERVATION' ? `预订 #${(row as ChannelOutboxEvent).aggregateId}` : `停售 #${(row as ChannelOutboxEvent).aggregateId}` }}</template></el-table-column>
              <el-table-column label="状态" min-width="90"><template #default="{ row }"><el-tag :type="(row as ChannelOutboxEvent).status === 'SENT' ? 'success' : (row as ChannelOutboxEvent).status === 'MANUAL' ? 'danger' : 'warning'">{{ outboxStatusNames[(row as ChannelOutboxEvent).status] || (row as ChannelOutboxEvent).status }}</el-tag></template></el-table-column>
              <el-table-column label="次数" min-width="65" prop="attempts" />
              <el-table-column label="最近错误" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ (row as ChannelOutboxEvent).lastError || '—' }}</template></el-table-column>
              <el-table-column label="创建时间" min-width="150"><template #default="{ row }">{{ localTime((row as ChannelOutboxEvent).createdAt) }}</template></el-table-column>
              <el-table-column label="投递时间" min-width="150"><template #default="{ row }">{{ (row as ChannelOutboxEvent).sentAt ? localTime((row as ChannelOutboxEvent).sentAt as string) : '—' }}</template></el-table-column>
              <el-table-column label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button v-if="(row as ChannelOutboxEvent).status === 'MANUAL'" text size="small" :disabled="acting" @click="retryOut(row as ChannelOutboxEvent)">重新入队</el-button></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`对账记录（${runs.length}）`" name="reconcile">
            <el-table :data="runs" empty-text="暂无对账记录：点击立即对账与渠道快照比对预订状态">
              <el-table-column label="批次" min-width="70" prop="id" />
              <el-table-column label="结果" min-width="95"><template #default="{ row }"><el-tag :type="(row as ChannelReconcileRun).status === 'COMPLETED' ? 'success' : (row as ChannelReconcileRun).status === 'FAILED' ? 'danger' : 'info'">{{ (row as ChannelReconcileRun).status === 'COMPLETED' ? '完成' : (row as ChannelReconcileRun).status === 'FAILED' ? '失败' : '进行中' }}</el-tag></template></el-table-column>
              <el-table-column label="核对" min-width="70" prop="checkedCount" />
              <el-table-column label="本地缺" min-width="75" prop="missingRemoteCount" />
              <el-table-column label="渠道缺" min-width="75" prop="missingLocalCount" />
              <el-table-column label="不一致" min-width="75" prop="mismatchCount" />
              <el-table-column label="差异明细" min-width="240" show-overflow-tooltip><template #default="{ row }">{{ (row as ChannelReconcileRun).detail && (row as ChannelReconcileRun).detail !== '{}' ? (row as ChannelReconcileRun).detail : '—' }}</template></el-table-column>
              <el-table-column label="开始时间" min-width="150"><template #default="{ row }">{{ localTime((row as ChannelReconcileRun).startedAt) }}</template></el-table-column>
              <el-table-column label="失败原因" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ (row as ChannelReconcileRun).lastError || '—' }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>

    <el-dialog v-model="createDialog" title="新建渠道连接" width="min(580px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="submitCreate">
        <div class="form-columns">
          <el-form-item label="供应商" for="channel-supplier" required>
            <el-select id="channel-supplier" v-model="createForm.supplierCode" :disabled="saving">
              <el-option label="模拟供应商（SIMULATOR，仅受控联调）" value="SIMULATOR" />
            </el-select>
          </el-form-item>
          <el-form-item label="连接名称" for="channel-name" required><el-input id="channel-name" v-model="createForm.name" maxlength="100" placeholder="例如 模拟渠道联调" :disabled="saving" /></el-form-item>
        </div>
        <el-form-item label="凭据引用" for="channel-cred" required><el-input id="channel-cred" v-model="createForm.credentialRef" maxlength="200" placeholder="环境变量或系统属性名，例如 STAYOS_SIM_CREDENTIAL" :disabled="saving" /></el-form-item>
        <el-form-item label="回调密钥引用" for="channel-secret" required><el-input id="channel-secret" v-model="createForm.callbackSecretRef" maxlength="200" placeholder="例如 STAYOS_SIM_CALLBACK_SECRET" :disabled="saving" /></el-form-item>
        <el-form-item label="接口地址" for="channel-url"><el-input id="channel-url" v-model="createForm.baseUrl" maxlength="300" placeholder="可选" :disabled="saving" /></el-form-item>
        <el-form-item label="备注" for="channel-notes"><el-input id="channel-notes" v-model="createForm.notes" type="textarea" :rows="2" maxlength="500" :disabled="saving" /></el-form-item>
        <el-alert type="info" :closable="false" title="系统只保存引用名并在运行时解析；创建时会立即校验引用可解析，真实密钥值不进入数据库与日志。" />
        <div class="dialog-actions"><el-button :disabled="saving" @click="createDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">创建连接</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑渠道连接" width="min(580px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="submitEdit">
        <el-form-item label="连接状态" for="channel-status" required>
          <el-select id="channel-status" v-model="editForm.status" :disabled="saving">
            <el-option label="启用" value="ACTIVE" /><el-option label="已暂停（收发暂停，回调仍存档）" value="PAUSED" /><el-option label="外部封控" value="BLOCKED_EXTERNAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭据引用" for="channel-cred-edit" required><el-input id="channel-cred-edit" v-model="editForm.credentialRef" maxlength="200" :disabled="saving" /></el-form-item>
        <el-form-item label="回调密钥引用" for="channel-secret-edit" required><el-input id="channel-secret-edit" v-model="editForm.callbackSecretRef" maxlength="200" :disabled="saving" /></el-form-item>
        <el-form-item label="接口地址" for="channel-url-edit"><el-input id="channel-url-edit" v-model="editForm.baseUrl" maxlength="300" :disabled="saving" /></el-form-item>
        <el-form-item label="备注" for="channel-notes-edit"><el-input id="channel-notes-edit" v-model="editForm.notes" type="textarea" :rows="2" maxlength="500" :disabled="saving" /></el-form-item>
        <div class="dialog-actions"><el-button :disabled="saving" @click="editDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存设置</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog v-model="mappingDialog" title="新增渠道映射" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="submitMapping">
        <el-form-item label="映射类型" for="mapping-kind" required>
          <el-select id="mapping-kind" v-model="mappingForm.kind" aria-label="映射类型" :disabled="saving" @change="mappingForm.localId = undefined; loadMappingOptions()">
            <el-option label="房型" value="ROOM_TYPE" /><el-option label="价格计划" value="RATE_PLAN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="mappingForm.kind === 'ROOM_TYPE' ? '本地房型' : '本地价格计划'" for="mapping-local" required>
          <el-select id="mapping-local" v-model="mappingForm.localId" aria-label="本地房型" filterable :disabled="saving" placeholder="请选择">
            <el-option v-if="mappingForm.kind === 'ROOM_TYPE'" v-for="type in roomTypes" :key="type.id" :label="`${type.typeName}（${type.typeCode}）`" :value="type.id" />
            <el-option v-if="mappingForm.kind === 'RATE_PLAN'" v-for="plan in ratePlans" :key="plan.id" :label="`${plan.planName}（${plan.planCode}）`" :value="plan.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道编码" for="mapping-external" required><el-input id="mapping-external" v-model="mappingForm.externalCode" maxlength="120" placeholder="供应商侧的编码，例如 SIM_KING" :disabled="saving" /></el-form-item>
        <div class="dialog-actions"><el-button :disabled="saving" @click="mappingDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存映射</el-button></div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.clickable-rows :deep(tbody tr) { cursor: pointer; }
.detail-heading { margin-top: 20px; }
.detail-title { display: flex; align-items: center; gap: 10px; font-size: 20px; }
.detail-tag { flex: none; }
.detail-actions { display: flex; gap: 10px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }
</style>
