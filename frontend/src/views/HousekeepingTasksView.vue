<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElTable, ElTableColumn, ElPagination, ElSelect, ElOption, ElDrawer, ElDescriptions, ElDescriptionsItem } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import type { PageResult } from '../types/room'
import type { HousekeepingTask } from '../types/housekeeping'
const roomNumber = ref(''), floor = ref<number>(), floors = ref<{ floor: number; roomCount: number }[]>([])
const page = ref(1), loading = ref(false), error = ref(''), result = ref<PageResult<HousekeepingTask> | null>(null)
const detail = ref<HousekeepingTask | null>(null), drawer = ref(false), detailLoading = ref(false), detailError = ref('')
let generation = 0, detailGeneration = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page.value), pageSize: '20', roomNumber: roomNumber.value, status: 'PENDING' })
  if (floor.value !== undefined) query.set('floor', String(floor.value))
  try { const response = await api<PageResult<HousekeepingTask>>(`/api/housekeeping-tasks?${query}`); if (current === generation) result.value = response }
  catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '任务查询失败'; result.value = null } }
  finally { if (current === generation) loading.value = false }
}
async function show(id: number) {
  const current = ++detailGeneration; drawer.value = true; detail.value = null; detailError.value = ''; detailLoading.value = true
  try { const response = await api<HousekeepingTask>(`/api/housekeeping-tasks/${id}`); if (current === detailGeneration) detail.value = response }
  catch (cause) { if (current === detailGeneration) detailError.value = cause instanceof Error ? cause.message : '详情查询失败' }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
onMounted(async () => {
  void load()
  try { floors.value = await api<{ floor: number; roomCount: number }[]>('/api/floors') }
  catch { error.value = '楼层选项加载失败，请刷新页面重试' }
})
</script>
<template>
  <section class="rooms-page"><div class="page-heading"><div><p class="eyebrow muted">HOUSEKEEPING</p><h1>待处理清洁任务</h1><p class="subtle">退房后自动生成；本阶段仅供查询，后续阶段开放清洁处理。</p></div><el-button :loading="loading" @click="load()">刷新任务</el-button></div>
    <form class="room-filters" @submit.prevent="load(true)"><div class="filter-field"><label for="task-room">任务房号搜索</label><el-input id="task-room" v-model="roomNumber" clearable maxlength="20" /></div><div class="filter-field"><label>任务楼层</label><el-select v-model="floor" aria-label="任务楼层" clearable placeholder="全部楼层"><el-option v-for="item in floors" :key="item.floor" :label="`${item.floor} 楼`" :value="item.floor" /></el-select></div><div class="filter-actions"><el-button native-type="submit" :loading="loading">查询任务</el-button><el-button @click="roomNumber = ''; floor = undefined; load(true)">重置</el-button></div></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel"><div class="table-caption"><h2>退房清洁</h2><span class="subtle">共 {{ result?.total ?? '—' }} 条待处理任务</span></div>
      <el-table :data="result?.items ?? []" empty-text="暂无待处理清洁任务" style="width: 100%"><el-table-column prop="roomNumber" label="房号" min-width="100" /><el-table-column prop="floor" label="楼层" width="80" /><el-table-column prop="roomTypeName" label="房型" min-width="150" /><el-table-column label="任务类型" min-width="110"><template #default>退房清洁</template></el-table-column><el-table-column label="任务状态" min-width="100"><template #default><el-tag type="warning">待处理</el-tag></template></el-table-column><el-table-column label="创建时间" min-width="190"><template #default="{ row }">{{ localTime((row as HousekeepingTask).createdAt) }}</template></el-table-column><el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button text @click="show((row as HousekeepingTask).id)">任务详情</el-button></template></el-table-column></el-table>
      <el-pagination v-if="result && result.total > 20" class="room-pagination" layout="prev, pager, next" :total="result.total" :page-size="20" v-model:current-page="page" @current-change="load()" />
    </div>
    <el-drawer v-model="drawer" title="清洁任务详情" size="min(520px, 94vw)"><div v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" /><el-descriptions v-if="detail" :column="1" border><el-descriptions-item label="任务编号">#{{ detail.id }}</el-descriptions-item><el-descriptions-item label="房号">{{ detail.roomNumber }}</el-descriptions-item><el-descriptions-item label="楼层">{{ detail.floor }} 楼</el-descriptions-item><el-descriptions-item label="房型">{{ detail.roomTypeName }}</el-descriptions-item><el-descriptions-item label="任务类型">退房清洁</el-descriptions-item><el-descriptions-item label="任务来源">退房自动创建</el-descriptions-item><el-descriptions-item label="任务状态">待处理</el-descriptions-item><el-descriptions-item label="创建时间">{{ localTime(detail.createdAt) }}</el-descriptions-item><el-descriptions-item label="任务备注">{{ detail.notes || '—' }}</el-descriptions-item></el-descriptions></div><template #footer><el-button @click="drawer = false">关闭任务详情</el-button></template></el-drawer>
  </section>
</template>
