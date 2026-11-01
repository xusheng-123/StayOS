<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElDatePicker, ElDialog, ElMessage, ElSelect, ElOption, ElTable, ElTableColumn } from 'element-plus'
import { ApiError } from '../api/client'
import { localTime } from '../api/rooms'
import { availability, createBlock, listBlocks, releaseBlock, reservationKey } from '../api/reservation'
import { api } from '../api/client'
import { can } from '../stores/auth'
import type { AvailabilityView, BlockView, NightAvailability } from '../types/reservation'
import type { RoomType } from '../types/room'
import type { PageResult } from '../types/room'

const dates = ref<string[]>([today(), plusDays(7)]), loading = ref(false), error = ref('')
const grid = ref<AvailabilityView | null>(null)
function today(): string { return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' }) }
function plusDays(days: number): string {
  const date = new Date(Date.now() + days * 86400000)
  return date.toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
}
const nights = computed<NightAvailability[]>(() => grid.value?.roomTypes[0]?.nights ?? [])
let generation = 0
async function load() {
  if (!dates.value || dates.value.length !== 2 || !dates.value[0] || !dates.value[1]) return
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const response = await availability(dates.value[0], dates.value[1])
    if (current === generation) grid.value = response
  } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '可售查询失败'; grid.value = null } }
  finally { if (current === generation) loading.value = false }
}
watch(dates, () => void load(), { immediate: false })
onMounted(() => { void load() })

function cellText(night: NightAvailability | undefined): string {
  if (!night) return ''
  if (night.blocked > 0) return `停售`
  return `${night.available}`
}
function cellType(night: NightAvailability | undefined): 'danger' | 'warning' | 'success' | 'info' {
  if (!night) return 'info'
  if (night.blocked > 0) return 'danger'
  if (night.available <= 0) return 'warning'
  return 'success'
}
const columns = computed(() => nights.value.map(night => ({ date: night.date, weekday: '周' + '日一二三四五六'[new Date(`${night.date}T12:00:00+08:00`).getDay()] })))

// ---- 停售管理（总经理） ----
const manageBlocks = computed(() => can('inventory:manage'))
const types = ref<RoomType[]>([])
const blocks = ref<PageResult<BlockView> | null>(null), blocksLoading = ref(false), includeReleased = ref(false), blocksPage = ref(1)
const blockVisible = ref(false), blockBusy = ref(false), blockError = ref('')
const blockForm = ref({ roomTypeId: '' as number | '', startDate: '', endDate: '', reason: '' })
async function loadBlocks() {
  if (!manageBlocks.value) return
  blocksLoading.value = true
  try { blocks.value = await listBlocks(includeReleased.value, blocksPage.value) }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '停售记录加载失败') }
  finally { blocksLoading.value = false }
}
async function loadTypes() {
  try {
    const list: RoomType[] = []
    let page = 1
    while (true) {
      const result = await api<PageResult<RoomType>>(`/api/room-types?page=${page++}&pageSize=100`)
      list.push(...result.items)
      if (list.length >= result.total || result.items.length === 0) break
    }
    types.value = list.filter(item => item.enabled)
  } catch { /* 建停售单时再提示 */ }
}
function openBlock() {
  blockForm.value = { roomTypeId: '', startDate: dates.value?.[0] ?? '', endDate: dates.value?.[1] ?? '', reason: '' }
  blockError.value = ''; blockVisible.value = true
  if (types.value.length === 0) void loadTypes()
}
async function submitBlock() {
  blockError.value = ''
  if (!blockForm.value.roomTypeId) { blockError.value = '请选择房型'; return }
  if (!blockForm.value.startDate || !blockForm.value.endDate) { blockError.value = '请选择停售区间（结束日不含）'; return }
  if (!blockForm.value.reason.trim()) { blockError.value = '请填写停售原因'; return }
  blockBusy.value = true
  try {
    await createBlock(reservationKey(), Number(blockForm.value.roomTypeId), blockForm.value.startDate, blockForm.value.endDate, blockForm.value.reason.trim())
    blockVisible.value = false
    ElMessage.success('停售已生效：区间内可售量归零，不影响在住客人')
    void load(); void loadBlocks()
  } catch (cause) { blockError.value = cause instanceof ApiError ? cause.message : '停售创建失败，请稍后重试' }
  finally { blockBusy.value = false }
}
const releasing = ref(0)
async function doRelease(block: BlockView) {
  releasing.value = block.id
  try {
    await releaseBlock(block.id, reservationKey())
    ElMessage.success('停售已解除，可售量恢复')
    void load(); void loadBlocks()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '解除停售失败') }
  finally { releasing.value = 0 }
}
</script>

<template>
  <section class="rooms-page stay-page">
    <div class="page-heading">
      <div><p class="eyebrow muted">AVAILABILITY</p><h1>可售查询与库存</h1><p class="subtle">可售 = 已启用房间 − 在住占用 − 已售预订；停售夜晚整类归零；[到店, 离店) 半开区间。</p></div>
      <el-button v-if="manageBlocks" type="primary" @click="openBlock">新建停售</el-button>
    </div>
    <form class="room-filters stay-list-filters" @submit.prevent="load()">
      <div class="filter-field"><label>查询区间（结束日不含）</label><el-date-picker v-model="dates" type="daterange" aria-label="查询区间" value-format="YYYY-MM-DD" start-placeholder="开始日" end-placeholder="结束日（不含）" /></div>
      <div class="filter-actions"><el-button native-type="submit" :loading="loading">查询</el-button></div>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel">
      <div class="table-caption"><h2>未来库存网格</h2><span class="subtle" v-if="grid">{{ grid.arrivalDate }} → {{ grid.departureDate }}（结束日不含）</span></div>
      <div class="availability-scroll">
        <table class="availability-grid" aria-label="未来库存网格">
          <thead><tr><th class="type-column">房型</th><th v-for="column in columns" :key="column.date" class="night-column"><div>{{ column.date.slice(5) }}</div><div class="subtle">{{ column.weekday }}</div></th></tr></thead>
          <tbody>
            <tr v-for="typeItem in grid?.roomTypes ?? []" :key="typeItem.roomTypeId">
              <td class="type-column"><strong>{{ typeItem.typeName }}</strong><div class="subtle">{{ typeItem.typeCode }} · 整段可订 {{ typeItem.bookable }}</div></td>
              <td v-for="night in typeItem.nights" :key="night.date" class="night-cell">
                <el-tag :type="cellType(night)" effect="plain" size="small">{{ cellText(night) }}</el-tag>
                <div class="subtle night-detail">占{{ night.occupied }} 售{{ night.sold }}<span v-if="night.blocked > 0"> 停{{ night.blocked }}</span></div>
              </td>
            </tr>
            <tr v-if="(grid?.roomTypes.length ?? 0) === 0 && !loading"><td class="subtle" style="padding: 16px">当前没有已启用房型</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="manageBlocks" v-loading="blocksLoading" class="catalog-panel">
      <div class="table-caption">
        <h2>停售计划</h2>
        <div><el-checkbox v-model="includeReleased" @change="blocksPage = 1; loadBlocks()">显示已解除</el-checkbox></div>
      </div>
      <el-table :data="blocks?.items ?? []" empty-text="暂无停售计划" style="width: 100%">
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column label="房型" min-width="150"><template #default="{ row }">{{ row.roomTypeName }}（{{ row.roomTypeCode }}）</template></el-table-column>
        <el-table-column label="停售区间" min-width="170"><template #default="{ row }">{{ row.startDate }} → {{ row.endDate }}（结束日不含）</template></el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.releasedAt ? 'info' : 'danger'" effect="plain">{{ row.releasedAt ? '已解除' : '生效中' }}</el-tag></template></el-table-column>
        <el-table-column label="创建" min-width="160"><template #default="{ row }">{{ row.createdByName }} · {{ localTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button v-if="!row.releasedAt" text type="primary" :loading="releasing === row.id" @click="doRelease(row as BlockView)">解除停售</el-button><span v-else class="subtle">{{ row.releasedByName }}</span></template></el-table-column>
      </el-table>
      <el-pagination v-if="blocks && blocks.total > 20" class="room-pagination" layout="prev, pager, next" :total="blocks.total" :page-size="20" v-model:current-page="blocksPage" @current-change="loadBlocks()" />
    </div>

    <el-dialog v-model="blockVisible" title="新建停售" width="460px" @closed="blockError = ''">
      <el-alert v-if="blockError" :title="blockError" type="error" :closable="false" show-icon />
      <p class="subtle">停售是房型级的计划性停卖：区间内可售量归零，但不影响已在住客人和既有预订之外的事实。</p>
      <el-form label-width="110px" @submit.prevent="submitBlock">
        <el-form-item label="房型" required><el-select v-model="blockForm.roomTypeId" aria-label="停售房型" style="width: 100%" placeholder="选择房型"><el-option v-for="item in types" :key="item.id" :label="`${item.typeName}（${item.typeCode}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="停售区间" required><el-date-picker v-model="blockForm.startDate" type="date" value-format="YYYY-MM-DD" aria-label="停售开始日" style="width: 47%" /><span class="subtle"> → </span><el-date-picker v-model="blockForm.endDate" type="date" value-format="YYYY-MM-DD" aria-label="停售结束日（不含）" style="width: 47%" /></el-form-item>
        <el-form-item label="原因" required><el-input v-model="blockForm.reason" maxlength="500" type="textarea" :rows="2" placeholder="如计划装修" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="blockVisible = false">取消</el-button><el-button type="primary" :loading="blockBusy" @click="submitBlock">创建停售</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.availability-scroll { overflow-x: auto; }
.availability-grid { border-collapse: collapse; width: 100%; }
.availability-grid th, .availability-grid td { border: 1px solid var(--el-border-color-lighter, #e4e7ed); padding: 8px; text-align: center; }
.type-column { min-width: 170px; text-align: left; }
.night-column { min-width: 76px; }
.night-cell { min-width: 76px; }
.night-detail { margin-top: 4px; font-size: 12px; }
</style>
