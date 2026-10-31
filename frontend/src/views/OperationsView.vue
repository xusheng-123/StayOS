<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { operations, refreshOperations, type Notice } from '../stores/operations'
import { localTime } from '../api/rooms'
import { can } from '../stores/auth'
const labels = { OVERDUE_STAY: '超过预计退房时间', STRANDED_TASK: '负责人已禁用或失去执行资格', DELAYED_TASK: '清洁任务处理较久', CRITICAL_ISSUE: '尚未解决的严重异常' }
const metrics = computed(() => [
  { label: '超预计退房', value: operations.data?.overdueStays }, { label: '未分配任务', value: operations.data?.unassignedTasks },
  { label: '处理较久任务', value: operations.data?.delayedTasks }, { label: '需接管任务', value: operations.data?.strandedTasks },
  { label: '严重异常', value: operations.data?.criticalIssues }, { label: '人工维修停用', value: operations.data?.maintenanceStops },
].filter(x => x.value !== null && x.value !== undefined))
function target(n: Notice) { return n.kind === 'OVERDUE_STAY' ? `/stays/${n.targetId}` : n.kind === 'CRITICAL_ISSUE' ? `/room-issues?roomNumber=${encodeURIComponent(n.roomNumber)}` : `/housekeeping/tasks?taskId=${n.targetId}` }
onMounted(() => { if (!operations.data) void refreshOperations() })
</script>
<template>
 <section class="rooms-page operations-page"><div class="page-heading"><div><p class="eyebrow muted">SHIFT OVERVIEW</p><h1>运营待办与交班</h1><p class="subtle">按当前岗位汇总未完成事项，交班时逐项核对。提醒不会自动退房、接管或解除停用。</p></div><el-button :loading="operations.loading" @click="refreshOperations">刷新待办</el-button></div>
 <el-alert v-if="operations.error" :title="operations.error" type="error" :closable="false" />
 <div v-loading="operations.loading"><div class="ops-metrics"><article v-for="m in metrics" :key="m.label" class="room-metric"><span>{{ m.label }}</span><strong>{{ m.value }}</strong></article></div>
 <p v-if="operations.data" class="subtle">最近更新：{{ localTime(operations.data.generatedAt) }}。活跃页面每 30 秒重新查询，页面隐藏或无操作 1 分钟后暂停。</p>
 <p v-if="operations.data" class="subtle">提醒阈值：待处理超过 {{ operations.data.pendingWarningMinutes }} 分钟、清洁中超过 {{ operations.data.cleaningWarningMinutes }} 分钟；仅为运营提示，不代表员工违规。每类最多展示 20 项，数量为完整统计。</p>
 <div class="ops-items"><router-link v-for="n in operations.data?.items" :key="`${n.kind}-${n.targetId}`" :to="target(n)" class="catalog-panel ops-item"><el-tag :type="n.kind === 'CRITICAL_ISSUE' ? 'danger' : 'warning'">{{ labels[n.kind] }}</el-tag><h2>{{ n.roomNumber }} 房</h2><p>{{ localTime(n.since) }}</p><span>查看并处理 →</span></router-link></div>
 <p v-if="operations.data && !operations.data.items.length">当前授权范围内没有需提醒的异常事项。</p>
 <div class="ops-links"><router-link v-if="can('housekeeping:manage')" to="/housekeeping/assignment"><el-button>处理未分配房间</el-button></router-link><router-link v-if="can('housekeeping:read')" to="/housekeeping/tasks"><el-button>查看清洁任务</el-button></router-link><router-link v-if="can('issue:report')" to="/room-issues"><el-button>查看房间异常</el-button></router-link><router-link v-if="can('stay:manage')" to="/stays"><el-button>查看当前入住</el-button></router-link></div>
 </div></section>
</template>
<style scoped>.ops-metrics { display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:12px }.ops-items { display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:16px;margin:20px 0 }.ops-item { color:inherit;text-decoration:none;overflow-wrap:anywhere }.ops-item h2 { margin-bottom:8px }.ops-links { display:flex;flex-wrap:wrap;gap:12px }.ops-links .el-button { min-height:44px }</style>
