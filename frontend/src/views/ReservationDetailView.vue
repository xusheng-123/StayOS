<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElDatePicker, ElDescriptions, ElDescriptionsItem, ElDialog, ElMessage, ElMessageBox, ElSelect, ElOption, ElTable, ElTableColumn } from 'element-plus'
import { ApiError } from '../api/client'
import { localTime } from '../api/rooms'
import { api } from '../api/client'
import { assignReservationRoom, cancelReservation, checkInReservation, confirmReservation, markNoShow, modifyReservation, receiveReservationDeposit, refundReservationDeposit, reservationDetail, reservationKey } from '../api/reservation'
import { can } from '../stores/auth'
import type { ReservationDetail, ReservationStatus } from '../types/reservation'
import { reservationSourceNames, reservationStatusNames } from '../types/reservation'
import type { Room } from '../types/room'
import type { PageResult } from '../types/room'

const route = useRoute(), router = useRouter()
const id = Number(route.params.id)
const detail = ref<ReservationDetail | null>(null), loading = ref(false), error = ref('')
const rooms = ref<Room[]>([])

async function load() {
  loading.value = true; error.value = ''
  try { detail.value = await reservationDetail(id) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '预订详情加载失败' }
  finally { loading.value = false }
}
async function loadRooms() {
  try {
    const result = await api<PageResult<Room>>(`/api/rooms?roomTypeId=${detail.value?.reservation.roomTypeId ?? 0}&pageSize=100`)
    rooms.value = result.items.filter(room => room.enabled)
  } catch { rooms.value = [] }
}
onMounted(() => { void load() })

const reservation = computed(() => detail.value?.reservation ?? null)
const active = computed(() => reservation.value?.status === 'HELD' || reservation.value?.status === 'CONFIRMED')
const roomsForType = computed(() => rooms.value.filter(room => room.roomTypeId === reservation.value?.roomTypeId))
const availableRooms = computed(() => roomsForType.value.filter(room => room.cleaningStatus === 'CLEAN'))
const deposits = computed(() => detail.value?.deposits ?? [])
const events = computed(() => detail.value?.events ?? [])
const depositBalance = computed(() => {
  let receipts = 0, refunds = 0
  for (const item of deposits.value) {
    const amount = Number(item.amount)
    if (item.direction === 'RECEIPT') receipts += amount; else refunds += amount
  }
  return (receipts - refunds).toFixed(2)
})

const eventNames: Record<string, string> = {
  CREATE: '创建预订', MODIFY: '修改预订', CONFIRM: '确认预订', CANCEL: '取消预订', EXPIRE_RELEASE: '到期自动释放',
  NO_SHOW: '标记未到店', ASSIGN_ROOM: '分配房间', CHECK_IN: '转办理入住', DEPOSIT_RECEIVE: '收取订金', DEPOSIT_REFUND: '退还订金',
}
function statusTagType(statusValue: ReservationStatus): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  return statusValue === 'CONFIRMED' ? 'success' : statusValue === 'CHECKED_IN' ? 'primary' : statusValue === 'CANCELLED' || statusValue === 'NO_SHOW' ? 'info' : 'warning'
}

// ---- 修改预订 ----
const modifyVisible = ref(false), modifyBusy = ref(false), modifyError = ref('')
const modifyForm = ref({ arrivalDate: '', departureDate: '', contactName: '', contactPhone: '', notes: '' })
function openModify() {
  if (!reservation.value) return
  modifyForm.value = { arrivalDate: reservation.value.arrivalDate, departureDate: reservation.value.departureDate, contactName: reservation.value.contactName, contactPhone: '', notes: reservation.value.notes }
  modifyError.value = ''; modifyVisible.value = true
}
async function submitModify() {
  modifyError.value = ''
  if (!reservation.value) return
  modifyBusy.value = true
  try {
    detail.value = await modifyReservation(id, reservationKey(), {
      version: reservation.value.version,
      arrivalDate: modifyForm.value.arrivalDate || undefined, departureDate: modifyForm.value.departureDate || undefined,
      contactName: modifyForm.value.contactName.trim() || undefined, contactPhone: modifyForm.value.contactPhone.trim() || undefined,
      notes: modifyForm.value.notes.trim() || undefined,
    })
    modifyVisible.value = false
    ElMessage.success('预订已修改')
  } catch (cause) { modifyError.value = cause instanceof ApiError ? cause.message : '预订修改失败，请稍后重试' }
  finally { modifyBusy.value = false }
}

// ---- 确认 / 取消 / 未到店 ----
const busyAction = ref('')
async function doConfirm() {
  busyAction.value = 'confirm'
  try { detail.value = await confirmReservation(id, reservationKey()); ElMessage.success('预订已确认') }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '确认失败，请稍后重试') }
  finally { busyAction.value = '' }
}
async function askReason(title: string, action: 'cancel' | 'no-show') {
  try {
    const { value } = await ElMessageBox.prompt('请填写原因（1—500 字）', title, { inputPattern: /\S+/, inputErrorMessage: '原因不能为空', inputPlaceholder: '将写入预订事件与操作记录', confirmButtonText: '确定', cancelButtonText: '取消' })
    busyAction.value = action
    const reason = value.trim()
    detail.value = action === 'cancel' ? await cancelReservation(id, reservationKey(), reason) : await markNoShow(id, reservationKey(), reason)
    ElMessage.success(action === 'cancel' ? '预订已取消，占用的未来夜晚已释放' : '预订已标记未到店')
  } catch (cause) {
    if (cause !== 'cancel' && cause instanceof Error) ElMessage.error(cause.message)
  } finally { busyAction.value = '' }
}

// ---- 分配房间 / 办理入住 ----
const assignVisible = ref(false), assignBusy = ref(false), assignError = ref('')
const checkInVisible = ref(false), checkInBusy = ref(false), checkInError = ref('')
const assignRoomId = ref<number | ''>('')
const checkInForm = ref({ roomId: '' as number | '', expectedCheckOutAt: '', guestName: '', documentType: 'ID_CARD', documentNumber: '', phone: '' })
function openAssign() {
  assignRoomId.value = reservation.value?.assignedRoomId ?? ''
  assignError.value = ''; assignVisible.value = true; void loadRooms()
}
async function submitAssign() {
  assignError.value = ''
  if (!assignRoomId.value) { assignError.value = '请选择房间'; return }
  assignBusy.value = true
  try { detail.value = await assignReservationRoom(id, reservationKey(), Number(assignRoomId.value)); assignVisible.value = false; ElMessage.success('已保存建议房间，实际可住以办理入住校验为准') }
  catch (cause) { assignError.value = cause instanceof ApiError ? cause.message : '分配房间失败，请稍后重试' }
  finally { assignBusy.value = false }
}
async function openCheckIn() {
  checkInError.value = ''; checkInVisible.value = true; void loadRooms()
  checkInForm.value = { roomId: reservation.value?.assignedRoomId ?? '', expectedCheckOutAt: '', guestName: '', documentType: 'ID_CARD', documentNumber: '', phone: '' }
}
async function submitCheckIn() {
  checkInError.value = ''
  if (!checkInForm.value.roomId) { checkInError.value = '请选择实际入住房间'; return }
  if (!checkInForm.value.expectedCheckOutAt) { checkInError.value = '请选择预计退房时间'; return }
  if (!checkInForm.value.guestName.trim() || !checkInForm.value.documentNumber.trim()) { checkInError.value = '请填写主要住客姓名与证件号'; return }
  checkInBusy.value = true
  try {
    const result = await checkInReservation(id, reservationKey(), {
      roomId: Number(checkInForm.value.roomId), expectedCheckOutAt: checkInForm.value.expectedCheckOutAt,
      guests: [{ name: checkInForm.value.guestName.trim(), documentType: checkInForm.value.documentType, documentNumber: checkInForm.value.documentNumber.trim(), phone: checkInForm.value.phone.trim() || undefined, primary: true }],
    })
    checkInVisible.value = false
    ElMessage.success('预订已转入住，占用的预订夜晚已转移到在住记录')
    void router.push(`/stays/${result.stayId}`)
  } catch (cause) { checkInError.value = cause instanceof ApiError ? cause.message : '办理入住失败，请稍后重试' }
  finally { checkInBusy.value = false }
}

// ---- 订金 ----
const depositVisible = ref(false), depositBusy = ref(false), depositError = ref('')
const depositForm = ref({ method: 'CASH', amount: '', methodReference: '', note: '' })
const refundVisible = ref(false), refundBusy = ref(false), refundError = ref('')
const refundForm = ref({ paymentRecordId: '' as number | '', amount: '', reason: '' })
const depositMethods = [{ value: 'CASH', label: '现金' }, { value: 'BANK_CARD', label: '银行卡' }, { value: 'QR_PAYMENT', label: '扫码支付' }, { value: 'BANK_TRANSFER', label: '银行转账' }, { value: 'OTHER', label: '其他' }]
const documentTypes = [{ value: 'ID_CARD', label: '居民身份证' }, { value: 'PASSPORT', label: '护照' }, { value: 'OTHER', label: '其他证件' }]
function openDeposit() { depositForm.value = { method: 'CASH', amount: '', methodReference: '', note: '' }; depositError.value = ''; depositVisible.value = true }
async function submitDeposit() {
  depositError.value = ''
  if (!depositForm.value.amount.trim()) { depositError.value = '请填写订金金额'; return }
  depositBusy.value = true
  try {
    await receiveReservationDeposit(id, reservationKey(), depositForm.value.method, depositForm.value.amount.trim(), depositForm.value.methodReference.trim(), depositForm.value.note.trim())
    depositVisible.value = false; ElMessage.success('订金已登记（真实资金记录，转入住后随客账核销）'); void load()
  } catch (cause) { depositError.value = cause instanceof ApiError ? cause.message : '订金登记失败，请稍后重试' }
  finally { depositBusy.value = false }
}
function openRefund() {
  const receipts = deposits.value.filter(item => item.direction === 'RECEIPT' && item.folioId === null)
  if (receipts.length === 0) { ElMessage.info('没有可退的未迁移订金；已转入客账的订金请在客账中办理退款'); return }
  refundForm.value = { paymentRecordId: receipts[0]?.id ?? '', amount: '', reason: '' }
  refundError.value = ''; refundVisible.value = true
}
async function submitRefund() {
  refundError.value = ''
  if (!refundForm.value.paymentRecordId) { refundError.value = '请选择要退款的订金记录'; return }
  if (!refundForm.value.amount.trim()) { refundError.value = '请填写退款金额'; return }
  if (!refundForm.value.reason.trim()) { refundError.value = '请填写退款原因'; return }
  refundBusy.value = true
  try {
    await refundReservationDeposit(id, reservationKey(), Number(refundForm.value.paymentRecordId), refundForm.value.amount.trim(), refundForm.value.reason.trim())
    refundVisible.value = false; ElMessage.success('退款已登记'); void load()
  } catch (cause) { refundError.value = cause instanceof ApiError ? cause.message : '退款登记失败，请稍后重试' }
  finally { refundBusy.value = false }
}
</script>

<template>
  <section class="rooms-page stay-page" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="reservation">
      <div class="page-heading">
        <div><p class="eyebrow muted">RESERVATION #{{ reservation.id }}</p><h1>预订详情</h1><p class="subtle">{{ reservation.roomTypeName }} · {{ reservation.arrivalDate }} → {{ reservation.departureDate }}（{{ reservation.nights }} 晚）</p></div>
        <div class="reservation-actions">
          <el-button v-if="reservation.status === 'HELD'" type="success" :loading="busyAction === 'confirm'" @click="doConfirm">确认预订</el-button>
          <el-button v-if="active" @click="openModify">修改预订</el-button>
          <el-button v-if="active" @click="openAssign">分配房间</el-button>
          <el-button v-if="reservation.status === 'CONFIRMED'" type="primary" @click="openCheckIn">办理入住</el-button>
          <el-button v-if="reservation.status === 'CONFIRMED'" :loading="busyAction === 'no-show'" @click="askReason('标记未到店', 'no-show')">标记未到店</el-button>
          <el-button v-if="active" type="danger" plain :loading="busyAction === 'cancel'" @click="askReason('取消预订', 'cancel')">取消预订</el-button>
        </div>
      </div>
      <div class="catalog-panel">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="状态"><el-tag :type="statusTagType(reservation.status)">{{ reservationStatusNames[reservation.status] }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="入住区间">{{ reservation.arrivalDate }} → {{ reservation.departureDate }}（{{ reservation.nights }} 晚，离店日不含）</el-descriptions-item>
          <el-descriptions-item label="房型">{{ reservation.roomTypeName }}（{{ reservation.roomTypeCode }}）</el-descriptions-item>
          <el-descriptions-item label="来源">{{ reservationSourceNames[reservation.source] ?? reservation.source }}</el-descriptions-item>
          <el-descriptions-item label="外部单号">{{ reservation.externalRef || '—' }}</el-descriptions-item>
          <el-descriptions-item label="建议房间">{{ reservation.assignedRoomNumber ?? '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ reservation.contactName }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ reservation.contactPhoneMask || '未留存' }}</el-descriptions-item>
          <el-descriptions-item label="版本号">{{ reservation.version }}</el-descriptions-item>
          <el-descriptions-item label="保留截止">{{ reservation.heldUntil ? localTime(reservation.heldUntil) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="取消原因">{{ reservation.cancelReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建信息">{{ reservation.createdByName }} · {{ localTime(reservation.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ reservation.notes || '—' }}</el-descriptions-item>
        </el-descriptions>
        <p v-if="reservation.checkedInStayId" class="subtle" style="margin-top: 12px">
          已转入住：<router-link :to="`/stays/${reservation.checkedInStayId}`">查看入住记录 #{{ reservation.checkedInStayId }}</router-link>
        </p>
      </div>

      <div class="catalog-panel">
        <div class="table-caption"><h2>订金</h2><span class="subtle">余额 {{ depositBalance }} 元（复用真实资金记录；取消罚金 0.00 元，首版未配置罚金政策）</span></div>
        <div style="margin-bottom: 12px" v-if="active">
          <el-button type="primary" plain @click="openDeposit">收订金</el-button>
          <el-button v-if="can('billing:refund')" @click="openRefund">订金退款</el-button>
        </div>
        <el-table :data="deposits" empty-text="尚未收取订金" style="width: 100%">
          <el-table-column prop="id" label="记录号" width="80" />
          <el-table-column label="方向" width="90"><template #default="{ row }"><el-tag :type="row.direction === 'RECEIPT' ? 'success' : 'info'" effect="plain">{{ row.direction === 'RECEIPT' ? '收款' : '退款' }}</el-tag></template></el-table-column>
          <el-table-column prop="amount" label="金额（元）" width="110" />
          <el-table-column label="方式" width="100"><template #default="{ row }">{{ depositMethods.find(item => item.value === row.method)?.label ?? row.method }}</template></el-table-column>
          <el-table-column label="所属客账" width="100"><template #default="{ row }">{{ row.folioId ? `客账 #${row.folioId}` : '未迁移' }}</template></el-table-column>
          <el-table-column prop="note" label="备注" min-width="160"><template #default="{ row }">{{ row.note || '—' }}</template></el-table-column>
          <el-table-column label="经办" min-width="150"><template #default="{ row }">{{ row.receivedByName }} · {{ localTime(row.receivedAt) }}</template></el-table-column>
        </el-table>
      </div>

      <div class="catalog-panel">
        <div class="table-caption"><h2>事件记录</h2><span class="subtle">仅记录业务事实，不含敏感输入</span></div>
        <el-table :data="events" empty-text="暂无事件" style="width: 100%">
          <el-table-column label="事件" width="140"><template #default="{ row }">{{ eventNames[row.action] ?? row.action }}</template></el-table-column>
          <el-table-column prop="detail" label="说明" min-width="300" />
          <el-table-column label="经办" min-width="170"><template #default="{ row }">{{ row.actorName }} · {{ localTime(row.createdAt) }}</template></el-table-column>
        </el-table>
      </div>
    </template>

    <el-dialog v-model="modifyVisible" title="修改预订" width="520px" @closed="modifyError = ''">
      <el-alert v-if="modifyError" :title="modifyError" type="error" :closable="false" show-icon />
      <p class="subtle">日期或房型变更会先锁新旧日期的库存再核验；失败时原预订与原库存保持不变。</p>
      <el-form label-width="110px" @submit.prevent="submitModify">
        <el-form-item label="到店日期"><el-date-picker v-model="modifyForm.arrivalDate" type="date" value-format="YYYY-MM-DD" aria-label="到店日期" /></el-form-item>
        <el-form-item label="离店日期"><el-date-picker v-model="modifyForm.departureDate" type="date" value-format="YYYY-MM-DD" aria-label="离店日期（不含）" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="modifyForm.contactName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="modifyForm.contactPhone" maxlength="30" clearable placeholder="留空保持不变" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="modifyForm.notes" maxlength="1000" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="modifyVisible = false">取消</el-button><el-button type="primary" :loading="modifyBusy" @click="submitModify">保存修改</el-button></template>
    </el-dialog>

    <el-dialog v-model="assignVisible" title="分配建议房间" width="460px" @closed="assignError = ''">
      <el-alert v-if="assignError" :title="assignError" type="error" :closable="false" show-icon />
      <p class="subtle">分配是建议性的；实际能否入住以办理入住时的房态与清洁校验为准。</p>
      <el-form label-width="110px" @submit.prevent="submitAssign">
        <el-form-item label="房间"><el-select v-model="assignRoomId" aria-label="建议房间" style="width: 100%" placeholder="选择本房型下的房间"><el-option v-for="room in roomsForType" :key="room.id" :label="`${room.roomNumber}（${room.cleaningStatus === 'CLEAN' ? '已清洁' : '未清洁'}）`" :value="room.id" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="assignVisible = false">取消</el-button><el-button type="primary" :loading="assignBusy" @click="submitAssign">保存分配</el-button></template>
    </el-dialog>

    <el-dialog v-model="checkInVisible" title="预订转入住" width="520px" @closed="checkInError = ''">
      <el-alert v-if="checkInError" :title="checkInError" type="error" :closable="false" show-icon />
      <p class="subtle">已收订金随入住转入客账，不重复记收款；实际超期会先提示未来冲突，不会自动赶客。</p>
      <el-form label-width="110px" @submit.prevent="submitCheckIn">
        <el-form-item label="实际房间" required><el-select v-model="checkInForm.roomId" aria-label="实际入住房间" style="width: 100%" placeholder="选择本房型下已清洁的房间"><el-option v-for="room in availableRooms" :key="room.id" :label="room.roomNumber" :value="room.id" /></el-select></el-form-item>
        <el-form-item label="预计退房" required><el-date-picker v-model="checkInForm.expectedCheckOutAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss+08:00" format="YYYY-MM-DD HH:mm" aria-label="预计退房时间（北京时间）" placeholder="北京时间" style="width: 100%" /></el-form-item>
        <el-form-item label="主要住客" required><el-input v-model="checkInForm.guestName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="证件类型" required><el-select v-model="checkInForm.documentType" aria-label="证件类型" style="width: 100%"><el-option v-for="item in documentTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="证件号码" required><el-input v-model="checkInForm.documentNumber" maxlength="50" clearable show-password placeholder="加密保存，仅显示掩码" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="checkInForm.phone" maxlength="30" clearable placeholder="选填" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="checkInVisible = false">取消</el-button><el-button type="primary" :loading="checkInBusy" @click="submitCheckIn">确认入住</el-button></template>
    </el-dialog>

    <el-dialog v-model="depositVisible" title="收订金" width="460px" @closed="depositError = ''">
      <el-alert v-if="depositError" :title="depositError" type="error" :closable="false" show-icon />
      <el-form label-width="110px" @submit.prevent="submitDeposit">
        <el-form-item label="收款方式" required><el-select v-model="depositForm.method" aria-label="收款方式" style="width: 100%"><el-option v-for="item in depositMethods" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="金额（元）" required><el-input v-model="depositForm.amount" maxlength="12" clearable placeholder="最多两位小数" /></el-form-item>
        <el-form-item label="外部参考"><el-input v-model="depositForm.methodReference" maxlength="120" clearable placeholder="选填" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="depositForm.note" maxlength="500" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="depositVisible = false">取消</el-button><el-button type="primary" :loading="depositBusy" @click="submitDeposit">登记收款</el-button></template>
    </el-dialog>

    <el-dialog v-model="refundVisible" title="订金退款" width="460px" @closed="refundError = ''">
      <el-alert v-if="refundError" :title="refundError" type="error" :closable="false" show-icon />
      <el-form label-width="110px" @submit.prevent="submitRefund">
        <el-form-item label="订金记录" required><el-select v-model="refundForm.paymentRecordId" aria-label="退款订金" style="width: 100%"><el-option v-for="item in deposits.filter(d => d.direction === 'RECEIPT' && d.folioId === null)" :key="item.id" :label="`#${item.id} · ${item.amount} 元`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="退款金额" required><el-input v-model="refundForm.amount" maxlength="12" clearable placeholder="不超过该笔订金未退回金额" /></el-form-item>
        <el-form-item label="退款原因" required><el-input v-model="refundForm.reason" maxlength="500" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="refundVisible = false">取消</el-button><el-button type="primary" :loading="refundBusy" @click="submitRefund">登记退款</el-button></template>
    </el-dialog>
  </section>
</template>
