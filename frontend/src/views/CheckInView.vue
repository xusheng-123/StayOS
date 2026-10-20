<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElSelect, ElOption, ElDatePicker, ElRadio, ElEmpty, ElPagination } from 'element-plus'
import { api, ApiError } from '../api/client'
import { roomOptions, roomQuery, floorName } from '../api/rooms'
import { auth } from '../stores/auth'
import type { Room, RoomType, Floor, PageResult, RoomFilters } from '../types/room'
import type { CheckInInput, GuestInput, Stay } from '../types/stay'
import RoomDetail from '../components/RoomDetail.vue'

const router = useRouter(), filters = ref<RoomFilters>({ roomNumber: '' }), page = ref(1)
const rooms = ref<Room[]>([]), total = ref(0), types = ref<RoomType[]>([]), floors = ref<Floor[]>([])
const selected = ref<Room | null>(null), detail = ref<number | null>(null), loading = ref(false), busy = ref(false), error = ref('')
const phoneBlank = (): GuestInput => ({ name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', notes: '', primary: false })
const guests = ref<GuestInput[]>([{ ...phoneBlank(), primary: true }]), primary = ref(0), departure = ref(''), notes = ref('')
const markerName = `stayos:check-in-pending:${auth.user?.id}`
const orphaned = ref(Boolean(sessionStorage.getItem(markerName)))
const pending = ref<{ key: string; body: CheckInInput } | null>(null)
const locked = computed(() => busy.value || pending.value !== null || orphaned.value)
const advice = computed(() => types.value.find(t => t.id === selected.value?.roomTypeId)?.suggestedOccupancy)
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true
  try {
    const result = await api<PageResult<Room>>(`/api/stays/available-rooms?${roomQuery(filters.value, page.value, 12)}`)
    if (current === generation) { rooms.value = result.items; total.value = result.total }
  } catch (cause) { if (current === generation) { rooms.value = []; error.value = cause instanceof Error ? cause.message : '房间加载失败' } }
  finally { if (current === generation) loading.value = false }
}
onMounted(async () => {
  try { const options = await roomOptions(); types.value = options.types; floors.value = options.floors; await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '筛选选项加载失败' }
})
function remove(index: number) {
  guests.value.splice(index, 1)
  if (primary.value === index) primary.value = 0
  else if (primary.value > index) primary.value--
}
function forget() { sessionStorage.removeItem(markerName); pending.value = null; orphaned.value = false }
async function checked() {
  try { await ElMessageBox.confirm('确认已在当前入住记录中核对上次操作结果，再重新登记。', '核对入住结果', { confirmButtonText: '已核对，重新登记', cancelButtonText: '返回核对' }); forget(); error.value = '' }
  catch { /* Keep the unresolved marker. */ }
}
async function cancel() {
  if (locked.value) return
  try { await ElMessageBox.confirm('放弃当前填写的入住资料？', '取消填写', { confirmButtonText: '放弃填写', cancelButtonText: '继续填写' }); guests.value = [{ ...phoneBlank(), primary: true }]; primary.value = 0; selected.value = null; departure.value = ''; notes.value = ''; error.value = '' }
  catch { /* Continue editing. */ }
}
async function submit() {
  if (busy.value || orphaned.value) return
  const retrying = pending.value !== null
  error.value = ''
  if (!pending.value) {
    if (!selected.value || !departure.value) { error.value = '请选择房间和预计退房时间'; return }
    if (guests.value.some(g => !g.name.trim() || !g.documentNumber.trim())) { error.value = '请填写每位实际住客的姓名和证件号码'; return }
    // Element Plus value is explicitly interpreted in the hotel's timezone, not the browser's timezone.
    const expected = new Date(`${departure.value.replace(' ', 'T')}+08:00`)
    if (Number.isNaN(expected.getTime()) || expected.getTime() <= Date.now()) { error.value = '预计退房时间应晚于当前时间，最终以服务器校验为准'; return }
    const body: CheckInInput = { roomId: selected.value.id, expectedCheckOutAt: expected.toISOString(), notes: notes.value,
      guests: guests.value.map((g, i) => ({ ...g, primary: primary.value === i })) }
    pending.value = { key: crypto.randomUUID(), body }
    // Only the random operation marker is persisted. Guest fields are never put in browser storage.
    sessionStorage.setItem(markerName, pending.value.key)
  }
  busy.value = true
  try {
    const stay = await api<Stay>('/api/stays', { method: 'POST', headers: { 'Idempotency-Key': pending.value.key }, body: JSON.stringify(pending.value.body) })
    forget(); guests.value = [{ ...phoneBlank(), primary: true }]; notes.value = ''; ElMessage.success('入住办理成功')
    await router.push(`/stays/${stay.id}`)
  } catch (cause) {
    const uncertain = retrying || !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500 || cause.code === 'INVALID_RESPONSE' || cause.code === 'IDEMPOTENCY_KEY_EXPIRED' || cause.status === 401
    error.value = (cause instanceof Error ? cause.message : '入住失败') + (uncertain ? '。结果尚未确认，请重试原请求或先查询入住记录。' : '')
    if (!uncertain) { forget(); void load() }
  } finally { busy.value = false }
}
</script>

<template>
  <section class="rooms-page stay-page">
    <div class="page-heading"><div><p class="eyebrow muted">GUEST CHECK-IN</p><h1>办理入住</h1><p class="subtle">先选房间，再登记全部实际住客。入住时间由服务器记录。</p></div><router-link to="/stays"><el-button>查看当前入住</el-button></router-link></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="stay-alert" />
    <el-alert v-if="orphaned" title="检测到上次入住结果尚未确认。请先查询当前入住记录，避免重复操作。" type="warning" :closable="false" show-icon class="stay-alert" />
    <div v-if="orphaned" class="heading-actions stay-alert"><router-link to="/stays"><el-button>查询入住记录</el-button></router-link><el-button @click="checked">已核对，重新登记</el-button></div>
    <div class="catalog-panel stay-section">
      <div class="table-caption"><h2>01 · 选择可入住房间</h2><el-button :loading="loading" @click="load()">刷新可入住房间</el-button></div>
      <form class="stay-room-filters" @submit.prevent="load(true)">
        <el-input v-model="filters.roomNumber" aria-label="入住选房房号" placeholder="搜索房号" maxlength="20" clearable />
        <el-select v-model="filters.floor" aria-label="入住选房楼层" placeholder="全部楼层" clearable @change="load(true)"><el-option v-for="f in floors" :key="f.floor" :label="floorName(f.floor)" :value="f.floor" /></el-select>
        <el-select v-model="filters.roomTypeId" aria-label="入住选房房型" placeholder="全部房型" clearable @change="load(true)"><el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" /></el-select>
        <el-button native-type="submit" :loading="loading">查询房间</el-button>
      </form>
      <div v-loading="loading" class="room-card-grid stay-room-grid">
        <div v-for="room in rooms" :key="room.id" :class="['stay-room-choice', { selected: selected?.id === room.id }]">
          <button :disabled="locked" :aria-label="'选择房间 ' + room.roomNumber" :aria-pressed="selected?.id === room.id" @click="selected = room"><strong>{{ room.roomNumber }}</strong><span>{{ room.roomTypeName }} · {{ floorName(room.floor) }}</span><span class="room-status-pill state-VACANT_CLEAN">空闲可入住</span></button>
          <el-button text size="small" @click="detail = room.id">查看详情</el-button>
        </div>
      </div>
      <el-empty v-if="!loading && !rooms.length" description="暂无可入住房间" />
      <el-pagination v-if="total > 12" class="room-pagination" layout="prev, pager, next" :total="total" :page-size="12" v-model:current-page="page" @current-change="load()" />
      <p v-if="selected" class="stay-selected">已选：<strong>{{ selected.roomNumber }}</strong> · {{ selected.roomTypeName }}<span class="subtle"> · 提交时后端将再次核对房态</span></p>
    </div>
    <el-form label-position="top" :disabled="locked" @submit.prevent>
      <div class="catalog-panel stay-section">
        <div class="table-caption"><h2>02 · 登记实际住客（{{ guests.length }} 人）</h2><el-button :disabled="guests.length >= 20" @click="guests.push(phoneBlank())">添加同行住客</el-button></div>
        <p class="subtle">证件与电话加密保存，查询默认脱敏。开发测试请使用虚构资料；格式检查不代表身份核验。</p>
        <el-alert v-if="advice && guests.length > advice" :title="`当前登记人数超过房型建议的 ${advice} 人，请核对实际安排。该建议不是法定容量判断。`" type="warning" :closable="false" class="stay-alert" />
        <div v-for="(guest, index) in guests" :key="index" class="stay-guest">
          <div class="table-caption"><h3>住客 {{ index + 1 }}</h3><div><el-radio v-model="primary" :value="index" :aria-label="`将住客 ${index + 1} 设为主要联系人`">主要联系人</el-radio><el-button v-if="guests.length > 1" text type="danger" :aria-label="`删除住客 ${index + 1}`" @click="remove(index)">删除</el-button></div></div>
          <div class="stay-guest-fields">
            <el-form-item label="姓名" required><el-input v-model="guest.name" :aria-label="`住客 ${index + 1} 姓名`" maxlength="100" autocomplete="off" /></el-form-item>
            <el-form-item label="证件类型" required><el-select v-model="guest.documentType" :aria-label="`住客 ${index + 1} 证件类型`"><el-option label="居民身份证" value="ID_CARD" /><el-option label="护照" value="PASSPORT" /><el-option label="其他证件" value="OTHER" /></el-select></el-form-item>
            <el-form-item label="证件号码" required><el-input v-model="guest.documentNumber" :aria-label="`住客 ${index + 1} 证件号码`" maxlength="64" autocomplete="off" /></el-form-item>
            <el-form-item label="联系电话（选填）"><el-input v-model="guest.phone" :aria-label="`住客 ${index + 1} 联系电话`" maxlength="30" autocomplete="off" /></el-form-item>
          </div>
          <el-form-item label="住客备注（选填）"><el-input v-model="guest.notes" :aria-label="`住客 ${index + 1} 备注`" maxlength="1000" placeholder="不要在备注中重复填写证件或电话号码" /></el-form-item>
        </div>
      </div>
      <div class="catalog-panel stay-section"><h2>03 · 确认入住信息</h2><div class="form-columns">
        <el-form-item label="预计退房时间（北京时间）" required><el-date-picker v-model="departure" type="datetime" aria-label="预计退房时间" value-format="YYYY-MM-DD HH:mm:ss" format="YYYY-MM-DD HH:mm" placeholder="选择预计退房时间" /></el-form-item>
        <el-form-item label="实际入住时间"><el-input model-value="办理成功时由服务器记录" disabled /></el-form-item>
      </div><el-form-item label="入住备注（选填）"><el-input v-model="notes" aria-label="入住备注" type="textarea" maxlength="1000" :rows="2" placeholder="不要填写证件号码等敏感内容" /></el-form-item></div>
    </el-form>
    <div class="stay-submit"><span class="subtle">{{ selected?.roomNumber ?? '尚未选择房间' }} · {{ guests.length }} 名实际住客</span><div><el-button :disabled="locked" @click="cancel">取消本次填写</el-button><el-button type="primary" :loading="busy" :disabled="orphaned || (!selected && !pending)" @click="submit">{{ pending ? '重试原入住请求' : '确认入住' }}</el-button></div></div>
    <RoomDetail v-model="detail" />
  </section>
</template>
