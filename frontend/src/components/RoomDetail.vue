<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDrawer, ElDescriptions, ElDescriptionsItem } from 'element-plus'
import { can } from '../stores/auth'
import type { RoomIssue, MaintenanceStop } from '../types/issue'
import { issueStates, severities } from '../types/issue'
import type { PageResult } from '../types/room'
import { api } from '../api/client'
import type { Room } from '../types/room'
import { availabilityNames, cleaningNames, floorName, localTime, blockedReasons } from '../api/rooms'

const model = defineModel<number | null>({ required: true })
const room = ref<Room | null>(null), error = ref(''), loading = ref(false)
const issues = ref<RoomIssue[]>([]), stops = ref<MaintenanceStop[]>([]), issueError = ref('')
let generation = 0
async function load() {
  const id = model.value, current = ++generation
  room.value = null; error.value = ''; issues.value=[]; stops.value=[]; issueError.value=''
  if (id === null) { loading.value = false; return }
  loading.value = true
  try { const result = await api<Room>(`/api/rooms/${id}`); if (current === generation) room.value = result
    if(can('issue:report')) {
      try { const [i,m]=await Promise.all([api<PageResult<RoomIssue>>(`/api/room-issues?roomId=${id}&pageSize=5`),api<MaintenanceStop[]>(`/api/rooms/${id}/maintenance-history`)]);if(current===generation) { issues.value=i.items;stops.value=m } }
      catch(e) { if(current===generation) issueError.value=e instanceof Error?e.message:'异常记录加载失败' }
    } }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '房间详情加载失败' }
  finally { if (current === generation) loading.value = false }
}
watch(model, load, { immediate: true })
</script>

<template>
  <el-drawer :model-value="model !== null" title="房间详情" size="min(500px, 100vw)" @update:model-value="value => { if (!value) model = null }">
    <div v-loading="loading" class="detail-content">
      <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" class="retry-button" @click="load">重试</el-button>
      <template v-if="room">
        <div class="detail-room-heading"><span class="detail-number">{{ room.roomNumber }}</span><span :class="['room-status-pill', 'state-' + room.availability]">{{ availabilityNames[room.availability] }}</span></div>
        <p class="subtle">{{ floorName(room.floor) }} · {{ room.roomTypeName }}</p>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="是否可入住">{{ room.canCheckIn ? '可安排入住' : '不可安排入住' }}</el-descriptions-item>
          <el-descriptions-item label="占用状态">{{ room.occupied ? '已入住' : '空闲' }}</el-descriptions-item>
          <el-descriptions-item label="清洁状态">{{ cleaningNames[room.cleaningStatus] }}</el-descriptions-item>
          <el-descriptions-item label="维修状态">{{ room.maintenanceStatus === 'NORMAL' ? '正常' : '维修停用' }}</el-descriptions-item>
          <el-descriptions-item label="档案状态">{{ room.enabled ? '启用' : '停用' }}</el-descriptions-item>
          <el-descriptions-item label="未完成清洁任务">{{ room.hasOpenCleaningTask ? '存在' : '无' }}</el-descriptions-item>
          <el-descriptions-item label="维修阻止条件">{{ room.hasBlockingIssue ? '存在' : '无' }}</el-descriptions-item>
          <el-descriptions-item label="不可入住原因">{{ blockedReasons(room).join('；') || (room.canCheckIn ? '无' : '请刷新房态后核查') }}</el-descriptions-item>
          <el-descriptions-item label="房型状态">{{ room.roomTypeEnabled ? '启用' : '已停用（保留现有关联）' }}</el-descriptions-item>
          <el-descriptions-item label="房间备注"><span class="room-notes">{{ room.notes || '暂无备注' }}</span></el-descriptions-item>
          <el-descriptions-item label="内部编号">{{ room.id }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ localTime(room.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ localTime(room.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="can('issue:report')"><h3>房间异常与维修记录</h3><el-alert v-if="issueError" :title="issueError" type="error" :closable="false" /><p v-for="i in issues" :key="i.id" class="room-notes">{{ severities[i.severity] }} · {{ issueStates[i.status] }} · {{ i.description }}</p><p v-if="!issues.length && !issueError" class="subtle">暂无授权范围内的异常记录。</p><p v-for="s in stops" :key="s.id" class="room-notes">{{ s.resumedAt ? '已解除停用' : '有效人工停用' }} · {{ s.reason }}<span v-if="s.resumedAt"> · {{ s.resumeReason }}</span></p><router-link :to="{path:'/room-issues',query:{roomId:room.id}}"><el-button>查看本房间异常记录</el-button></router-link></template>
      </template>
    </div>
  </el-drawer>
</template>
