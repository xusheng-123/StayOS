<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElDatePicker, ElTable, ElTableColumn, ElPagination } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import CheckoutDialog from '../components/CheckoutDialog.vue'
import type { Stay } from '../types/stay'
import type { PageResult } from '../types/room'
const route = useRoute(), history = computed(() => route.path === '/stays/history')
const roomNumber = ref(''), guestName = ref(''), dates = ref<string[]>([]), page = ref(1), result = ref<PageResult<Stay> | null>(null), loading = ref(false), error = ref('')
const checkout = ref<InstanceType<typeof CheckoutDialog> | null>(null)
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page.value), pageSize: '20', roomNumber: roomNumber.value, guestName: guestName.value })
  if (dates.value?.length === 2) { query.set('checkInFrom', `${dates.value[0]}T00:00:00+08:00`); query.set('checkInTo', `${dates.value[1]}T00:00:00+08:00`) }
  try { const response = await api<PageResult<Stay>>(`/api/stays${history.value ? '/history' : ''}?${query}`); if (current === generation) result.value = response }
  catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '入住记录加载失败'; result.value = null } }
  finally { if (current === generation) loading.value = false }
}
watch(() => route.path, () => { page.value = 1; roomNumber.value = ''; guestName.value = ''; dates.value = []; void load() }, { immediate: true })
</script>
<template>
  <section class="rooms-page stay-page"><div class="page-heading"><div><p class="eyebrow muted">STAY RECORDS</p><h1>{{ history ? '历史入住' : '当前入住' }}</h1><p class="subtle">{{ history ? '已退房的入住与住客记录保留在此，便于查询。' : '有效入住不会因超过预计退房时间而自动结束。' }}</p></div><router-link to="/stays/new"><el-button type="primary">办理入住</el-button></router-link></div>
    <form class="room-filters stay-list-filters" @submit.prevent="load(true)"><div class="filter-field"><label for="stay-room">房号搜索</label><el-input id="stay-room" v-model="roomNumber" maxlength="20" clearable /></div><div class="filter-field"><label for="stay-guest">住客姓名</label><el-input id="stay-guest" v-model="guestName" maxlength="100" clearable /></div><div class="filter-field"><label>入住时间范围（北京时间，结束日不含）</label><el-date-picker v-model="dates" type="daterange" aria-label="入住时间范围" value-format="YYYY-MM-DD" start-placeholder="开始日" end-placeholder="结束日（不含）" /></div><div class="filter-actions"><el-button native-type="submit" :loading="loading">查询入住</el-button><el-button @click="roomNumber = ''; guestName = ''; dates = []; load(true)">重置</el-button></div></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel"><div class="table-caption"><h2>{{ history ? '历史记录' : '在住记录' }}</h2><span class="subtle">共 {{ result?.total ?? '—' }} 条</span></div>
      <el-table :data="result?.items ?? []" empty-text="暂无入住记录" style="width: 100%"><el-table-column prop="roomNumber" label="房号" min-width="95" /><el-table-column prop="roomTypeName" label="房型" min-width="140" /><el-table-column prop="primaryGuestName" label="主要联系人" min-width="120" /><el-table-column prop="guestCount" label="人数" width="70" /><el-table-column label="实际入住" min-width="185"><template #default="{ row }">{{ localTime((row as Stay).checkInAt) }}</template></el-table-column><el-table-column label="预计退房" min-width="185"><template #default="{ row }">{{ localTime((row as Stay).expectedCheckOutAt) }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="(row as Stay).status === 'IN_HOUSE' ? 'primary' : 'info'">{{ (row as Stay).status === 'IN_HOUSE' ? '在住' : '已退房' }}</el-tag></template></el-table-column><el-table-column prop="operatorName" label="经办人员" min-width="140" /><el-table-column label="操作" :width="history ? 100 : 190" fixed="right"><template #default="{ row }"><router-link :to="`/stays/${(row as Stay).id}`"><el-button text>查看详情</el-button></router-link><el-button v-if="!history" text type="primary" @click="checkout?.open(row as Stay)">办理退房</el-button></template></el-table-column></el-table>
      <el-pagination v-if="result && result.total > 20" class="room-pagination" layout="prev, pager, next" :total="result.total" :page-size="20" v-model:current-page="page" @current-change="load()" />
    </div>
    <CheckoutDialog ref="checkout" @completed="load()" />
  </section>
</template>
