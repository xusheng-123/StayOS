<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElPagination, ElEmpty } from 'element-plus'
import { api } from '../api/client'
import { can } from '../stores/auth'
import { availabilityNames, cleaningNames, floorName, roomOptions, roomQuery } from '../api/rooms'
import type { RoomBoard, RoomFilters as Filters, RoomType, Floor, RoomStatistics } from '../types/room'
import RoomFilters from '../components/RoomFilters.vue'
import RoomDetail from '../components/RoomDetail.vue'

const filters = ref<Filters>({ roomNumber: '' }), page = ref(1)
const types = ref<RoomType[]>([]), floors = ref<Floor[]>([]), board = ref<RoomBoard | null>(null)
const loading = ref(false), error = ref(''), detail = ref<number | null>(null), updated = ref('')
const metrics: { key: keyof RoomStatistics; label: string; state: string }[] = [
  { key: 'total', label: '客房总数', state: 'total' }, { key: 'available', label: '空闲可入住', state: 'VACANT_CLEAN' },
  { key: 'occupied', label: '已入住', state: 'OCCUPIED' }, { key: 'dirty', label: '待打扫', state: 'DIRTY' },
  { key: 'cleaning', label: '正在打扫', state: 'CLEANING' }, { key: 'inspection', label: '待检查', state: 'INSPECTION' },
  { key: 'outOfService', label: '维修停用', state: 'OUT_OF_SERVICE' }, { key: 'disabled', label: '档案停用', state: 'DISABLED' },
]
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true; error.value = ''
  try {
    const result = await api<RoomBoard>(`/api/room-status?${roomQuery(filters.value, page.value, 24)}`)
    if (current === generation) {
      board.value = result
      updated.value = new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
    }
  } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '房态加载失败'; board.value = null } }
  finally { if (current === generation) loading.value = false }
}
function search() { page.value = 1; void load() }
function reset() { filters.value = { roomNumber: '' }; search() }
async function refresh() {
  try { const options = await roomOptions(); types.value = options.types; floors.value = options.floors; await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '筛选选项加载失败' }
}
function changePage(value: number) { page.value = value; void load() }
onMounted(refresh)
</script>

<template>
  <section class="rooms-page">
    <div class="page-heading"><div><p class="eyebrow muted">ROOM OVERVIEW</p><h1>房态图</h1><p class="subtle">前台与客房部，共享同一份房间信息。</p></div><div class="heading-actions"><router-link v-if="can('room:manage')" to="/manage/rooms"><el-button>管理房间</el-button></router-link><el-button :loading="loading" @click="refresh">刷新房态</el-button></div></div>
    <RoomFilters v-model="filters" :types="types" :floors="floors" :loading="loading" @search="search" @reset="reset" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-button v-if="error" class="retry-button" @click="refresh">重试</el-button>
    <div v-loading="loading" class="board-content">
      <div class="room-statistics" aria-label="房态统计"><div v-for="item in metrics" :key="item.key" :class="['room-metric', 'state-' + item.state]"><span>{{ item.label }}</span><strong :data-testid="'stat-' + item.key">{{ board?.statistics[item.key] ?? '—' }}</strong></div></div>
      <div class="board-caption"><p>当前筛选共 <strong>{{ board?.total ?? '—' }}</strong> 间<span class="subtle"> · 状态可能重叠，统计数量不应相加</span></p><span v-if="updated" class="subtle">最近刷新 {{ updated }}</span></div>
      <div v-if="board?.items.length" class="room-card-grid">
        <button v-for="room in board.items" :key="room.id" :class="['room-card', 'state-' + room.availability]" :aria-label="'查看房间 ' + room.roomNumber" @click="detail = room.id">
          <div class="room-card-top"><span class="room-floor">{{ floorName(room.floor) }}</span><span class="room-card-arrow">↗</span></div>
          <strong class="room-number">{{ room.roomNumber }}</strong><span class="room-type-name">{{ room.roomTypeName }}</span>
          <span class="room-status-pill">{{ availabilityNames[room.availability] }}</span>
          <span class="room-card-flags">{{ room.occupied ? '已入住' : '空闲' }} · {{ cleaningNames[room.cleaningStatus] }}<template v-if="room.hasBlockingIssue"> · 有阻止异常</template><template v-if="!room.enabled"> · 档案停用</template></span>
        </button>
      </div>
      <el-empty v-else-if="!loading && !error" :description="types.length ? '没有符合条件的房间' : '暂无房间，请管理员先维护房型和房间'" />
      <el-pagination v-if="board && board.total > 24" class="room-pagination" layout="prev, pager, next" :total="board.total" :page-size="24" :current-page="page" @current-change="changePage" />
    </div>
    <RoomDetail v-model="detail" />
  </section>
</template>
