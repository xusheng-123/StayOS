<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElPagination } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import type { PageResult } from '../types/room'
import type { StayExtension } from '../types/stay'

const props = defineProps<{ stayId: number; version: number }>()
const items = ref<StayExtension[]>([]), total = ref(0), page = ref(1), loading = ref(false), error = ref('')
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const response = await api<PageResult<StayExtension>>(`/api/stays/${props.stayId}/extensions?page=${page.value}&pageSize=10`)
    if (current === generation) { items.value = response.items; total.value = response.total }
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '续住历史加载失败' }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.stayId, props.version], () => { page.value = 1; items.value = []; total.value = 0; void load() }, { immediate: true })
</script>
<template>
  <section aria-label="续住与预计离店调整历史" class="extension-history" v-loading="loading">
    <h2 class="stay-guests-title">续住与预计离店调整历史</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-button v-if="error" class="retry-button" @click="load">重试加载续住历史</el-button>
    <article v-for="item in items" :key="item.id" class="extension-history-item">
      <strong>#{{ item.id }} · {{ item.roomNumber }}</strong>
      <p>原预计退房：{{ localTime(item.oldExpectedCheckOutAt) }}</p>
      <p>新预计退房：{{ localTime(item.newExpectedCheckOutAt) }}</p>
      <p>{{ localTime(item.operatedAt) }} · {{ item.operatorName }}</p>
      <p class="room-notes">调整原因：{{ item.reason }}</p>
    </article>
    <p v-if="!loading && !error && !items.length" class="subtle">本次入住暂无续住或预计离店调整记录。</p>
    <el-pagination v-if="total > 10" small layout="prev, pager, next" :total="total" :page-size="10" v-model:current-page="page" @current-change="load" />
  </section>
</template>
<style scoped>
.extension-history { min-height: 70px; }
.extension-history-item { margin: 12px 0; padding: 14px; border: 1px solid #e1e8df; border-radius: 8px; overflow-wrap: anywhere; }
.extension-history-item p { margin: 8px 0; }
</style>
