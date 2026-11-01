<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElDatePicker, ElDialog, ElMessage, ElSelect, ElOption, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { api, ApiError } from '../api/client'
import { localTime } from '../api/rooms'
import { createReservation, listReservations, releaseExpiredReservations, reservationKey } from '../api/reservation'
import type { ReservationSource, ReservationStatus, ReservationView } from '../types/reservation'
import { reservationSourceNames, reservationStatusNames } from '../types/reservation'
import type { RoomType } from '../types/room'
import type { PageResult } from '../types/room'

const router = useRouter()
const status = ref(''), query = ref(''), dates = ref<string[]>([]), page = ref(1)
const result = ref<PageResult<ReservationView> | null>(null), loading = ref(false), error = ref('')
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const response = await listReservations({ status: status.value || 'ALL', arrivalFrom: dates.value?.[0], arrivalTo: dates.value?.[1], query: query.value.trim(), page: page.value })
    if (current === generation) result.value = response
  } catch (cause) {
    if (current === generation) { error.value = cause instanceof Error ? cause.message : '预订记录加载失败'; result.value = null }
  } finally { if (current === generation) loading.value = false }
}
void load()

const statusOptions = Object.entries(reservationStatusNames).map(([value, label]) => ({ value, label }))

// ---- 新建预订 ----
const createVisible = ref(false), createBusy = ref(false), createError = ref('')
const types = ref<RoomType[]>([])
const form = ref({ arrivalDate: '', departureDate: '', roomTypeId: '' as number | '', source: 'PHONE' as ReservationSource, externalRef: '', contactName: '', contactPhone: '', notes: '' })
const sourceOptions = Object.entries(reservationSourceNames).map(([value, label]) => ({ value, label }))
const createNights = computed(() => {
  if (!form.value.arrivalDate || !form.value.departureDate) return 0
  return Math.round((new Date(form.value.departureDate).getTime() - new Date(form.value.arrivalDate).getTime()) / 86400000)
})
async function openCreate() {
  createVisible.value = true; createError.value = ''
  form.value = { arrivalDate: '', departureDate: '', roomTypeId: '', source: 'PHONE', externalRef: '', contactName: '', contactPhone: '', notes: '' }
  if (types.value.length === 0) {
    try {
      const list: RoomType[] = []
      let page2 = 1
      while (true) {
        const result2 = await api<PageResult<RoomType>>(`/api/room-types?page=${page2++}&pageSize=100`)
        list.push(...result2.items)
        if (list.length >= result2.total || result2.items.length === 0) break
      }
      types.value = list.filter(item => item.enabled)
    } catch { createError.value = '房型列表加载失败，请稍后重试' }
  }
}
async function submitCreate() {
  createError.value = ''
  if (!form.value.arrivalDate || !form.value.departureDate) { createError.value = '请选择到店与离店日期'; return }
  if (!form.value.roomTypeId) { createError.value = '请选择房型'; return }
  if (!form.value.contactName.trim()) { createError.value = '请填写联系人姓名'; return }
  createBusy.value = true
  try {
    const detail = await createReservation(reservationKey(), {
      arrivalDate: form.value.arrivalDate, departureDate: form.value.departureDate, roomTypeId: Number(form.value.roomTypeId),
      source: form.value.source, externalRef: form.value.externalRef.trim() || undefined, contactName: form.value.contactName.trim(),
      contactPhone: form.value.contactPhone.trim() || undefined, notes: form.value.notes.trim() || undefined,
    })
    createVisible.value = false
    ElMessage.success(`预订已创建（保留单，${reservationStatusNames.HELD}）`)
    void router.push(`/reservations/${detail.reservation.id}`)
  } catch (cause) { createError.value = cause instanceof ApiError ? cause.message : '预订创建失败，请稍后重试' }
  finally { createBusy.value = false }
}

// ---- 释放到期保留单 ----
const sweeping = ref(false)
async function sweep() {
  sweeping.value = true
  try {
    const outcome = await releaseExpiredReservations()
    ElMessage.success(outcome.released > 0 ? `已自动释放 ${outcome.released} 张到期保留单` : '当前没有到期保留单')
    void load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '释放到期保留单失败') }
  finally { sweeping.value = false }
}

function statusTagType(statusValue: ReservationStatus): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  return statusValue === 'CONFIRMED' ? 'success' : statusValue === 'CHECKED_IN' ? 'primary' : statusValue === 'CANCELLED' || statusValue === 'NO_SHOW' ? 'info' : 'warning'
}
</script>

<template>
  <section class="rooms-page stay-page">
    <div class="page-heading">
      <div><p class="eyebrow muted">RESERVATIONS</p><h1>预订管理</h1><p class="subtle">一单一房可多晚；[到店, 离店) 半开区间；默认禁止超卖。</p></div>
      <div><el-button @click="sweep" :loading="sweeping">释放到期保留单</el-button><el-button type="primary" @click="openCreate">新建预订</el-button></div>
    </div>
    <form class="room-filters stay-list-filters" @submit.prevent="load(true)">
      <div class="filter-field"><label>状态</label><el-select v-model="status" aria-label="预订状态" placeholder="全部状态" clearable style="width: 150px" @change="load(true)"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></div>
      <div class="filter-field"><label>外部单号 / 联系人</label><el-input v-model="query" maxlength="60" clearable placeholder="按外部单号或联系人搜索" /></div>
      <div class="filter-field"><label>到店日期范围（结束日不含）</label><el-date-picker v-model="dates" type="daterange" aria-label="到店日期范围" value-format="YYYY-MM-DD" start-placeholder="开始日" end-placeholder="结束日（不含）" /></div>
      <div class="filter-actions"><el-button native-type="submit" :loading="loading">查询预订</el-button><el-button @click="status = ''; query = ''; dates = []; load(true)">重置</el-button></div>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel">
      <div class="table-caption"><h2>预订单</h2><span class="subtle">共 {{ result?.total ?? '—' }} 条</span></div>
      <el-table :data="result?.items ?? []" empty-text="暂无预订记录" style="width: 100%">
        <el-table-column prop="id" label="单号" width="80" />
        <el-table-column label="房型" min-width="140"><template #default="{ row }">{{ (row as ReservationView).roomTypeName }}（{{ (row as ReservationView).roomTypeCode }}）</template></el-table-column>
        <el-table-column label="入住区间" min-width="175"><template #default="{ row }">{{ (row as ReservationView).arrivalDate }} → {{ (row as ReservationView).departureDate }}（{{ (row as ReservationView).nights }} 晚）</template></el-table-column>
        <el-table-column label="来源" width="100"><template #default="{ row }">{{ reservationSourceNames[(row as ReservationView).source] ?? (row as ReservationView).source }}</template></el-table-column>
        <el-table-column label="外部单号" min-width="120"><template #default="{ row }">{{ (row as ReservationView).externalRef || '—' }}</template></el-table-column>
        <el-table-column label="联系人" min-width="130"><template #default="{ row }">{{ (row as ReservationView).contactName }}<span v-if="(row as ReservationView).contactPhoneMask" class="subtle"> {{ (row as ReservationView).contactPhoneMask }}</span></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusTagType((row as ReservationView).status)">{{ reservationStatusNames[(row as ReservationView).status] }}</el-tag></template></el-table-column>
        <el-table-column label="保留截止" min-width="165"><template #default="{ row }">{{ (row as ReservationView).heldUntil ? localTime((row as ReservationView).heldUntil as string) : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><router-link :to="`/reservations/${(row as ReservationView).id}`"><el-button text>查看详情</el-button></router-link></template></el-table-column>
      </el-table>
      <el-pagination v-if="result && result.total > 20" class="room-pagination" layout="prev, pager, next" :total="result.total" :page-size="20" v-model:current-page="page" @current-change="load()" />
    </div>

    <el-dialog v-model="createVisible" title="新建预订" width="560px" @closed="createError = ''">
      <el-alert v-if="createError" :title="createError" type="error" :closable="false" show-icon />
      <el-form label-width="120px" @submit.prevent="submitCreate">
        <el-form-item label="入住区间" required><el-date-picker v-model="form.arrivalDate" type="date" value-format="YYYY-MM-DD" placeholder="到店日期" aria-label="到店日期" style="width: 47%" /><span class="subtle"> → </span><el-date-picker v-model="form.departureDate" type="date" value-format="YYYY-MM-DD" placeholder="离店日期（不含）" aria-label="离店日期" style="width: 47%" /></el-form-item>
        <el-form-item label="晚数"><span class="subtle">{{ createNights > 0 ? `${createNights} 晚` : '到店日须早于离店日' }}</span></el-form-item>
        <el-form-item label="房型" required><el-select v-model="form.roomTypeId" aria-label="预订房型" placeholder="选择房型" style="width: 100%"><el-option v-for="item in types" :key="item.id" :label="`${item.typeName}（${item.typeCode}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="预订来源" required><el-select v-model="form.source" aria-label="预订来源" style="width: 100%"><el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="外部单号"><el-input v-model="form.externalRef" maxlength="120" clearable placeholder="OTA/渠道单号，选填且不可重复" /></el-form-item>
        <el-form-item label="联系人" required><el-input v-model="form.contactName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.contactPhone" maxlength="30" clearable placeholder="选填；保存后仅显示掩码" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.notes" maxlength="1000" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="createBusy" @click="submitCreate">创建预订</el-button></template>
    </el-dialog>
  </section>
</template>
