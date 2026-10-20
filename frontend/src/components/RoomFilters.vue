<script setup lang="ts">
import { ElSelect, ElOption } from 'element-plus'
import type { RoomFilters, RoomType, Floor } from '../types/room'
import { availabilityNames, floorName } from '../api/rooms'

const model = defineModel<RoomFilters>({ required: true })
defineProps<{ types: RoomType[]; floors: Floor[]; loading: boolean }>()
const emit = defineEmits<{ search: []; reset: [] }>()
</script>

<template>
  <form class="room-filters" aria-label="房间筛选" @submit.prevent="emit('search')">
    <div class="filter-field"><label for="filter-number">房号搜索</label><el-input id="filter-number" v-model="model.roomNumber" maxlength="20" placeholder="如 0801、A801" clearable /></div>
    <div class="filter-field"><label id="floor-filter-label">楼层</label><el-select v-model="model.floor" aria-label="楼层" placeholder="全部楼层" clearable @change="emit('search')"><el-option v-for="item in floors" :key="item.floor" :label="floorName(item.floor)" :value="item.floor" /></el-select></div>
    <div class="filter-field"><label id="type-filter-label">房型</label><el-select v-model="model.roomTypeId" aria-label="房型" placeholder="全部房型" clearable @change="emit('search')"><el-option v-for="item in types" :key="item.id" :label="item.typeName + (item.enabled ? '' : '（已停用）')" :value="item.id" /></el-select></div>
    <div class="filter-field"><label id="state-filter-label">综合房态</label><el-select v-model="model.availability" aria-label="综合房态" placeholder="全部房态" clearable @change="emit('search')"><el-option v-for="(name, key) in availabilityNames" :key="key" :label="name" :value="key" /></el-select></div>
    <div class="filter-actions"><el-button native-type="submit" type="primary" :loading="loading">查询</el-button><el-button @click="emit('reset')">重置</el-button></div>
  </form>
</template>
