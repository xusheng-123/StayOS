<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { can } from '../stores/auth'
import { ElEmpty, ElPagination } from 'element-plus'
import { folioList } from '../api/billing'
import { localTime } from '../api/rooms'
import { folioStatusNames, type FolioListItem } from '../types/billing'

const router = useRouter()
const status = ref(''), page = ref(1)
const list = ref<FolioListItem[]>(), total = ref(0), loading = ref(false), error = ref('')
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await folioList(status.value, page.value)
    if (current === generation) { list.value = data.items; total.value = data.total }
  } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '客账加载失败，请稍后重试'; list.value = undefined } }
  finally { if (current === generation) loading.value = false }
}
// Positive balance is what the guest still owes; negative means prepaid remainder after refunds/deposits.
const balanceLabel = (value: string) => value.startsWith('-') ? `预收剩余 ${value.slice(1)}` : `未付 ${value}`
onMounted(() => { if (can('billing:read')) void load(true) })
</script>

<template>
 <section class="rooms-page billing-page">
  <div class="page-heading"><div><p class="eyebrow muted">FOLIOS</p><h1>账务</h1><p class="subtle">每个入住一份客账：费用、实收、预收剩余与未付余额分项展示，不合并为单一收入口径。</p></div><el-button :loading="loading" @click="load()">刷新客账</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <form class="billing-filters catalog-panel" @submit.prevent="load(true)">
   <label>结账状态<select v-model="status" aria-label="结账状态"><option value="">全部客账</option><option value="OPEN">未结账</option><option value="CLOSED">已结账</option></select></label>
   <el-button native-type="submit" type="primary" :loading="loading">查询客账</el-button>
  </form>
  <div v-loading="loading" class="catalog-panel">
   <div class="table-caption"><h2>客账列表</h2><span class="subtle">{{ total }} 份</span></div>
   <el-empty v-if="list && !list.length" description="当前条件下没有客账" />
   <button v-for="item in list" :key="item.id" class="folio-row" :aria-label="`查看客账 ${item.id}`" @click="router.push(`/billing/folios/${item.id}`)">
    <span><strong>#{{ item.id }} · {{ item.roomNumber }}</strong><small>{{ item.primaryGuestName }}</small></span>
    <span class="subtle">应收 {{ item.charges }}<small>实收 {{ item.netReceived }}</small></span>
    <span :class="['folio-balance', { owed: !item.balance.startsWith('-') }]">{{ balanceLabel(item.balance) }}<small>{{ item.status === 'OPEN' ? '开账于 ' + localTime(item.checkInAt) : '结账于 ' + localTime(item.closedAt || '') }}</small></span>    <el-tag :type="item.status === 'OPEN' ? 'warning' : 'success'">{{ folioStatusNames[item.status] }}</el-tag>
   </button>
   <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load()" />
  </div>
 </section>
</template>
<style scoped>
.billing-page { min-width: 0; }
.billing-filters { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; margin-bottom: 20px; align-items: end; }
.billing-filters label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.billing-filters select { font: inherit; color: inherit; min-height: 42px; border: 1px solid #cbd6ce; border-radius: 6px; padding: 9px; background: #fff; }
.folio-row { font: inherit; text-align: left; background: transparent; border: 0; border-bottom: 1px solid #e9eee8; width: 100%; padding: 16px 0; display: grid; grid-template-columns: 1.4fr 1.2fr 1.2fr auto; gap: 12px; cursor: pointer; color: inherit; }
.folio-row span { min-width: 0; overflow-wrap: anywhere; }
.folio-row strong, .folio-row small { display: block; }
.folio-row small { color: #788278; margin-top: 5px; }
.folio-balance.owed { color: #b3590f; font-weight: 600; }
.folio-balance { color: #256029; font-weight: 600; }
.folio-balance small { color: #788278; font-weight: 400; margin-top: 5px; }
@media (max-width: 750px) {
 .billing-filters { grid-template-columns: minmax(0, 1fr); }
 .folio-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
 .folio-row :deep(.el-tag) { justify-self: start; max-width: 100%; }
}
</style>
