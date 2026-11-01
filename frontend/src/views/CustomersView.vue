<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElMessageBox, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus'
import { localTime } from '../api/rooms'
import { can } from '../stores/auth'
import {
  customerCreate, customerDetail, customerDetachStay, customerLinkStay, customerList, customerMerge,
  customerSetStatus, customerUpdate, staySearch,
} from '../api/customer'
import type { CreatedProfile, CustomerProfile, CustomerProfileDetail, ProfileStayLink, SimilarProfile } from '../types/customer'
import type { Stay } from '../types/stay'

const documentTypes: Record<string, string> = { ID_CARD: '身份证', PASSPORT: '护照', OTHER: '其他证件' }
const statusNames: Record<string, string> = { ACTIVE: '启用', DISABLED: '已停用', MERGED: '已并入' }
const statusTags: Record<string, string> = { ACTIVE: 'success', DISABLED: 'warning', MERGED: 'info' }
const stayStatusNames: Record<string, string> = { IN_HOUSE: '在住', CHECKED_OUT: '已退房' }

const list = ref<CustomerProfile[]>([]), total = ref(0), page = ref(1)
const query = ref(''), status = ref('ACTIVE')
const loading = ref(false), error = ref(''), saving = ref(false)
const detail = ref<CustomerProfileDetail | null>(null), similar = ref<SimilarProfile[]>([])
const createDialog = ref(false), editDialog = ref(false), mergeDialog = ref(false), linkDialog = ref(false)
const createForm = ref({ name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', notes: '' })
const editForm = ref({ name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', notes: '', version: 0 })
const mergeTarget = ref<number | undefined>()
const candidates = ref<CustomerProfile[]>([])
const stayQuery = ref(''), stayResults = ref<Stay[]>([]), stayLoading = ref(false)
let generation = 0

function message(e: unknown) { return e instanceof Error ? e.message : '客历操作失败，请稍后重试' }

async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const data = await customerList(query.value.trim(), status.value, page.value)
    if (current === generation) { list.value = data.items; total.value = data.total }
  } catch (e) { if (current === generation) { error.value = message(e); list.value = [] } }
  finally { if (current === generation) loading.value = false }
}
async function open(row: CustomerProfile) {
  error.value = ''; similar.value = []
  try { detail.value = await customerDetail(row.id) } catch (e) { error.value = message(e) }
}
async function openById(id: number) {
  try { detail.value = await customerDetail(id) } catch (e) { error.value = message(e) }
}
async function submitCreate() {
  saving.value = true
  try {
    const created: CreatedProfile = await customerCreate({ ...createForm.value, name: createForm.value.name.trim() })
    createDialog.value = false; createForm.value = { name: '', documentType: 'ID_CARD', documentNumber: '', phone: '', notes: '' }
    similar.value = created.similarActiveProfiles; detail.value = created.profile
    ElMessage.success('客历档案已创建')
    await load(true)
  } catch (e) { ElMessage.error(message(e)) } finally { saving.value = false }
}
function startEdit() {
  if (!detail.value) return
  editForm.value = { name: detail.value.displayName, documentType: detail.value.documentType, documentNumber: '', phone: '', notes: detail.value.notes, version: detail.value.version }
  editDialog.value = true
}
async function submitEdit() {
  if (!detail.value) return
  saving.value = true
  try {
    detail.value = await customerUpdate(detail.value.id, {
      name: editForm.value.name.trim(), documentType: editForm.value.documentType,
      documentNumber: editForm.value.documentNumber.trim(), phone: editForm.value.phone.trim(),
      notes: editForm.value.notes, version: editForm.value.version,
    })
    editDialog.value = false; ElMessage.success('客历档案已更新'); await load()
  } catch (e) { ElMessage.error(message(e)) } finally { saving.value = false }
}
async function toggleStatus() {
  if (!detail.value) return
  const target = detail.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const ask = target === 'DISABLED'
    ? '停用后该档案不再出现在默认列表，也不能关联新入住；已有历史关联保留。确认停用？'
    : '恢复启用该客历档案？'
  try {
    await ElMessageBox.confirm(ask, '确认操作', { type: 'warning' })
    detail.value = await customerSetStatus(detail.value.id, target)
    ElMessage.success(target === 'DISABLED' ? '档案已停用' : '档案已恢复启用'); await load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(message(e)) }
}
async function openMerge() {
  if (!detail.value) return
  mergeTarget.value = undefined
  try {
    const data = await customerList('', 'ACTIVE', 1, 100)
    candidates.value = data.items.filter(p => p.id !== detail.value!.id)
    mergeDialog.value = true
  } catch (e) { ElMessage.error(message(e)) }
}
async function submitMerge() {
  if (!detail.value || !mergeTarget.value) return
  const target = candidates.value.find(p => p.id === mergeTarget.value)
  try {
    await ElMessageBox.confirm(
      `合并不可撤销：「${detail.value.displayName}（${detail.value.documentMask}）」将并入「${target?.displayName}（${target?.documentMask}）」，其入住关联全部转移到目标档案。请再次核对确系同一人。`,
      '人工核验合并', { type: 'warning', confirmButtonText: '确认同一人并合并' })
    detail.value = await customerMerge(detail.value.id, mergeTarget.value)
    mergeDialog.value = false; ElMessage.success('档案已合并'); await load()
  } catch (e) { if (e !== 'cancel') ElMessage.error(message(e)) }
}
async function openLink() {
  stayQuery.value = ''; stayResults.value = []; linkDialog.value = true
}
async function searchStays() {
  stayLoading.value = true
  try { const data = await staySearch(stayQuery.value.trim()); stayResults.value = data.items } catch (e) { ElMessage.error(message(e)) }
  finally { stayLoading.value = false }
}
async function link(row: Stay) {
  if (!detail.value) return
  try {
    await customerLinkStay(detail.value.id, row.id)
    ElMessage.success('入住已关联到客历档案'); linkDialog.value = false
    detail.value = await customerDetail(detail.value.id)
  } catch (e) { ElMessage.error(message(e)) }
}
async function detach(link: ProfileStayLink) {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(`解除该入住与档案的关联？入住记录本身不受影响。`, '确认解除', { type: 'warning' })
    detail.value = await customerDetachStay(link.linkId)
    ElMessage.success('关联已解除')
  } catch (e) { if (e !== 'cancel') ElMessage.error(message(e)) }
}
onMounted(load)
</script>
<template>
 <section class="rooms-page">
  <div class="page-heading"><div><p class="eyebrow muted">GUEST PROFILES</p><h1>客历档案</h1><p class="subtle">有限的客史档案：与每次入住登记分离；同名或同电话不自动认定同一人，匹配与合并均需人工核验。</p></div>
   <el-button v-if="can('customer:manage')" type="primary" @click="createDialog = true">新建档案</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="stay-alert" />
  <form class="catalog-panel audit-filters" @submit.prevent="load(true)">
   <el-input v-model="query" placeholder="按姓名搜索" aria-label="客历姓名搜索" clearable />
   <el-select v-model="status" aria-label="档案状态" @change="load(true)">
    <el-option label="启用档案" value="ACTIVE" /><el-option label="已停用" value="DISABLED" /><el-option label="已并入" value="MERGED" /><el-option label="全部状态" value="ALL" />
   </el-select>
   <el-button native-type="submit" type="primary" :loading="loading">查询客历</el-button>
  </form>
  <div class="catalog-panel">
   <el-table :data="list" empty-text="没有符合条件的客历档案" @row-click="open" class="clickable-rows" v-loading="loading">
    <el-table-column prop="displayName" label="姓名" min-width="120" />
    <el-table-column label="证件" min-width="150"><template #default="{ row }">{{ documentTypes[(row as CustomerProfile).documentType] }} · {{ (row as CustomerProfile).documentMask }}</template></el-table-column>
    <el-table-column label="电话" min-width="120"><template #default="{ row }">{{ (row as CustomerProfile).phoneMask || '—' }}</template></el-table-column>
    <el-table-column label="状态" min-width="90"><template #default="{ row }"><el-tag :type="(statusTags[(row as CustomerProfile).status] || 'info') as 'success' | 'warning' | 'info'">{{ statusNames[(row as CustomerProfile).status] || (row as CustomerProfile).status }}</el-tag></template></el-table-column>
    <el-table-column label="更新时间" min-width="150"><template #default="{ row }">{{ localTime((row as CustomerProfile).updatedAt) }}</template></el-table-column>
   </el-table>
   <el-pagination v-if="total > 0" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load()" class="table-pagination" />
  </div>

  <div v-if="detail" class="catalog-panel detail-panel">
   <div class="table-caption"><h2>{{ detail.displayName }} 的档案</h2><el-tag :type="(statusTags[detail.status] || 'info') as 'success' | 'warning' | 'info'">{{ statusNames[detail.status] || detail.status }}</el-tag></div>
   <el-alert v-if="detail.status === 'MERGED' && detail.mergedInto" type="info" :closable="false" class="stay-alert"
     :title="`该档案已并入「${detail.mergedInto.displayName}（${detail.mergedInto.documentMask}）」，本页仅保留追溯，入住关联已转移到目标档案。`" />
   <el-alert v-if="similar.length" type="warning" :closable="false" class="stay-alert" title="存在同名启用档案；同名不自动认定同一人，请人工核验后决定是否合并。">
    <p v-for="p in similar" :key="p.id">{{ p.displayName }} · {{ p.documentMask }}<el-button v-if="can('customer:merge')" link type="primary" @click="openById(p.id).then(openMerge)">核对合并</el-button></p>
   </el-alert>
   <dl class="profile-facts">
    <dt>证件</dt><dd>{{ documentTypes[detail.documentType] }} · {{ detail.documentMask }}</dd>
    <dt>电话</dt><dd>{{ detail.phoneMask || '未登记' }}</dd>
    <dt>备注</dt><dd>{{ detail.notes || '—' }}</dd>
   </dl>
   <div v-if="can('customer:manage')" class="detail-actions">
    <el-button :disabled="detail.status === 'MERGED'" @click="startEdit">编辑档案</el-button>
    <el-button v-if="detail.status === 'ACTIVE'" @click="openLink">关联入住</el-button>
    <el-button v-if="detail.status !== 'MERGED'" :type="detail.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggleStatus">{{ detail.status === 'ACTIVE' ? '停用档案' : '恢复启用' }}</el-button>
    <el-button v-if="can('customer:merge') && detail.status === 'ACTIVE'" type="danger" plain @click="openMerge">合并到其他档案</el-button>
   </div>
   <el-table :data="detail.stays" empty-text="尚无人工核验的入住关联；可通过「关联入住」把一次登记挂到该档案">
    <el-table-column label="入住时间" min-width="150"><template #default="{ row }">{{ localTime((row as ProfileStayLink).checkInAt) }}</template></el-table-column>
    <el-table-column label="房间" min-width="90"><template #default="{ row }">{{ (row as ProfileStayLink).roomNumber || '—' }}</template></el-table-column>
    <el-table-column prop="guestName" label="登记住客" min-width="110" />
    <el-table-column label="入住状态" min-width="90"><template #default="{ row }">{{ stayStatusNames[(row as ProfileStayLink).stayStatus] || (row as ProfileStayLink).stayStatus }}</template></el-table-column>
    <el-table-column label="退房时间" min-width="150"><template #default="{ row }">{{ (row as ProfileStayLink).stayStatus === 'CHECKED_OUT' ? '—' : '尚未退房' }}</template></el-table-column>
    <el-table-column v-if="can('customer:manage')" label="操作" min-width="100"><template #default="{ row }"><el-button link type="danger" :disabled="detail?.status === 'MERGED'" @click="detach(row as ProfileStayLink)">解除关联</el-button></template></el-table-column>
   </el-table>
  </div>

  <el-dialog v-model="createDialog" title="新建客历档案" width="min(480px, 94vw)">
   <el-form label-width="90px">
    <el-form-item label="姓名"><el-input v-model="createForm.name" aria-label="客历姓名" maxlength="100" /></el-form-item>
    <el-form-item label="证件类型"><el-select v-model="createForm.documentType" aria-label="证件类型"><el-option label="身份证" value="ID_CARD" /><el-option label="护照" value="PASSPORT" /><el-option label="其他证件" value="OTHER" /></el-select></el-form-item>
    <el-form-item label="证件号码"><el-input v-model="createForm.documentNumber" aria-label="证件号码" maxlength="64" placeholder="仅用于建档，保存后不可在界面读取" /></el-form-item>
    <el-form-item label="联系电话"><el-input v-model="createForm.phone" aria-label="联系电话" maxlength="30" /></el-form-item>
    <el-form-item label="备注"><el-input v-model="createForm.notes" type="textarea" :rows="2" aria-label="客历备注" maxlength="1000" /></el-form-item>
   </el-form>
   <p class="subtle">同名档案不会被自动合并或关联；创建后可在详情页人工核对。</p>
   <template #footer><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitCreate">创建档案</el-button></template>
  </el-dialog>

  <el-dialog v-model="editDialog" title="编辑客历档案" width="min(480px, 94vw)">
   <el-form label-width="90px">
    <el-form-item label="姓名"><el-input v-model="editForm.name" aria-label="编辑客历姓名" maxlength="100" /></el-form-item>
    <el-form-item label="证件类型"><el-select v-model="editForm.documentType" aria-label="编辑证件类型"><el-option label="身份证" value="ID_CARD" /><el-option label="护照" value="PASSPORT" /><el-option label="其他证件" value="OTHER" /></el-select></el-form-item>
    <el-form-item label="证件号码"><el-input v-model="editForm.documentNumber" aria-label="新证件号码" maxlength="64" placeholder="从空字段重新填写，不回显原文" /></el-form-item>
    <el-form-item label="联系电话"><el-input v-model="editForm.phone" aria-label="新联系电话" maxlength="30" placeholder="从空字段重新填写，不回显原文" /></el-form-item>
    <el-form-item label="备注"><el-input v-model="editForm.notes" type="textarea" :rows="2" aria-label="编辑客历备注" maxlength="1000" /></el-form-item>
   </el-form>
   <template #footer><el-button @click="editDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submitEdit">保存修改</el-button></template>
  </el-dialog>

  <el-dialog v-model="mergeDialog" title="人工核验后合并" width="min(520px, 94vw)">
   <p class="subtle">合并不可撤销：源档案标记「已并入」，入住关联全部转移到目标档案；两档案若挂有同一次入住登记，须先解除重复关联。</p>
   <el-form label-width="90px">
    <el-form-item label="目标档案"><el-select v-model="mergeTarget" aria-label="合并目标档案" filterable placeholder="选择保留的目标档案"><el-option v-for="p in candidates" :key="p.id" :label="`${p.displayName} · ${p.documentMask} · #${p.id}`" :value="p.id" /></el-select></el-form-item>
   </el-form>
   <template #footer><el-button @click="mergeDialog = false">取消</el-button><el-button type="danger" :disabled="!mergeTarget" @click="submitMerge">下一步：确认合并</el-button></template>
  </el-dialog>

  <el-dialog v-model="linkDialog" title="关联入住登记" width="min(680px, 94vw)">
   <p class="subtle">按主要联系人姓名检索在住与历史入住；关联默认挂到该登记的主要住客快照。一次登记只能属于一份客历档案。</p>
   <form class="link-search" @submit.prevent="searchStays">
    <el-input v-model="stayQuery" placeholder="住客姓名" aria-label="入住检索姓名" clearable /><el-button native-type="submit" :loading="stayLoading">检索</el-button>
   </form>
   <el-table :data="stayResults" empty-text="没有匹配的入住登记" max-height="360">
    <el-table-column prop="primaryGuestName" label="主要住客" min-width="100" />
    <el-table-column label="入住时间" min-width="150"><template #default="{ row }">{{ localTime((row as Stay).checkInAt) }}</template></el-table-column>
    <el-table-column prop="roomNumber" label="房间" min-width="80" />
    <el-table-column label="状态" min-width="80"><template #default="{ row }">{{ stayStatusNames[(row as Stay).status] || (row as Stay).status }}</template></el-table-column>
    <el-table-column label="操作" min-width="90"><template #default="{ row }"><el-button link type="primary" @click="link(row as Stay)">关联此入住</el-button></template></el-table-column>
   </el-table>
  </el-dialog>
 </section>
</template>
<style scoped>
.detail-panel { margin-top: 20px }
.detail-actions { display: flex; gap: 12px; margin: 16px 0 20px; flex-wrap: wrap }
.profile-facts dt { font-size: 12px; color: #788278; margin-top: 14px }
.profile-facts dd { margin: 6px 0; overflow-wrap: anywhere }
.link-search { display: flex; gap: 12px; margin: 12px 0 }
.table-pagination { margin-top: 16px }
@media (max-width: 750px) { .audit-filters { grid-template-columns: minmax(0, 1fr) } }
</style>
