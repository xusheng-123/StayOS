<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElPagination } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import { correctionFieldNames } from '../types/stay'
import type { GuestCorrection } from '../types/stay'
const props = defineProps<{ stayId: number; guestId: number; version: number }>()
const items = ref<GuestCorrection[]>([]), total = ref(0), page = ref(1), loading = ref(false), error = ref('')
let generation = 0
function display(field: string, value: string) {
  if (field === 'documentType') return ({ ID_CARD: '居民身份证', PASSPORT: '护照', OTHER: '其他证件' } as Record<string, string>)[value] || value
  return value || '未填写'
}
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await api<{ items: GuestCorrection[]; total: number }>(`/api/stays/${props.stayId}/guests/${props.guestId}/corrections?page=${page.value}&pageSize=10`)
    if (current === generation) { items.value = data.items; total.value = data.total }
  } catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '更正历史加载失败' }
  finally { if (current === generation) loading.value = false }
}
watch(() => [props.stayId, props.guestId, props.version], () => { page.value = 1; items.value = []; void load() }, { immediate: true })
</script>
<template>
  <section class="correction-history" v-loading="loading" aria-label="住客更正历史"><h4>资料更正历史</h4>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" @click="load">重试更正历史</el-button>
    <p v-if="!loading && !error && !total" class="subtle">暂无资料更正记录。</p>
    <article v-for="item in items" :key="item.id"><strong>#{{ item.id }} · {{ localTime(item.operatedAt) }} · {{ item.operatorName }}</strong>
      <p v-for="field in item.changedFields" :key="field">{{ correctionFieldNames[field] }}：{{ display(field, item.before[field]) }} → {{ display(field, item.after[field]) }}</p>
      <p class="room-notes">原因：{{ item.reason }}</p>
    </article>
    <el-pagination v-if="total > 10" v-model:current-page="page" :page-size="10" :total="total" layout="prev, pager, next" @current-change="load" />
  </section>
</template>
<style scoped>.correction-history { margin-top: 16px; border-top: 1px solid #e1e8df; overflow-wrap: anywhere; }.correction-history article { margin: 12px 0; padding: 12px; background: #f5f6f2; border-radius: 8px; }.correction-history p { margin: 8px 0; }</style>
