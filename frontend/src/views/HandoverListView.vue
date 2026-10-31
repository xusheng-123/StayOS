<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElEmpty, ElPagination } from 'element-plus'
import { auth, can } from '../stores/auth'
import { handoverList } from '../api/handover'
import { localTime } from '../api/rooms'
import HandoverFormDialog from '../components/HandoverFormDialog.vue'
import { handoverScopeNames, handoverStatusNames, type HandoverScope, type HandoverSummary } from '../types/handover'

const router = useRouter()
const canFront = computed(() => can('handover:front-desk')), canHousekeeping = computed(() => can('handover:housekeeping'))
const scope = ref<HandoverScope>(canFront.value ? 'FRONT_DESK' : 'HOUSEKEEPING')
const status = ref(''), involving = ref(''), page = ref(1)
const list = ref<HandoverSummary[]>(), total = ref(0), loading = ref(false), error = ref('')
const dialog = ref<InstanceType<typeof HandoverFormDialog>>()
let generation = 0
function message(e: unknown) { return e instanceof Error ? e.message : '交班记录加载失败，请稍后重试' }
const canCreate = computed(() => scope.value === 'FRONT_DESK' ? canFront.value : canHousekeeping.value)
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await handoverList(scope.value, status.value, involving.value, page.value)
    if (current === generation) { list.value = data.items; total.value = data.total }
  } catch (cause) { if (current === generation) { error.value = message(cause); list.value = undefined } }
  finally { if (current === generation) loading.value = false }
}
function switchScope(next: HandoverScope) { if (scope.value !== next) { scope.value = next; void load(true) } }
function create() { dialog.value?.open(scope.value) }
function edited() { void load() }
const statusTag = (s: HandoverSummary) => s.status === 'DRAFT' ? 'info' : s.status === 'SUBMITTED' ? 'warning' : 'success'
onMounted(() => { if (auth.user) void load(true) })
</script>

<template>
 <section class="rooms-page handover-page">
  <div class="page-heading"><div><p class="eyebrow muted">SHIFT HANDOVER</p><h1>交接班</h1><p class="subtle">非财务交班记录：草稿可修改，提交后摘要与事项按快照冻结，接班人确认后归档。事项不含住客证件、电话等敏感资料。</p></div>
   <div class="handover-heading-actions"><el-button v-if="canCreate" type="primary" @click="create">新建交班</el-button><el-button :loading="loading" @click="load()">刷新记录</el-button></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <form class="handover-filters catalog-panel" @submit.prevent="load(true)">
   <label>交班范围<select :value="scope" aria-label="交班范围" :disabled="!(canFront && canHousekeeping)" @change="switchScope(($event.target as HTMLSelectElement).value as HandoverScope)"><option v-if="canFront" value="FRONT_DESK">前台</option><option v-if="canHousekeeping" value="HOUSEKEEPING">客房</option></select></label>
   <label>交班状态<select v-model="status" aria-label="交班状态"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="SUBMITTED">待接班确认</option><option value="ACKNOWLEDGED">接班已确认</option></select></label>
   <label>与我相关<select v-model="involving" aria-label="与我的关系"><option value="">全部交班</option><option value="RECEIVING">我接班的</option><option value="MINE">我交出的</option></select></label>
   <el-button native-type="submit" type="primary" :loading="loading">查询交班</el-button>
  </form>
  <div v-loading="loading" class="catalog-panel handover-records">
   <div class="table-caption"><h2>交班记录</h2><span class="subtle">{{ total }} 条 · 范围：{{ handoverScopeNames[scope] }}</span></div>
   <el-empty v-if="list && !list.length" description="当前条件下没有交班记录" />
   <button v-for="item in list" :key="item.id" class="handover-record" :aria-label="`查看交班 ${item.id}`" @click="router.push(`/handovers/${item.id}`)">
    <span><strong>#{{ item.id }} · {{ handoverScopeNames[item.scope] }}</strong><span class="subtle">{{ localTime(item.shiftStartAt) }} 起</span></span>
    <span>{{ item.handoverByName }}<small>交出 → {{ item.receiverName }}</small></span>
    <span class="subtle">{{ item.itemCount }} 条事项<small v-if="item.acknowledgedAt">确认于 {{ localTime(item.acknowledgedAt) }}</small><small v-else-if="item.submittedAt">提交于 {{ localTime(item.submittedAt) }}</small><small v-else>创建于 {{ localTime(item.createdAt) }}</small></span>
    <el-tag :type="statusTag(item)">{{ handoverStatusNames[item.status] }}</el-tag>
   </button>
   <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load()" />
  </div>
  <HandoverFormDialog ref="dialog" @completed="edited" />
 </section>
</template>
<style scoped>
.handover-page { min-width: 0; }
.handover-heading-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.handover-filters { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
.handover-filters label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.handover-filters select { font: inherit; color: inherit; min-height: 42px; border: 1px solid #cbd6ce; border-radius: 6px; padding: 9px; background: #fff; }
.handover-record { font: inherit; text-align: left; background: transparent; border: 0; border-bottom: 1px solid #e9eee8; width: 100%; padding: 16px 0; display: grid; grid-template-columns: 1.6fr 1.4fr 1.4fr auto; gap: 12px; cursor: pointer; color: inherit; }
.handover-record span { min-width: 0; overflow-wrap: anywhere; }
.handover-record strong, .handover-record small { display: block; }
.handover-record small { color: #788278; margin-top: 5px; }
.handover-records .el-pagination { margin-top: 20px; }
@media (max-width: 750px) {
 .handover-filters { grid-template-columns: minmax(0, 1fr); }
 .handover-record { grid-template-columns: repeat(2, minmax(0, 1fr)); }
 .handover-record :deep(.el-tag) { justify-self: start; max-width: 100%; }
}
</style>
