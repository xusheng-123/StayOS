<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElSelect, ElOption, ElInputNumber, ElTable, ElTableColumn, ElDialog, ElPagination, ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { roomOptions, roomQuery, availabilityNames, floorName } from '../api/rooms'
import type { Room, RoomType, Floor, RoomFilters as Filters, PageResult } from '../types/room'
import RoomFilters from '../components/RoomFilters.vue'
import RoomDetail from '../components/RoomDetail.vue'

const filters = ref<Filters>({ roomNumber: '' }), types = ref<RoomType[]>([]), floors = ref<Floor[]>([])
const rooms = ref<Room[]>([]), total = ref(0), page = ref(1), loading = ref(false), saving = ref(false), error = ref('')
const dialog = ref(false), editing = ref<Room | null>(null), detail = ref<number | null>(null)
const form = ref({ roomNumber: '', floor: 1 as number | undefined, roomTypeId: undefined as number | undefined, notes: '' })
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try { const result = await api<PageResult<Room>>(`/api/rooms?${roomQuery(filters.value, page.value)}`); if (current === generation) { rooms.value = result.items; total.value = result.total } }
  catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '房间加载失败'; rooms.value = []; total.value = 0 } }
  finally { if (current === generation) loading.value = false }
}
async function refresh() {
  try { const result = await roomOptions(); types.value = result.types; floors.value = result.floors; await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '筛选选项加载失败' }
}
function search() { page.value = 1; void load() }
function reset() { filters.value = { roomNumber: '' }; search() }
function open(room?: Room) {
  editing.value = room || null
  form.value = { roomNumber: room?.roomNumber || '', floor: room?.floor ?? 1, roomTypeId: room?.roomTypeId, notes: room?.notes || '' }
  dialog.value = true
}
async function save() {
  if (!form.value.roomNumber.trim() || !Number.isInteger(form.value.floor) || form.value.floor === 0 || !form.value.roomTypeId) {
    ElMessage.error('请填写房号，选择房型，并输入非零整数楼层'); return
  }
  saving.value = true
  try {
    const room = editing.value
    await api(room ? `/api/rooms/${room.id}` : '/api/rooms', { method: room ? 'PUT' : 'POST', body: JSON.stringify({ ...form.value, ...(room ? { version: room.version } : {}) }) })
    dialog.value = false; ElMessage.success(room ? '房间资料已更新' : '房间已新增，初始状态为待打扫'); await refresh()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '保存失败') }
  finally { saving.value = false }
}
async function toggle(room: Room) {
  try { await ElMessageBox.confirm(`确定${room.enabled ? '停用' : '启用'}房间 ${room.roomNumber}？`, '确认操作', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }) }
  catch { return }
  saving.value = true
  try {
    await api(`/api/rooms/${room.id}/set-enabled`, { method: 'POST', body: JSON.stringify({ enabled: !room.enabled, version: room.version }) })
    ElMessage.success('房间启用状态已更新'); await refresh()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '操作失败') }
  finally { saving.value = false }
}
function changePage(value: number) { page.value = value; void load() }
onMounted(refresh)
</script>

<template>
  <section class="rooms-page">
    <div class="page-heading"><div><p class="eyebrow muted">ROOM CATALOG</p><h1>房间管理</h1><p class="subtle">维护基础资料。房间内部编号始终保持不变。</p></div><div class="heading-actions"><router-link to="/rooms"><el-button>查看房态图</el-button></router-link><el-button type="primary" @click="open()">新增房间</el-button></div></div>
    <RoomFilters v-model="filters" :types="types" :floors="floors" :loading="loading" @search="search" @reset="reset" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-button v-if="error" class="retry-button" @click="refresh">重试</el-button>
    <div class="catalog-panel" v-loading="loading">
      <div class="table-caption"><h2>房间资料</h2><span class="subtle">共 {{ total }} 间</span></div>
      <el-table :data="rooms" class="catalog-table" empty-text="暂无房间，请先维护房型后新增房间">
        <el-table-column prop="roomNumber" label="房号" min-width="115" /><el-table-column label="楼层" min-width="95"><template #default="{ row }">{{ floorName(row.floor) }}</template></el-table-column>
        <el-table-column prop="roomTypeName" label="房型" min-width="150" /><el-table-column label="当前房态" min-width="130"><template #default="{ row }"><span :class="['room-status-pill', 'state-' + row.availability]">{{ availabilityNames[row.availability as keyof typeof availabilityNames] }}</span></template></el-table-column>
        <el-table-column label="档案" min-width="80"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
        <el-table-column label="操作" min-width="215" fixed="right"><template #default="{ row }"><el-button text size="small" @click="detail = row.id">详情</el-button><el-button text size="small" :disabled="saving" @click="open(row as Room)">编辑</el-button><el-button text size="small" :disabled="saving" @click="toggle(row as Room)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="total > 20" class="room-pagination" layout="prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="changePage" />
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑房间' : '新增房间'" width="min(580px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="房号" for="room-number" required><el-input id="room-number" v-model="form.roomNumber" maxlength="20" placeholder="例如 0801、A801" :disabled="saving || editing?.hasUsageHistory" /><span v-if="editing?.hasUsageHistory" class="field-help">已有使用历史，不能直接修改房号。</span></el-form-item>
        <div class="form-columns"><el-form-item label="楼层" for="room-floor" required><el-input-number id="room-floor" v-model="form.floor" :min="-99" :max="199" :precision="0" :disabled="saving" /><span class="field-help">地下层用负数，不允许 0。</span></el-form-item><el-form-item label="房型" for="room-type" required><el-select id="room-type" v-model="form.roomTypeId" placeholder="请选择房型" :disabled="saving"><el-option v-for="item in types" :key="item.id" :label="item.typeName + (item.enabled ? '' : '（已停用）')" :value="item.id" :disabled="!item.enabled && item.id !== editing?.roomTypeId" /></el-select></el-form-item></div>
        <el-form-item label="备注" for="room-notes"><el-input id="room-notes" v-model="form.notes" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="saving" /></el-form-item>
        <p v-if="!editing" class="field-help">新房间默认待打扫，本阶段不提供直接修改业务房态的功能。</p>
        <div class="dialog-actions"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存房间</el-button></div>
      </el-form>
    </el-dialog>
    <RoomDetail v-model="detail" />
  </section>
</template>
