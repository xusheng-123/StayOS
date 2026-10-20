<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus'
import { api } from '../api/client'
import { localTime } from '../api/rooms'
import { can } from '../stores/auth'
import RoomChangeDialog from '../components/RoomChangeDialog.vue'
import CheckoutDialog from '../components/CheckoutDialog.vue'
import { changeReasonNames } from '../types/stay'
import type { Stay } from '../types/stay'
const route = useRoute(), stay = ref<Stay | null>(null), error = ref(''), loading = ref(false)
const documentNames = { ID_CARD: '居民身份证', PASSPORT: '护照', OTHER: '其他证件' }
const roomChange = ref<InstanceType<typeof RoomChangeDialog> | null>(null), checkout = ref<InstanceType<typeof CheckoutDialog> | null>(null)
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''; stay.value = null
  try { const result = await api<Stay>(`/api/stays/${String(route.params.id)}`); if (current === generation) stay.value = result }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '详情加载失败' }
  finally { if (current === generation) loading.value = false }
}
watch(() => route.params.id, () => void load(), { immediate: true })
</script>
<template>
  <section class="rooms-page stay-page"><div class="page-heading"><div><p class="eyebrow muted">STAY DETAILS</p><h1>入住详情</h1><p class="subtle">证件与电话仅展示脱敏信息。</p></div><div class="heading-actions"><template v-if="stay?.status === 'IN_HOUSE' && can('stay:manage')"><el-button type="primary" @click="roomChange?.open(stay)">办理换房</el-button><el-button @click="checkout?.open(stay)">办理退房</el-button></template><router-link to="/stays"><el-button>返回当前入住</el-button></router-link><router-link to="/rooms"><el-button>查看房态图</el-button></router-link><el-button :loading="loading" @click="load">刷新详情</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="catalog-panel stay-section"><template v-if="stay"><div class="table-caption"><h2>{{ stay.roomNumber }} · {{ stay.roomTypeName }}</h2><el-tag>{{ stay.status === 'IN_HOUSE' ? '在住' : '已退房' }}</el-tag></div>
      <el-descriptions :column="1" border><el-descriptions-item label="入住记录 ID">{{ stay.id }}</el-descriptions-item><el-descriptions-item label="实际入住时间">{{ localTime(stay.checkInAt) }}</el-descriptions-item><el-descriptions-item label="预计退房时间">{{ localTime(stay.expectedCheckOutAt) }}</el-descriptions-item><el-descriptions-item v-if="stay.actualCheckOutAt" label="实际退房时间">{{ localTime(stay.actualCheckOutAt) }}</el-descriptions-item><el-descriptions-item label="实际登记人数">{{ stay.guestCount }}</el-descriptions-item><el-descriptions-item label="办理员工">{{ stay.operatorName }}</el-descriptions-item><el-descriptions-item label="入住备注"><span class="room-notes">{{ stay.notes || '—' }}</span></el-descriptions-item></el-descriptions>
      <h2 class="stay-guests-title">本次实际住客</h2><div v-for="guest in stay.guests" :key="guest.id" class="stay-guest stay-masked-guest"><div class="table-caption"><h3>{{ guest.name }}</h3><el-tag v-if="guest.primary" type="success">主要联系人</el-tag></div><p>{{ documentNames[guest.documentType] }}：{{ guest.documentNumber }}</p><p>联系电话：{{ guest.phone || '未填写' }}</p><p v-if="guest.notes" class="room-notes">备注：{{ guest.notes }}</p></div>
      <h2 class="stay-guests-title">房间使用历史</h2><article v-for="segment in stay.roomSegments" :key="segment.id" class="stay-history-item"><strong>{{ segment.roomNumber }} · {{ segment.roomTypeName }}</strong><p>{{ localTime(segment.enteredAt) }} → {{ segment.exitedAt ? localTime(segment.exitedAt) : '当前有效使用' }}</p><p>{{ segment.endReason === 'ROOM_CHANGE' ? '已结束 · 换房' : segment.endReason === 'CHECK_OUT' ? '已结束 · 退房' : '当前入住房间' }}</p></article>
      <h2 class="stay-guests-title">换房历史</h2><article v-for="change in stay.roomChanges" :key="change.id" class="stay-history-item"><strong>#{{ change.id }} · {{ change.oldRoomNumber }} → {{ change.newRoomNumber }}</strong><p>{{ localTime(change.changedAt) }} · {{ change.operatorName }}</p><p>{{ changeReasonNames[change.reasonType] }}：<span class="room-notes">{{ change.reason }}</span></p><p>{{ change.oldRoomTypeName }} → {{ change.newRoomTypeName }}</p><p v-if="change.issueId">关联原房异常 #{{ change.issueId }}（换房不改变异常处理状态）</p><p>原房清洁任务 #{{ change.taskId }} · 使用段 #{{ change.oldSegmentId }} → #{{ change.newSegmentId }}</p></article><p v-if="!stay.roomChanges?.length" class="subtle">本次入住暂无换房记录。</p>
    </template></div>
    <RoomChangeDialog ref="roomChange" @completed="load" /><CheckoutDialog ref="checkout" @completed="load" />
  </section>
</template>

<style scoped>.stay-history-item { margin: 12px 0; padding: 14px; border: 1px solid #e4e7ed; border-radius: 8px; overflow-wrap: anywhere; }.stay-history-item p { margin: 8px 0; }</style>
