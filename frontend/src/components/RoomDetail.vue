<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDrawer, ElDescriptions, ElDescriptionsItem } from 'element-plus'
import { api } from '../api/client'
import type { Room } from '../types/room'
import { availabilityNames, cleaningNames, floorName, localTime } from '../api/rooms'

const model = defineModel<number | null>({ required: true })
const room = ref<Room | null>(null), error = ref(''), loading = ref(false)
let generation = 0
async function load() {
  const id = model.value, current = ++generation
  room.value = null; error.value = ''
  if (id === null) { loading.value = false; return }
  loading.value = true
  try { const result = await api<Room>(`/api/rooms/${id}`); if (current === generation) room.value = result }
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
          <el-descriptions-item label="阻止入住的异常">{{ room.hasBlockingIssue ? '存在' : '无' }}</el-descriptions-item>
          <el-descriptions-item label="房型状态">{{ room.roomTypeEnabled ? '启用' : '已停用（保留现有关联）' }}</el-descriptions-item>
          <el-descriptions-item label="房间备注"><span class="room-notes">{{ room.notes || '暂无备注' }}</span></el-descriptions-item>
          <el-descriptions-item label="内部编号">{{ room.id }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ localTime(room.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ localTime(room.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </el-drawer>
</template>
