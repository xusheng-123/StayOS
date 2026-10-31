<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { auth, can } from '../stores/auth'
import { ElEmpty, ElMessage, ElMessageBox, ElPagination } from 'element-plus'
import { businessDays, cashierKey, closeBusinessDay, confirmShift, openShift, shiftList, submitShift } from '../api/cashier'
import { localTime } from '../api/rooms'
import { shiftStatusNames, type BusinessDayView, type ShiftView } from '../types/cashier'

const canClose = computed(() => can('cashier:close'))
const shifts = ref<ShiftView[]>(), shiftTotal = ref(0), days = ref<BusinessDayView[]>()
const scope = ref('mine'), status = ref('ALL'), page = ref(1)
const openedFloat = ref(''), opening = ref(false)
const loading = ref(false), dayLoading = ref(false), error = ref(''), dayError = ref('')
// Close failures must stay visible until the next close attempt: the detail names every gap that blocks the day.
const closeProblem = ref('')
let generation = 0
async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await shiftList(canClose.value ? scope.value : 'mine', status.value, page.value)
    if (current === generation) { shifts.value = data.items; shiftTotal.value = data.total }
  } catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '班次加载失败，请稍后重试'; shifts.value = undefined } }
  finally { if (current === generation) loading.value = false }
}
async function loadDays() {
  dayLoading.value = true; dayError.value = ''
  try { days.value = await businessDays() }
  catch (cause) { dayError.value = cause instanceof Error ? cause.message : '营业日加载失败，请稍后重试'; days.value = undefined }
  finally { dayLoading.value = false }
}
const yuan = (value: string | null) => value === null ? '—' : `¥${value}`

async function open() {
  if (!/^[0-9]{1,8}(\.[0-9]{1,2})?$/.test(openedFloat.value.trim())) { error.value = '期初备用金必须为最多两位小数的非负数'; return }
  opening.value = true; error.value = ''
  try { await openShift(cashierKey(), openedFloat.value.trim()); openedFloat.value = ''; ElMessage.success('班次已开启'); await Promise.all([load(true), loadDays()]) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '开班失败，请稍后重试' }
  finally { opening.value = false }
}
const MONEY_PATTERN = /^[0-9]{1,8}(\.[0-9]{1,2})?$/
async function submit(shift: ShiftView) {
  let counted = ''
  try {
    ({ value: counted } = await ElMessageBox.prompt(
      `清点抽屉现金后录入。账面应为实收现金 ${yuan(shift.money.receipts)} − 退款 ${yuan(shift.money.refunds)} + 备用金 ${yuan(shift.openedFloat)} = ${yuan(shift.money.expectedCash)}。`,
      `提交班次 #${shift.id}`, { confirmButtonText: '提交实盘', cancelButtonText: '取消', inputPattern: MONEY_PATTERN, inputErrorMessage: '实盘金额必须为最多两位小数的非负数', inputPlaceholder: '例如：1280.50' }))
  } catch { return }
  counted = counted.trim()
  let reason = ''
  // Compare numerically: entering “306” against an expected “306.00” is not a difference.
  if (Number(counted) !== Number(shift.money.expectedCash)) {
    try {
      ({ value: reason } = await ElMessageBox.prompt('实盘与账面存在差异，需填写差异原因（不修改任何已入账收款）。', '差异原因', { confirmButtonText: '确认提交', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请填写差异原因', inputPlaceholder: '例如：找零误差' }))
    } catch { return }
  }
  error.value = ''
  try { await submitShift(shift.id, cashierKey(), counted, reason, shift.version); ElMessage.success('班次已提交，等待确认'); await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败，请稍后重试'; await load() }
}
async function confirm(shift: ShiftView) {
  try { await ElMessageBox.confirm(`确认班次 #${shift.id} 的实盘结果？确认后班次与差异将永久固化。`, '确认收银班次', { confirmButtonText: '确认班次', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  error.value = ''
  try { await confirmShift(shift.id, cashierKey(), shift.version); ElMessage.success('班次已确认'); await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '确认失败，请稍后重试'; await load() }
}
async function closeDay(day: BusinessDayView) {
  try { await ElMessageBox.confirm(`关账将校验 ${day.businessDate} 的房费与资金，固化当期日报并锁定该营业日。关账为人工操作，不会自动退房。`, `关账 ${day.businessDate}`, { confirmButtonText: '开始关账', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  closeProblem.value = ''
  try { await closeBusinessDay(day.businessDate, cashierKey()); ElMessage.success('关账完成，日报已固化'); await loadDays() }
  catch (cause) {
    closeProblem.value = cause instanceof Error ? cause.message : '关账失败，请稍后重试'
    await loadDays()
  }
}
onMounted(() => { if (can('cashier:operate')) { void load(true); void loadDays() } })
</script>

<template>
 <section class="rooms-page cashier-page">
  <div class="page-heading"><div><p class="eyebrow muted">CASHIER DESK</p><h1>收银与营业日</h1><p class="subtle">一个收银员同一时间只有一个进行中班次；现金收退款按收款人归属班次，实盘差异独立留痕，从不回改已入账收款。</p></div><span class="heading-actions"><router-link v-if="can('report:finance')" to="/reports/daily"><el-button>营业日报</el-button></router-link><el-button :loading="loading" @click="load(); loadDays()">刷新</el-button></span></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <el-alert v-if="closeProblem" :title="`关账未完成：${closeProblem}`" type="warning" :closable="false" class="stay-alert" />

  <div class="catalog-panel day-panel" v-loading="dayLoading">
   <div class="table-caption"><h2>营业日</h2><span class="subtle">日切时间来自生效财务政策；关账后不改原日报，本期调整记在当前营业日。</span></div>
   <el-alert v-if="dayError" :title="dayError" type="error" :closable="false" />
   <el-empty v-else-if="days && !days.length" description="暂无营业日记录" />
   <div v-else class="day-row day-head"><span>营业日</span><span>日切</span><span>状态</span><span>关账信息</span><span>日报</span><span></span></div>
   <div v-for="day in days" :key="day.businessDate" class="day-row">
    <span><strong>{{ day.businessDate }}</strong><small v-if="day.current">今天</small></span>
    <span class="subtle">每日 {{ day.cutoff }}</span>
    <el-tag :type="day.status === 'OPEN' ? 'warning' : 'success'">{{ day.status === 'OPEN' ? '营业中' : '已关账' }}</el-tag>
    <span class="subtle">{{ day.closedByName ? `${day.closedByName} 关账于 ${localTime(day.closedAt || '')}` : '—' }}</span>
    <span class="subtle">{{ day.hasReport ? '已固化' : '未生成' }}</span>
    <el-button v-if="canClose && day.status === 'OPEN'" type="primary" size="small" @click="closeDay(day)">关账</el-button>
   </div>
  </div>

  <form class="cashier-filters catalog-panel" @submit.prevent="open">
   <label>期初备用金<input v-model="openedFloat" inputmode="decimal" aria-label="期初备用金" placeholder="例如：500" /></label>
   <el-button native-type="submit" type="primary" :loading="opening">开启我的班次</el-button>
  </form>

  <form class="cashier-filters catalog-panel" @submit.prevent="load(true)">
   <label v-if="canClose">查看范围<select v-model="scope" aria-label="查看范围"><option value="mine">我的班次</option><option value="all">全部收银员</option></select></label>
   <label>班次状态<select v-model="status" aria-label="班次状态"><option value="ALL">全部状态</option><option value="OPEN">进行中</option><option value="SUBMITTED">待确认</option><option value="CONFIRMED">已确认</option></select></label>
   <el-button native-type="submit" type="primary" :loading="loading">查询班次</el-button>
  </form>

  <div v-loading="loading" class="catalog-panel">
   <div class="table-caption"><h2>收银班次</h2><span class="subtle">{{ shiftTotal }} 个班次</span></div>
   <el-empty v-if="shifts && !shifts.length" description="当前条件下没有班次" />
   <div v-for="shift in shifts" :key="shift.id" class="shift-row">
    <span><strong>#{{ shift.id }} · {{ shift.operatorName }}</strong><small>{{ shift.businessDate }} 开班于 {{ localTime(shift.openedAt) }} · 备用金 {{ yuan(shift.openedFloat) }}</small></span>
    <span class="subtle">实收现金 {{ yuan(shift.money.receipts) }}<small>退款 {{ yuan(shift.money.refunds) }} · 账面应存 {{ yuan(shift.money.expectedCash) }}</small></span>
    <span class="subtle">{{ shift.countedCash ? `实盘 ${yuan(shift.countedCash)}` : '未盘点' }}<small v-if="shift.differenceReason">差异原因：{{ shift.differenceReason }}</small><small v-else-if="shift.status !== 'OPEN'">差异原因：无（账实相符）</small></span>
    <span class="shift-side">
     <el-tag :type="shift.status === 'OPEN' ? 'warning' : shift.status === 'SUBMITTED' ? 'primary' : 'success'">{{ shiftStatusNames[shift.status] }}</el-tag>
     <small class="subtle">版本 {{ shift.version }}<template v-if="shift.confirmedByName"> · {{ shift.confirmedByName }} 确认于 {{ localTime(shift.confirmedAt || '') }}</template></small>
     <span class="shift-actions">
      <el-button v-if="shift.status === 'OPEN' && shift.username === auth.user?.username" size="small" type="primary" @click="submit(shift)">提交实盘</el-button>
      <el-button v-if="shift.status === 'SUBMITTED' && canClose" size="small" type="success" @click="confirm(shift)">确认班次</el-button>
     </span>
    </span>
   </div>
   <el-pagination v-if="shiftTotal > 20" v-model:current-page="page" :total="shiftTotal" :page-size="20" layout="prev, pager, next" @current-change="load()" />
  </div>
 </section>
</template>

<style scoped>
.cashier-page { min-width: 0; }
.heading-actions { display: inline-flex; gap: 10px; align-items: center; }
.day-panel { margin-bottom: 20px; }
.day-row { display: grid; grid-template-columns: 1.1fr 0.8fr auto 1.4fr 0.7fr auto; gap: 12px; align-items: center; padding: 12px 0; border-bottom: 1px solid #e9eee8; }
.day-row:last-child { border-bottom: 0; }
.day-head { color: #788278; font-size: 13px; }
.day-row strong { display: block; }
.day-row small { color: #788278; }
.cashier-filters { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 12px; margin-bottom: 20px; align-items: end; }
.cashier-filters label { display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
.cashier-filters select, .cashier-filters input { font: inherit; color: inherit; min-height: 42px; border: 1px solid #cbd6ce; border-radius: 6px; padding: 9px; background: #fff; }
.shift-row { display: grid; grid-template-columns: 1.3fr 1.1fr 1.1fr auto; gap: 12px; padding: 16px 0; border-bottom: 1px solid #e9eee8; }
.shift-row:last-of-type { border-bottom: 0; }
.shift-row span { min-width: 0; overflow-wrap: anywhere; }
.shift-row strong, .shift-row small { display: block; }
.shift-row small { color: #788278; margin-top: 5px; }
.shift-side { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.shift-actions { display: flex; gap: 8px; }
@media (max-width: 750px) {
 .cashier-filters { grid-template-columns: minmax(0, 1fr); }
 .day-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
 .shift-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
 .shift-side { align-items: flex-start; }
}
</style>
