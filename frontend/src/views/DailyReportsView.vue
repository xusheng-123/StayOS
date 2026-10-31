<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { can } from '../stores/auth'
import { ElEmpty, ElPagination } from 'element-plus'
import { dailyReports } from '../api/cashier'
import { localTime } from '../api/rooms'
import type { DailyReportItem } from '../types/cashier'

const from = ref(''), to = ref(''), page = ref(1)
const reports = ref<DailyReportItem[]>(), total = ref(0), loading = ref(false), error = ref('')
const expanded = ref<number | null>(null)
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await dailyReports(from.value.trim(), to.value.trim(), page.value)
    if (current === generation) { reports.value = data.items; total.value = data.total }
  } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '日报加载失败，请稍后重试'; reports.value = undefined } }
  finally { if (current === generation) loading.value = false }
}
function toggle(id: number) { expanded.value = expanded.value === id ? null : id }
// Snapshot values are frozen at close time; money keeps two decimals, counts stay integers, and “数据不足” marks unreliable denominators.
const money = (value: unknown): string => typeof value === 'number' ? value.toFixed(2) : typeof value === 'string' ? value : '—'
const count = (value: unknown): string => typeof value === 'number' ? String(value) : '—'
const rate = (value: unknown): string => typeof value === 'number' ? `${value}%` : '数据不足'
const section = (snapshot: Record<string, unknown>, key: string) => (snapshot[key] && typeof snapshot[key] === 'object' ? snapshot[key] as Record<string, unknown> : {})
const adjustments = (snapshot: Record<string, unknown>) => Array.isArray(snapshot.laterAdjustments) ? snapshot.laterAdjustments as Record<string, unknown>[] : []
const gaps = (snapshot: Record<string, unknown>) => Array.isArray(snapshot.dataGaps) ? snapshot.dataGaps as string[] : []
onMounted(() => { if (can('report:finance')) void load(true) })
</script>

<template>
 <section class="rooms-page reports-page">
  <div class="page-heading"><div><p class="eyebrow muted">DAILY REPORTS</p><h1>营业日报</h1><p class="subtle">日报为关账时点固化的快照：分母不可靠时标记“数据不足”，不做推算；关账后的调整列在当期调整明细中。</p></div><el-button :loading="loading" @click="load()">刷新日报</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <form class="reports-filters catalog-panel" @submit.prevent="load(true)">
   <label>开始日期<input v-model="from" type="date" aria-label="开始日期" /></label>
   <label>结束日期<input v-model="to" type="date" aria-label="结束日期" /></label>
   <el-button native-type="submit" type="primary" :loading="loading">查询日报</el-button>
  </form>
  <div v-loading="loading" class="catalog-panel">
   <div class="table-caption"><h2>日报列表</h2><span class="subtle">{{ total }} 份</span></div>
   <el-empty v-if="reports && !reports.length" description="当前条件下没有日报；日报在营业日关账成功后生成" />
   <div v-for="report in reports" :key="report.id" class="report-row">
    <button class="report-head" :aria-expanded="expanded === report.id" @click="toggle(report.id)">
     <span><strong>{{ report.businessDate }}</strong><small>{{ report.createdByName }} 固化于 {{ localTime(report.createdAt) }}</small></span>
     <span class="subtle">净应收 ¥{{ money(section(report.snapshot, 'charges').netReceivable) }}<small>净收 ¥{{ money(section(report.snapshot, 'payments').netReceived) }}</small></span>
     <span class="subtle">出租率 {{ rate(section(report.snapshot, 'occupancy').occupancyRate) }}<small>ADR {{ section(report.snapshot, 'occupancy').adr === null || section(report.snapshot, 'occupancy').adr === undefined ? '数据不足' : '¥' + money(section(report.snapshot, 'occupancy').adr) }}</small></span>
     <span class="report-toggle">{{ expanded === report.id ? '收起 ▲' : '展开 ▼' }}</span>
    </button>
    <div v-if="expanded === report.id" class="report-body">
     <div class="report-grid">
      <div><h3>费用</h3><p>费用合计 ¥{{ money(section(report.snapshot, 'charges').total) }}</p><p>冲减 ¥{{ money(section(report.snapshot, 'charges').credits) }}</p><p>净应收 ¥{{ money(section(report.snapshot, 'charges').netReceivable) }}</p><p>房晚 {{ count(section(report.snapshot, 'charges').roomNights) }} · 房费收入 ¥{{ money(section(report.snapshot, 'charges').roomRevenue) }}</p></div>
      <div><h3>资金</h3><p>收款 ¥{{ money(section(report.snapshot, 'payments').receipts) }}</p><p>退款 ¥{{ money(section(report.snapshot, 'payments').refunds) }}</p><p>净收 ¥{{ money(section(report.snapshot, 'payments').netReceived) }}</p><p>现金收 ¥{{ money(section(report.snapshot, 'payments').cashIn) }} · 现金退 ¥{{ money(section(report.snapshot, 'payments').cashOut) }}</p></div>
      <div><h3>出租</h3><p>已售房晚 {{ count(section(report.snapshot, 'occupancy').roomNightsSold) }}</p><p>在住房 {{ count(section(report.snapshot, 'occupancy').occupiedRooms) }} / 可售房 {{ count(section(report.snapshot, 'occupancy').availableRooms) }}</p><p>出租率 {{ rate(section(report.snapshot, 'occupancy').occupancyRate) }}</p><p>ADR {{ section(report.snapshot, 'occupancy').adr === null || section(report.snapshot, 'occupancy').adr === undefined ? '数据不足' : '¥' + money(section(report.snapshot, 'occupancy').adr) }}</p></div>
     </div>
     <div v-if="gaps(report.snapshot).length" class="report-gaps">
      <el-alert v-for="gap in gaps(report.snapshot)" :key="gap" :title="gap" type="warning" :closable="false" />
     </div>
     <div class="report-adjust"><h3>当期调整（关账后关联原营业日的记录）</h3>
      <p v-if="!adjustments(report.snapshot).length" class="subtle">无</p>
      <p v-for="(item, index) in adjustments(report.snapshot)" :key="index" class="subtle">#{{ count(item.id) }} {{ item.direction === 'CREDIT' ? '冲减' : '费用' }} ¥{{ money(item.amount) }} · 原营业日 {{ String(item.original_date ?? '—') }}</p>
     </div>
     <p class="subtle report-note">{{ report.snapshot.notes ?? '' }}</p>
    </div>
   </div>
   <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load()" />
  </div>
 </section>
</template>

<style scoped>
.reports-page { min-width: 0; }
.reports-filters { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 12px; margin-bottom: 20px; align-items: end; }
.reports-filters label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.reports-filters input { font: inherit; color: inherit; min-height: 42px; border: 1px solid #cbd6ce; border-radius: 6px; padding: 9px; background: #fff; }
.report-row { border-bottom: 1px solid #e9eee8; }
.report-row:last-of-type { border-bottom: 0; }
.report-head { font: inherit; text-align: left; color: inherit; background: transparent; border: 0; width: 100%; padding: 16px 0; display: grid; grid-template-columns: 1.2fr 1fr 1fr auto; gap: 12px; cursor: pointer; }
.report-head span { min-width: 0; overflow-wrap: anywhere; }
.report-head strong, .report-head small { display: block; }
.report-head small { color: #788278; margin-top: 5px; }
.report-toggle { color: #788278; font-size: 13px; align-self: center; }
.report-body { padding: 0 0 16px; }
.report-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.report-grid h3, .report-adjust h3 { font-size: 13px; color: #788278; margin-bottom: 6px; font-weight: 600; }
.report-grid p, .report-adjust p { margin: 2px 0; font-size: 14px; }
.report-gaps { margin-top: 12px; display: grid; gap: 8px; }
.report-adjust { margin-top: 14px; }
.report-note { margin-top: 12px; font-size: 12px; }
@media (max-width: 750px) {
 .reports-filters { grid-template-columns: minmax(0, 1fr); }
 .report-head { grid-template-columns: repeat(2, minmax(0, 1fr)); }
 .report-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
