<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElDialog, ElEmpty, ElInput, ElMessage, ElOption, ElRadioButton, ElRadioGroup, ElSelect, ElTable, ElTableColumn } from 'element-plus'
import { can } from '../stores/auth'
import {
  createCatalogItem, createPolicy, createRatePlan, policyOverview, rateDays, ratePlans, serviceCatalog,
  updateCatalogItem, updateRatePlan, upsertRateDay,
} from '../api/billing'
import { localTime, roomOptions } from '../api/rooms'
import { policyModeOptions, type CatalogView, type PolicyView, type RateDayView, type RatePlanView } from '../types/billing'
import type { RoomType } from '../types/room'

const canConfigure = computed(() => can('billing:configure'))
const policies = ref<PolicyView[]>([]), plans = ref<RatePlanView[]>(), catalog = ref<CatalogView[]>(), roomTypes = ref<RoomType[]>([]), loading = ref(false), saving = ref(false), error = ref('')
function message(e: unknown) { return e instanceof Error ? e.message : '价目加载失败，请稍后重试' }
async function load() {
  loading.value = true; error.value = ''
  try {
    const overview = await policyOverview()
    policies.value = [...(overview.active ? [overview.active] : []), ...overview.history]
    plans.value = await ratePlans(); catalog.value = await serviceCatalog()
    if (!roomTypes.value.length) roomTypes.value = (await roomOptions()).types.filter(type => type.enabled)
  } catch (cause) { error.value = message(cause) } finally { loading.value = false }
}

const policyDialog = ref(false)
const policyForm = ref({ billingCutoffTime: '04:00', checkoutDeadline: '', earlyCheckinMode: 'FULL_NIGHT', lateCheckoutMode: 'HOURLY', earlyCheckoutMode: 'FULL_NIGHT', roomChangePriceMode: 'BY_NEW_SEGMENT', extensionPriceMode: 'CURRENT_RATE', notes: '' })
function openPolicy() { policyDialog.value = true }
async function savePolicy() {
  if (!/^\d{2}:\d{2}$/.test(policyForm.value.billingCutoffTime)) { ElMessage.error('计费日切时间格式须为 HH:mm'); return }
  saving.value = true
  try {
    await createPolicy({ ...policyForm.value, checkoutDeadline: policyForm.value.checkoutDeadline || undefined, notes: policyForm.value.notes.trim() || undefined })
    policyDialog.value = false; ElMessage.success('新政策版本已生效，旧版本自动归档'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const planDialog = ref(false), planForm = ref({ roomTypeId: undefined as number | undefined, planCode: '', planName: '', nightlyPrice: '' })
function openPlan() { planForm.value = { roomTypeId: undefined, planCode: '', planName: '', nightlyPrice: '' }; planDialog.value = true }
async function savePlan() {
  if (!planForm.value.roomTypeId || !planForm.value.planCode.trim() || !planForm.value.planName.trim() || !planForm.value.nightlyPrice) { ElMessage.error('请完整填写房型、编码、名称和门市价'); return }
  saving.value = true
  try {
    await createRatePlan({ roomTypeId: planForm.value.roomTypeId!, planCode: planForm.value.planCode.trim(), planName: planForm.value.planName.trim(), nightlyPrice: planForm.value.nightlyPrice })
    planDialog.value = false; ElMessage.success('价目方案已创建'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

const dayDialog = ref(false), dayPlan = ref<RatePlanView | null>(null), dayList = ref<RateDayView[]>([]), dayForm = ref({ stayDate: "", nightlyPrice: "" })
async function openDays(plan: RatePlanView) {
  dayPlan.value = plan; dayForm.value = { stayDate: '', nightlyPrice: plan.nightlyPrice }; dayList.value = []
  dayDialog.value = true
  const from = new Date().toISOString().slice(0, 10), to = new Date(Date.now() + 30 * 86400000).toISOString().slice(0, 10)
  try { dayList.value = await rateDays(plan.id, from, to) } catch (cause) { ElMessage.error(message(cause)) }
}
async function saveDay() {
  if (!dayPlan.value || !dayForm.value.stayDate || !dayForm.value.nightlyPrice) { ElMessage.error('请填写日期和当日房价'); return }
  saving.value = true
  try {
    await upsertRateDay(dayPlan.value.id, { ...dayForm.value })
    ElMessage.success('当日房价已保存，已入旧账不受影响'); await openDays(dayPlan.value); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}

async function togglePlan(plan: RatePlanView) {
  saving.value = true
  try { await updateRatePlan(plan.id, { planName: plan.planName, nightlyPrice: plan.nightlyPrice, enabled: !plan.enabled, version: plan.version }); ElMessage.success('价目方案已更新'); await load() }
  catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
const itemDialog = ref(false), itemForm = ref({ itemCode: '', itemName: '', pricingUnit: 'ITEM', unitPrice: '' })
function openItem() { itemForm.value = { itemCode: '', itemName: '', pricingUnit: 'ITEM', unitPrice: '' }; itemDialog.value = true }
async function saveItem() {
  if (!itemForm.value.itemCode.trim() || !itemForm.value.itemName.trim() || !itemForm.value.unitPrice) { ElMessage.error('请完整填写项目编码、名称和单价'); return }
  saving.value = true
  try {
    await createCatalogItem({ ...itemForm.value, itemCode: itemForm.value.itemCode.trim(), itemName: itemForm.value.itemName.trim() })
    itemDialog.value = false; ElMessage.success('服务价目已创建'); await load()
  } catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
async function toggleItem(item: CatalogView) {
  saving.value = true
  try { await updateCatalogItem(item.id, { itemName: item.itemName, pricingUnit: item.pricingUnit, unitPrice: item.unitPrice, enabled: !item.enabled, version: item.version }); ElMessage.success('服务价目已更新'); await load() }
  catch (cause) { ElMessage.error(message(cause)) } finally { saving.value = false }
}
const modeLabel = (group: keyof typeof policyModeOptions, value: string) => policyModeOptions[group].find(option => option.value === value)?.label || value
onMounted(load)
</script>

<template>
 <section class="rooms-page billing-config-page">
  <div class="page-heading"><div><p class="eyebrow muted">BILLING CONFIGURATION</p><h1>价目维护</h1><p class="subtle">财务政策、房价方案与特殊日价、服务价目。改价只影响之后的入账，已入旧账按快照保留。</p></div>
   <el-button v-if="canConfigure" type="primary" @click="openPolicy">发布新政策版本</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
  <div v-loading="loading">
   <div class="catalog-panel"><div class="table-caption"><h2>财务政策</h2><span class="subtle">同时只有一个生效版本</span></div>
    <div v-for="policy in policies" :key="policy.id" :class="['policy-row', { archived: !policy.active }]">
     <span><strong>版本 v{{ policy.version }}<el-tag v-if="policy.active" type="success" size="small">生效中</el-tag></strong><small class="subtle">由 {{ policy.createdByName }} 于 {{ localTime(policy.createdAt) }} 发布<template v-if="policy.notes"> · {{ policy.notes }}</template></small></span>
     <span class="subtle">日切 {{ policy.billingCutoffTime }}<template v-if="policy.checkoutDeadline"> · 退房时限 {{ policy.checkoutDeadline }}</template></span>
     <span class="subtle">早到 {{ modeLabel('earlyCheckin', policy.earlyCheckinMode) }} · 晚退 {{ modeLabel('lateCheckout', policy.lateCheckoutMode) }} · 提前退房 {{ modeLabel('earlyCheckout', policy.earlyCheckoutMode) }}</span>
     <span class="subtle">换房差价 {{ modeLabel('roomChange', policy.roomChangePriceMode) }} · 续住定价 {{ modeLabel('extension', policy.extensionPriceMode) }}</span>
    </div>
    <el-empty v-if="!policies.length" description="还没有财务政策：开立客账前必须先发布一个版本" />
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>房价方案</h2><el-button v-if="canConfigure" size="small" @click="openPlan">新增方案</el-button></div>
    <el-table :data="plans" empty-text="暂无价目方案">
     <el-table-column label="房型" min-width="140"><template #default="{ row }">{{ row.roomTypeName }}（{{ row.roomTypeCode }}）</template></el-table-column>
     <el-table-column prop="planCode" label="编码" min-width="100" /><el-table-column prop="planName" label="名称" min-width="120" />
     <el-table-column label="门市价" min-width="100"><template #default="{ row }">¥{{ row.nightlyPrice }}/晚</template></el-table-column>
     <el-table-column label="状态" min-width="80"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
     <el-table-column v-if="canConfigure" label="操作" min-width="150" fixed="right"><template #default="{ row }"><el-button text size="small" :disabled="saving" @click="openDays(row as RatePlanView)">特殊日价</el-button><el-button text size="small" :disabled="saving" @click="togglePlan(row as RatePlanView)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
   </div>
   <div class="catalog-panel"><div class="table-caption"><h2>服务价目</h2><el-button v-if="canConfigure" size="small" @click="openItem">新增项目</el-button></div>
    <el-table :data="catalog" empty-text="暂无服务价目">
     <el-table-column prop="itemCode" label="编码" min-width="100" /><el-table-column prop="itemName" label="名称" min-width="140" />
     <el-table-column label="单价" min-width="100"><template #default="{ row }">¥{{ row.unitPrice }}/{{ row.pricingUnit === 'TIME' ? '次' : '份' }}</template></el-table-column>
     <el-table-column label="状态" min-width="80"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
     <el-table-column v-if="canConfigure" label="操作" min-width="90" fixed="right"><template #default="{ row }"><el-button text size="small" :disabled="saving" @click="toggleItem(row as CatalogView)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
   </div>
  </div>

  <el-dialog v-model="policyDialog" title="发布新政策版本" width="min(620px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="savePolicy">
    <div class="form-columns">
     <el-form-item label="计费日切（HH:mm）" required><el-input v-model="policyForm.billingCutoffTime" placeholder="04:00" /></el-form-item>
     <el-form-item label="退房时限（可选）"><el-input v-model="policyForm.checkoutDeadline" placeholder="12:00" /></el-form-item>
     <el-form-item label="早到政策"><el-select v-model="policyForm.earlyCheckinMode" aria-label="早到政策"><el-option v-for="option in policyModeOptions.earlyCheckin" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
     <el-form-item label="晚退政策"><el-select v-model="policyForm.lateCheckoutMode" aria-label="晚退政策"><el-option v-for="option in policyModeOptions.lateCheckout" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
     <el-form-item label="提前退房"><el-select v-model="policyForm.earlyCheckoutMode" aria-label="提前退房政策"><el-option v-for="option in policyModeOptions.earlyCheckout" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
     <el-form-item label="换房差价"><el-select v-model="policyForm.roomChangePriceMode" aria-label="换房差价政策"><el-option v-for="option in policyModeOptions.roomChange" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
     <el-form-item label="续住定价"><el-select v-model="policyForm.extensionPriceMode" aria-label="续住定价政策"><el-option v-for="option in policyModeOptions.extension" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
    </div>
    <el-form-item label="备注"><el-input v-model="policyForm.notes" maxlength="500" /></el-form-item>
    <p class="subtle">发布后旧版本立即归档，只保留一个生效版本。</p>
    <div class="dialog-actions"><el-button :disabled="saving" @click="policyDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">发布并生效</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="planDialog" title="新增房价方案" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="savePlan">
    <el-form-item label="房型" required><el-select v-model="planForm.roomTypeId" placeholder="选择房型" aria-label="房型"><el-option v-for="type in roomTypes" :key="type.id" :label="`${type.typeName}（${type.typeCode}）`" :value="type.id" /></el-select></el-form-item>
    <el-form-item label="方案编码" required><el-input v-model="planForm.planCode" maxlength="40" placeholder="例如 STD" /></el-form-item>
    <el-form-item label="方案名称" required><el-input v-model="planForm.planName" maxlength="60" placeholder="例如 门市价" /></el-form-item>
    <el-form-item label="每晚门市价（元）" required><el-input v-model="planForm.nightlyPrice" placeholder="最多两位小数" /></el-form-item>
    <div class="dialog-actions"><el-button :disabled="saving" @click="planDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">创建方案</el-button></div>
   </el-form>
  </el-dialog>
  <el-dialog v-model="dayDialog" :title="`特殊日价 · ${dayPlan?.planName || ''}`" width="min(560px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveDay">
    <div class="form-columns">
     <el-form-item label="住宿日期" required><el-input v-model="dayForm.stayDate" placeholder="如 2026-12-31" /></el-form-item>
     <el-form-item label="当晚房价（元）" required><el-input v-model="dayForm.nightlyPrice" /></el-form-item>
    </div>
    <p class="subtle">同一日期重复保存即覆盖；已入旧账不受影响。节假日等特殊日期可单独调价。</p>
    <div class="dialog-actions"><el-button :disabled="saving" @click="dayDialog = false">关闭</el-button><el-button type="primary" native-type="submit" :loading="saving">保存日价</el-button></div>
   </el-form>
   <el-table v-if="dayList.length" :data="dayList" size="small" class="day-table"><el-table-column prop="stayDate" label="日期" min-width="110" /><el-table-column label="当晚房价" min-width="100"><template #default="{ row }">¥{{ row.nightlyPrice }}</template></el-table-column></el-table>
   <el-empty v-else description="未来 30 天没有单独日价，全部按门市价" :image-size="60" />
  </el-dialog>
  <el-dialog v-model="itemDialog" title="新增服务价目" width="min(520px, calc(100vw - 32px))">
   <el-form label-position="top" @submit.prevent="saveItem">
    <el-form-item label="项目编码" required><el-input v-model="itemForm.itemCode" maxlength="40" placeholder="例如 WATER" /></el-form-item>
    <el-form-item label="项目名称" required><el-input v-model="itemForm.itemName" maxlength="60" placeholder="例如 矿泉水" /></el-form-item>
    <el-form-item label="计价单位" required><el-radio-group v-model="itemForm.pricingUnit"><el-radio-button value="ITEM">按份</el-radio-button><el-radio-button value="TIME">按次</el-radio-button></el-radio-group></el-form-item>
    <el-form-item label="单价（元）" required><el-input v-model="itemForm.unitPrice" placeholder="最多两位小数" /></el-form-item>
    <div class="dialog-actions"><el-button :disabled="saving" @click="itemDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">创建项目</el-button></div>
   </el-form>
  </el-dialog>
 </section>
</template>
<style scoped>
.billing-config-page { min-width: 0; }
.policy-row { display: grid; grid-template-columns: 1.6fr 1fr 1.4fr 1.4fr; gap: 12px; padding: 14px 0; border-bottom: 1px solid #e9eee8; }
.policy-row span { min-width: 0; overflow-wrap: anywhere; }
.policy-row strong { display: flex; gap: 8px; align-items: center; }
.policy-row small { display: block; margin-top: 4px; }
.policy-row.archived { opacity: 0.65; }
.catalog-panel .el-table { margin-top: 8px; }
.day-table { margin-top: 16px; }
@media (max-width: 900px) { .policy-row { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
