<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElDialog, ElEmpty, ElInput, ElInputNumber, ElMessage, ElMessageBox, ElOption, ElRadioButton, ElRadioGroup, ElSelect, ElTable, ElTableColumn } from 'element-plus'
import { can } from '../stores/auth'
import {
  adjustCharge, billingKey, closeFolio, folioDetail, fulfillServiceOrder, cancelServiceOrder, createServiceOrder,
  postCharge, ratePlans, receivePayment, reopenFolio, refundPayment, serviceCatalog, serviceOrdersByStay,
} from '../api/billing'
import { localTime } from '../api/rooms'
import {
  chargeCategoryNames, folioEventNames, folioStatusNames, paymentMethodNames, paymentPurposeNames,
  pricingStatusNames, serviceOrderStatusNames, type ChargeView, type FolioDetail, type RatePlanView,
  type CatalogView, type ServiceOrderView,
} from '../types/billing'

const route = useRoute()
const id = Number(route.params.id)
const detail = ref<FolioDetail>(), orders = ref<ServiceOrderView[]>(), loading = ref(false), error = ref('')
const plans = ref<RatePlanView[]>([]), catalog = ref<CatalogView[]>([]), saving = ref(false)
const canCharge = computed(() => can('billing:charge') && detail.value?.folio.status === 'OPEN')
const canAdjust = computed(() => can('billing:adjust') && detail.value?.folio.status === 'OPEN')
const canReceive = computed(() => can('billing:receive') && detail.value?.folio.status === 'OPEN')
const canRefund = computed(() => can('billing:refund') && detail.value?.folio.status === 'OPEN')
const canConfigure = computed(() => can('billing:configure'))
// 重开只看权限：结账后的客账对有调整权限的岗位展示重开入口。
const canReopen = computed(() => can('billing:adjust'))
const folio = computed(() => detail.value?.folio)
const myPlans = computed(() => plans.value.filter(plan => plan.enabled && plan.roomTypeName === folio.value?.roomTypeName))
const activeCatalog = computed(() => catalog.value.filter(item => item.enabled))
const balance = computed(() => folio.value ? Number(folio.value.summary.balance) : 0)
function message(e: unknown) { return e instanceof Error ? e.message : '客账加载失败，请稍后重试' }

async function load() {
  loading.value = true; error.value = ''
  try {
    detail.value = await folioDetail(id)
    orders.value = await serviceOrdersByStay(detail.value.folio.stayRecordId)
    if (!plans.value.length) plans.value = await ratePlans()
    if (!catalog.value.length) catalog.value = await serviceCatalog()
  } catch (cause) { error.value = message(cause) }
  finally { loading.value = false }
}

const chargeDialog = ref(false), chargeForm = ref({ category: 'MANUAL' as 'ROOM_NIGHT' | 'MANUAL', businessDate: '', ratePlanId: undefined as number | undefined, title: '', unitPrice: '', quantity: 1, note: '' })
function openCharge() {
  const today = folio.value?.currentBusinessDate || ''
  chargeForm.value = { category: 'MANUAL', businessDate: today, ratePlanId: undefined, title: '', unitPrice: '', quantity: 1, note: '' }
  chargeDialog.value = true
}
async function saveCharge() {
  const form = chargeForm.value
  if (!form.businessDate) { ElMessage.error('请选择入账日期'); return }
  if (form.category === 'ROOM_NIGHT' && !form.ratePlanId) { ElMessage.error('请选择价目方案'); return }
  if (form.category === 'MANUAL' && (!form.title.trim() || !form.unitPrice)) { ElMessage.error('请填写费用名称和单价'); return }
  saving.value = true
  try {
    await postCharge(id, billingKey(), { ...form, ratePlanId: form.category === 'ROOM_NIGHT' ? form.ratePlanId : undefined, title: form.category === 'MANUAL' ? form.title.trim() : undefined })
    chargeDialog.value = false; ElMessage.success('费用已入账'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const adjustDialog = ref(false), adjustForm = ref({ originalChargeId: undefined as number | undefined, mode: 'CORRECTION' as 'REVERSAL' | 'CORRECTION', amount: '', reason: '' })
const chargeOptions = computed(() => (detail.value?.charges || []).filter(item => item.direction === 'CHARGE'))
const adjustedCharge = computed(() => chargeOptions.value.find(item => item.id === adjustForm.value.originalChargeId))
function openAdjust(charge?: ChargeView) {
  adjustForm.value = { originalChargeId: charge?.id, mode: 'CORRECTION', amount: '', reason: '' }
  adjustDialog.value = true
}
async function saveAdjust() {
  const form = adjustForm.value
  if (!form.originalChargeId) { ElMessage.error('请选择要调整的费用'); return }
  if (!form.reason.trim()) { ElMessage.error('请填写调整原因'); return }
  if (form.mode === 'CORRECTION' && !form.amount) { ElMessage.error('请填写调整金额'); return }
  saving.value = true
  try {
    await adjustCharge(id, billingKey(), { originalChargeId: form.originalChargeId, mode: form.mode, amount: form.mode === 'CORRECTION' ? form.amount : undefined, reason: form.reason.trim() })
    adjustDialog.value = false; ElMessage.success('调整已入账'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const payDialog = ref(false), payForm = ref({ method: 'CASH' as 'CASH' | 'BANK_CARD' | 'QR_PAYMENT' | 'BANK_TRANSFER' | 'OTHER', methodReference: '', purpose: 'PAYMENT' as 'PAYMENT' | 'DEPOSIT', amount: '', note: '' })
function openPay() { payForm.value = { method: 'CASH', methodReference: '', purpose: 'PAYMENT', amount: '', note: '' }; payDialog.value = true }
async function savePay() {
  if (!payForm.value.amount) { ElMessage.error('请填写收款金额'); return }
  saving.value = true
  try {
    await receivePayment(id, billingKey(), { ...payForm.value, methodReference: payForm.value.methodReference.trim() || undefined, note: payForm.value.note.trim() || undefined })
    payDialog.value = false; ElMessage.success('收款已登记'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const refundDialog = ref(false), refundForm = ref({ paymentRecordId: undefined as number | undefined, amount: '', reason: '' })
const receiptOptions = computed(() => (detail.value?.payments || []).filter(item => item.direction === 'RECEIPT'))
function openRefund(payment?: { id: number }) {
  refundForm.value = { paymentRecordId: payment?.id, amount: '', reason: '' }
  refundDialog.value = true
}
async function saveRefund() {
  const form = refundForm.value
  if (!form.paymentRecordId) { ElMessage.error('请选择要退款的收款记录'); return }
  if (!form.amount) { ElMessage.error('请填写退款金额'); return }
  if (!form.reason.trim()) { ElMessage.error('请填写退款原因'); return }
  saving.value = true
  try {
    await refundPayment(id, billingKey(), { paymentRecordId: form.paymentRecordId, amount: form.amount, reason: form.reason.trim() })
    refundDialog.value = false; ElMessage.success('退款已登记'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

async function close() {
  const current = folio.value; if (!current) return
  const outstanding = Number(current.summary.balance) !== 0
  if (outstanding) {
    const note = balance.value > 0
      ? `该客账还有未付余额 ${current.summary.balance} 元。确认结账后仍可重开补充收退款。`
      : `该客账有预收剩余 ${current.summary.balance.slice(1)} 元。确认结账后剩余预收需通过重开客账处理。`
    try { await ElMessageBox.confirm(note, '存在未结清余额', { confirmButtonText: '确认结账', cancelButtonText: '先去收退款', type: 'warning' }) }
    catch { return }
  }
  saving.value = true
  try {
    await closeFolio(id, billingKey(), current.version, outstanding)
    ElMessage.success('客账已结账'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
async function reopen() {
  const current = folio.value; if (!current) return
  let reason = ''
  try { ({ value: reason } = await ElMessageBox.prompt('重开客账需要填写原因，便于结账后追溯。', '重开客账', { confirmButtonText: '确认重开', cancelButtonText: '取消', inputPattern: /\S+/, inputErrorMessage: '请填写重开原因', inputPlaceholder: '例如：补充迷你吧消费' })) }
  catch { return }
  saving.value = true
  try { await reopenFolio(id, billingKey(), current.version, reason.trim()); ElMessage.success('客账已重开'); await load() }
  catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const orderDialog = ref(false), orderForm = ref({ catalogItemId: undefined as number | undefined, quantity: 1, note: '' })
function openOrder() { orderForm.value = { catalogItemId: undefined, quantity: 1, note: '' }; orderDialog.value = true }
async function saveOrder() {
  if (!orderForm.value.catalogItemId) { ElMessage.error('请选择服务项目'); return }
  saving.value = true
  try {
    await createServiceOrder(folio.value!.stayRecordId, billingKey(), { items: [{ catalogItemId: orderForm.value.catalogItemId, quantity: orderForm.value.quantity }], note: orderForm.value.note.trim() || undefined })
    orderDialog.value = false; ElMessage.success('消费请求已登记，实际提供后履约入账'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
async function fulfill(order: ServiceOrderView) {
  try { await ElMessageBox.confirm(`确认已实际提供“${order.items.map(item => item.itemName).join('、')}”？履约后按快照金额入账。`, '履约确认', { confirmButtonText: '确认履约', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  saving.value = true
  try { await fulfillServiceOrder(order.id, billingKey()); ElMessage.success('服务已履约入账'); await load() }
  catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
async function cancel(order: ServiceOrderView) {
  let reason = ''
  try { ({ value: reason } = await ElMessageBox.prompt('取消消费请求需填写原因，取消后不会产生费用。', '取消消费请求', { confirmButtonText: '确认取消', cancelButtonText: '返回', inputPattern: /\S+/, inputErrorMessage: '请填写取消原因' })) }
  catch { return }
  saving.value = true
  try { await cancelServiceOrder(order.id, billingKey(), reason.trim()); ElMessage.success('消费请求已取消'); await load() }
  catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
 <section class="rooms-page folio-page">
  <div class="page-heading"><div><p class="eyebrow muted">FOLIO #{{ id }}</p><h1>客账详情</h1><p class="subtle">费用、实收、预收剩余、退款与未付余额分项展示；收据为酒店内部凭证，不代表法定发票。</p></div>
   <div class="folio-actions">
    <template v-if="folio?.status === 'OPEN'">
     <el-button v-if="canCharge" type="primary" @click="openCharge">费用入账</el-button>
     <el-button v-if="canCharge" @click="openOrder">登记消费</el-button>
     <el-button v-if="canReceive" @click="openPay">登记收款</el-button>
     <el-button v-if="canCharge" @click="openAdjust()">调整费用</el-button>
     <el-button v-if="canRefund" @click="openRefund()">登记退款</el-button>
     <el-button v-if="canCharge" type="warning" :disabled="saving" @click="close">结账</el-button>
    </template>
    <el-button v-else-if="folio?.status === 'CLOSED' && canReopen" type="warning" :disabled="saving" @click="reopen">重开客账</el-button>
    <router-link v-if="canConfigure" to="/billing/config"><el-button>价目维护</el-button></router-link>
    <el-button :loading="loading" @click="load">刷新客账</el-button>
   </div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <div v-loading="loading">
   <div v-if="folio" class="catalog-panel folio-summary">
    <div class="table-caption"><h2>{{ folio.roomNumber }} · {{ folio.roomTypeName }}<small class="subtle">{{ folio.primaryGuestName }} · 入住 {{ folio.checkInDate }}</small></h2>
     <span><el-tag :type="folio.status === 'OPEN' ? 'warning' : 'success'">{{ folioStatusNames[folio.status] }}</el-tag><el-tag v-if="folio.pricingStatus === 'PRICING_REQUIRED'" type="info">{{ pricingStatusNames[folio.pricingStatus] }}（{{ folio.chargesEffectiveFrom }} 起）</el-tag></span></div>
    <dl class="folio-figures">
     <div><dt>费用合计</dt><dd>¥{{ folio.summary.charges }}</dd></div>
     <div><dt>调整冲抵</dt><dd>-¥{{ folio.summary.credits }}</dd></div>
     <div><dt>净应收</dt><dd>¥{{ folio.summary.netReceivable }}</dd></div>
     <div><dt>收款合计</dt><dd>¥{{ folio.summary.receipts }}</dd></div>
     <div><dt>退款合计</dt><dd>-¥{{ folio.summary.refunds }}</dd></div>
     <div><dt>净实收</dt><dd>¥{{ folio.summary.netReceived }}</dd></div>
     <div :class="{ owed: balance > 0 }"><dt>{{ balance < 0 ? '预收剩余' : '未付余额' }}</dt><dd>¥{{ balance < 0 ? folio.summary.balance.slice(1) : folio.summary.balance }}</dd></div>
    </dl>
    <p class="subtle">营业日 {{ folio.currentBusinessDate }} · 开账 {{ localTime(folio.openedAt) }}（{{ folio.openedByName }}）<template v-if="folio.closedAt"> · 结账 {{ localTime(folio.closedAt) }}（{{ folio.closedByName }}）</template></p>
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>费用与调整</h2><span class="subtle">{{ detail?.charges.length || 0 }} 条 · 费用快照冻结，只增不改</span></div>
    <el-table :data="detail?.charges || []" empty-text="暂无费用记录">
     <el-table-column prop="businessDate" label="营业日" min-width="100" />
     <el-table-column label="摘要" min-width="180"><template #default="{ row }"><strong>{{ row.name }}</strong><small v-if="row.note" class="subtle"> · {{ row.note }}</small></template></el-table-column>
     <el-table-column label="类别" min-width="100"><template #default="{ row }">{{ chargeCategoryNames[row.category as keyof typeof chargeCategoryNames] }}</template></el-table-column>
     <el-table-column label="数量 × 单价" min-width="110"><template #default="{ row }">{{ row.quantity }} × {{ row.unitPrice }}</template></el-table-column>
     <el-table-column label="金额" min-width="100"><template #default="{ row }"><span :class="{ credit: row.direction === 'CREDIT' }">{{ row.direction === 'CREDIT' ? '-' : '' }}¥{{ row.amount }}</span></template></el-table-column>
     <el-table-column label="经办" min-width="110"><template #default="{ row }">{{ row.createdByName }}<small class="subtle"> {{ localTime(row.createdAt) }}</small></template></el-table-column>
     <el-table-column v-if="canAdjust" label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.direction === 'CHARGE' && folio?.status === 'OPEN'" text size="small" @click="openAdjust(row as ChargeView)">调整</el-button></template></el-table-column>
    </el-table>
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>收款与退款</h2><span class="subtle">现金收据为内部凭证，不代表法定发票</span></div>
    <el-table :data="detail?.payments || []" empty-text="暂无收退款记录">
     <el-table-column prop="receivedAt" label="时间" min-width="150"><template #default="{ row }">{{ localTime(row.receivedAt) }}</template></el-table-column>
     <el-table-column label="方向" min-width="80"><template #default="{ row }"><el-tag :type="row.direction === 'RECEIPT' ? 'success' : 'danger'">{{ row.direction === 'RECEIPT' ? '收款' : '退款' }}</el-tag></template></el-table-column>
     <el-table-column label="方式" min-width="100"><template #default="{ row }">{{ paymentMethodNames[row.method as keyof typeof paymentMethodNames] }} · {{ paymentPurposeNames[row.purpose as keyof typeof paymentPurposeNames] }}</template></el-table-column>
     <el-table-column prop="amount" label="金额" min-width="90"><template #default="{ row }">{{ row.direction === 'RECEIPT' ? '' : '-' }}¥{{ row.amount }}</template></el-table-column>
     <el-table-column label="凭证号 / 备注" min-width="150"><template #default="{ row }">{{ row.methodReference || '—' }}<small v-if="row.note" class="subtle"> · {{ row.note }}</small></template></el-table-column>
     <el-table-column label="经办" min-width="100"><template #default="{ row }">{{ row.receivedByName }}</template></el-table-column>
     <el-table-column v-if="canRefund" label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.direction === 'RECEIPT' && folio?.status === 'OPEN'" text size="small" @click="openRefund(row as { id: number })">退款</el-button></template></el-table-column>
    </el-table>
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>服务消费</h2><span class="subtle">实际提供后才履约入账；取消不产生费用</span></div>
    <el-empty v-if="orders && !orders.length" description="暂无消费请求" />
    <button v-for="order in orders" :key="order.id" class="order-row" :aria-label="`消费请求 ${order.id}`">
     <span><strong>#{{ order.id }} · {{ order.items.map(item => `${item.itemName}×${item.quantity}`).join('、') }}</strong><small class="subtle">合计 ¥{{ order.totalAmount }}<template v-if="order.note"> · {{ order.note }}</template></small></span>
     <span class="subtle">{{ order.requestedByName }}<small>{{ localTime(order.createdAt) }}</small></span>
     <span v-if="order.status === 'CANCELLED'" class="subtle">{{ order.cancelledByName }}<small>{{ order.cancelReason }}</small></span>
     <span><el-tag :type="order.status === 'REQUESTED' ? 'warning' : order.status === 'FULFILLED' ? 'success' : 'info'">{{ serviceOrderStatusNames[order.status] }}</el-tag>
      <template v-if="order.status === 'REQUESTED' && canCharge && folio?.status === 'OPEN'"><el-button text size="small" :disabled="saving" @click="fulfill(order)">履约入账</el-button><el-button text size="small" :disabled="saving" @click="cancel(order)">取消</el-button></template></span>
    </button>
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>客账事件</h2></div>
    <p v-for="event in detail?.events" :key="event.id" class="folio-event">{{ folioEventNames[event.eventType] }} · {{ event.actorName }} · {{ localTime(event.createdAt) }}<small v-if="event.note" class="subtle"> · {{ event.note }}</small></p>
    <el-empty v-if="detail && !detail.events.length" description="暂无事件" />
   </div>
  </div>

  <el-dialog v-model="chargeDialog" title="费用入账" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveCharge">
    <el-form-item label="费用类别" required><el-radio-group v-model="chargeForm.category"><el-radio-button value="MANUAL">手工费用</el-radio-button><el-radio-button value="ROOM_NIGHT">房费</el-radio-button></el-radio-group></el-form-item>
    <el-form-item label="入账营业日" required><el-input v-model="chargeForm.businessDate" placeholder="如 2026-10-31" /></el-form-item>
    <template v-if="chargeForm.category === 'ROOM_NIGHT'">
     <el-form-item label="价目方案" required><el-select v-model="chargeForm.ratePlanId" placeholder="选择本房型价目" aria-label="价目方案"><el-option v-for="plan in myPlans" :key="plan.id" :label="`${plan.planName}（门市价 ¥${plan.nightlyPrice}）`" :value="plan.id" /></el-select></el-form-item>
     <p class="subtle">按该日覆盖的住房段核对方型，金额按当日日价或门市价确定。</p>
    </template>
    <template v-else>
     <el-form-item label="费用名称" required><el-input v-model="chargeForm.title" maxlength="200" placeholder="例如：迷你吧消费" /></el-form-item>
     <el-form-item label="单价（元）" required><el-input v-model="chargeForm.unitPrice" placeholder="最多两位小数" /></el-form-item>
     <el-form-item label="数量"><el-input-number v-model="chargeForm.quantity" :min="1" :max="999" :precision="0" /></el-form-item>
    </template>
    <el-form-item label="备注"><el-input v-model="chargeForm.note" maxlength="500" /></el-form-item>
    <div class="dialog-actions"><el-button :disabled="saving" @click="chargeDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认入账</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="adjustDialog" title="调整费用" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveAdjust">
    <el-form-item label="要调整的费用" required><el-select v-model="adjustForm.originalChargeId" placeholder="选择费用记录" aria-label="要调整的费用"><el-option v-for="charge in chargeOptions" :key="charge.id" :label="`#${charge.id} ${charge.name} ¥${charge.amount}`" :value="charge.id" /></el-select>
     <p v-if="adjustedCharge" class="subtle">该费用剩余可调整额度以最近记录为准，超过会被拒绝。</p></el-form-item>
    <el-form-item label="调整方式" required><el-radio-group v-model="adjustForm.mode"><el-radio-button value="CORRECTION">部分调整</el-radio-button><el-radio-button value="REVERSAL">全额冲正</el-radio-button></el-radio-group></el-form-item>
    <el-form-item v-if="adjustForm.mode === 'CORRECTION'" label="调整金额（元）" required><el-input v-model="adjustForm.amount" placeholder="最多两位小数" /></el-form-item>
    <el-form-item label="调整原因" required><el-input v-model="adjustForm.reason" maxlength="500" /></el-form-item>
    <div class="dialog-actions"><el-button :disabled="saving" @click="adjustDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认调整</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="payDialog" title="登记收款" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="savePay">
    <el-form-item label="收款方式" required><el-select v-model="payForm.method" aria-label="收款方式"><el-option v-for="(label, value) in paymentMethodNames" :key="value" :label="label" :value="value" /></el-select></el-form-item>
    <el-form-item label="款项用途" required><el-radio-group v-model="payForm.purpose"><el-radio-button value="PAYMENT">房费收款</el-radio-button><el-radio-button value="DEPOSIT">预收定金</el-radio-button></el-radio-group></el-form-item>
    <el-form-item label="金额（元）" required><el-input v-model="payForm.amount" placeholder="最多两位小数" /></el-form-item>
    <el-form-item label="凭证号"><el-input v-model="payForm.methodReference" maxlength="120" placeholder="现金可留空；非现金同一凭证号不可重复" /></el-form-item>
    <el-form-item label="备注"><el-input v-model="payForm.note" maxlength="500" /></el-form-item>
    <div class="dialog-actions"><el-button :disabled="saving" @click="payDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认收款</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="refundDialog" title="登记退款" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveRefund">
    <el-form-item label="原收款记录" required><el-select v-model="refundForm.paymentRecordId" placeholder="选择收款记录" aria-label="原收款记录"><el-option v-for="receipt in receiptOptions" :key="receipt.id" :label="`#${receipt.id} ${paymentMethodNames[receipt.method]} ¥${receipt.amount}`" :value="receipt.id" /></el-select></el-form-item>
    <el-form-item label="退款金额（元）" required><el-input v-model="refundForm.amount" placeholder="不超过该笔收款未退部分与当前预收余额" /></el-form-item>
    <el-form-item label="退款原因" required><el-input v-model="refundForm.reason" maxlength="500" /></el-form-item>
    <p class="subtle">退款额度 = 该笔收款未退回部分与当前预收余额中的较小者；存在欠费时需先冲减费用。</p>
    <div class="dialog-actions"><el-button :disabled="saving" @click="refundDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认退款</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="orderDialog" title="登记服务消费" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveOrder">
    <el-form-item label="服务项目" required><el-select v-model="orderForm.catalogItemId" placeholder="选择价目中的项目" aria-label="服务项目"><el-option v-for="item in activeCatalog" :key="item.id" :label="`${item.itemName}（¥${item.unitPrice}/${item.pricingUnit === 'TIME' ? '次' : '份'}）`" :value="item.id" /></el-select></el-form-item>
    <el-form-item label="数量" required><el-input-number v-model="orderForm.quantity" :min="1" :max="999" :precision="0" /></el-form-item>
    <el-form-item label="备注"><el-input v-model="orderForm.note" maxlength="500" /></el-form-item>
    <p class="subtle">登记后进入“待履约”，实际提供时才按快照金额入账。</p>
    <div class="dialog-actions"><el-button :disabled="saving" @click="orderDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">登记消费</el-button></div>
   </el-form>
  </el-dialog>
 </section>
</template>
<style scoped>
.folio-page { min-width: 0; }
.folio-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.folio-summary p { margin-top: 12px; }
.folio-figures { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 12px; margin-top: 16px; }
.folio-figures dt { font-size: 13px; color: #788278; }
.folio-figures dd { font-size: 20px; font-weight: 600; margin-top: 4px; }
.folio-figures .owed dd { color: #b3590f; }
.credit { color: #256029; }
.order-row { font: inherit; text-align: left; background: transparent; border: 0; border-bottom: 1px solid #e9eee8; width: 100%; padding: 14px 0; display: grid; grid-template-columns: 2fr 1fr 1fr auto; gap: 12px; color: inherit; }
.order-row span { min-width: 0; overflow-wrap: anywhere; }
.order-row small { display: block; margin-top: 4px; }
.folio-event { padding: 10px 0; border-bottom: 1px solid #e9eee8; }
@media (max-width: 750px) { .order-row { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
