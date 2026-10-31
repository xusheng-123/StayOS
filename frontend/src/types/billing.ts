// Billing domain types. All money values are plain decimal strings from the API (never floats).
export type FolioStatus = 'OPEN' | 'CLOSED'
export type PricingStatus = 'PRICED' | 'PRICING_REQUIRED'
export type ChargeCategory = 'ROOM_NIGHT' | 'SERVICE' | 'MANUAL' | 'REVERSAL' | 'CORRECTION'
export type PaymentMethod = 'CASH' | 'BANK_CARD' | 'QR_PAYMENT' | 'BANK_TRANSFER' | 'OTHER'
export type PaymentPurpose = 'PAYMENT' | 'DEPOSIT'
export type PaymentDirection = 'RECEIPT' | 'REFUND'
export type FolioEventType = 'OPENED' | 'CLOSED' | 'REOPENED'
export type ServiceOrderStatus = 'REQUESTED' | 'FULFILLED' | 'CANCELLED'
export type AdjustMode = 'REVERSAL' | 'CORRECTION'

export interface PolicyInput {
  billingCutoffTime: string; checkoutDeadline?: string
  earlyCheckinMode: string; lateCheckoutMode: string; earlyCheckoutMode: string
  roomChangePriceMode: string; extensionPriceMode: string; notes?: string
}
export interface PolicyView {
  id: number; version: number; billingCutoffTime: string; checkoutDeadline: string | null
  earlyCheckinMode: string; lateCheckoutMode: string; earlyCheckoutMode: string
  roomChangePriceMode: string; extensionPriceMode: string; notes: string | null
  active: boolean; createdByName: string; createdAt: string
}

export interface RatePlanCreateInput { roomTypeId: number; planCode: string; planName: string; nightlyPrice: string }
export interface RatePlanUpdateInput { planName: string; nightlyPrice: string; enabled?: boolean; version: number }
export interface RatePlanView {
  id: number; roomTypeId: number; roomTypeCode: string; roomTypeName: string
  planCode: string; planName: string; pricingUnit: string; nightlyPrice: string
  enabled: boolean; version: number; createdAt: string; updatedAt: string
}
export interface RateDayInput { stayDate: string; nightlyPrice: string }
export interface RateDayView { ratePlanId: number; stayDate: string; nightlyPrice: string; updatedAt: string }

export interface CatalogCreateInput { itemCode: string; itemName: string; pricingUnit: string; unitPrice: string }
export interface CatalogUpdateInput { itemName: string; pricingUnit: string; unitPrice: string; enabled?: boolean; version: number }
export interface CatalogView {
  id: number; itemCode: string; itemName: string; pricingUnit: string; unitPrice: string
  enabled: boolean; version: number; createdAt: string; updatedAt: string
}

export interface FolioSummary {
  charges: string; credits: string; netReceivable: string
  receipts: string; refunds: string; netReceived: string; balance: string
}
export interface FolioView {
  id: number; stayRecordId: number; status: FolioStatus; pricingStatus: PricingStatus
  chargesEffectiveFrom: string | null; currency: string; version: number
  openedByName: string; openedAt: string; closedAt: string | null; closedByName: string | null
  stayVersion: number; stayStatus: string; roomNumber: string; roomTypeName: string; primaryGuestName: string
  checkInAt: string; expectedCheckOutAt: string; actualCheckOutAt: string | null
  checkInDate: string; currentBusinessDate: string; summary: FolioSummary
}
export interface FolioListItem {
  id: number; stayRecordId: number; status: FolioStatus; pricingStatus: PricingStatus
  roomNumber: string; primaryGuestName: string; checkInAt: string; closedAt: string | null
  charges: string; netReceived: string; balance: string
}
export interface ChargeView {
  id: number; folioId: number; businessDate: string; direction: 'CHARGE' | 'CREDIT'; category: ChargeCategory
  name: string; pricingUnit: string; quantity: number; unitPrice: string; amount: string; currency: string
  policyVersion: number | null; ratePlanId: number | null; catalogItemId: number | null
  sourceType: string; sourceKey: string; reversalOf: number | null; note: string | null
  createdByName: string; createdAt: string
}
export interface PaymentView {
  id: number; folioId: number; direction: PaymentDirection; method: PaymentMethod; purpose: PaymentPurpose
  amount: string; currency: string; methodReference: string | null; refundOf: number | null
  originalPaymentId: number | null; originalRemainder: string | null; note: string | null
  receivedByName: string; receivedAt: string
}
export interface FolioEventView { id: number; eventType: FolioEventType; actorName: string; note: string | null; createdAt: string }
export interface FolioDetail { folio: FolioView; charges: ChargeView[]; payments: PaymentView[]; events: FolioEventView[] }

export interface ChargeInput { category: 'ROOM_NIGHT' | 'MANUAL'; businessDate: string; ratePlanId?: number; title?: string; unitPrice?: string; quantity?: number; note?: string }
export interface AdjustInput { originalChargeId: number; mode: AdjustMode; amount?: string; reason: string }
export interface PaymentInput { method: PaymentMethod; methodReference?: string; purpose: PaymentPurpose; amount: string; note?: string }
export interface RefundInput { paymentRecordId: number; amount: string; reason: string }

export interface ServiceOrderItemInput { catalogItemId: number; quantity: number }
export interface ServiceOrderCreateInput { items: ServiceOrderItemInput[]; note?: string }
export interface ServiceOrderUpdateInput { items: ServiceOrderItemInput[]; note?: string; version: number }
export interface ServiceOrderItemView { id: number; catalogItemId: number; itemName: string; unitPrice: string; quantity: number; amount: string }
export interface ServiceOrderView {
  id: number; stayRecordId: number; status: ServiceOrderStatus; note: string | null
  requestedByName: string; fulfilledByName: string | null; fulfilledAt: string | null
  cancelledByName: string | null; cancelledAt: string | null; cancelReason: string | null
  version: number; createdAt: string; items: ServiceOrderItemView[]; totalAmount: string
}

export const folioStatusNames: Record<FolioStatus, string> = { OPEN: '未结账', CLOSED: '已结账' }
export const pricingStatusNames: Record<PricingStatus, string> = { PRICED: '全部可计价', PRICING_REQUIRED: '接入日之后计价' }
export const chargeCategoryNames: Record<ChargeCategory, string> = {
  ROOM_NIGHT: '房费', SERVICE: '服务消费', MANUAL: '手工费用', REVERSAL: '全额冲正', CORRECTION: '部分调整',
}
export const paymentMethodNames: Record<PaymentMethod, string> = {
  CASH: '现金', BANK_CARD: '银行卡', QR_PAYMENT: '扫码支付', BANK_TRANSFER: '银行转账', OTHER: '其他',
}
export const paymentPurposeNames: Record<PaymentPurpose, string> = { PAYMENT: '房费收款', DEPOSIT: '预收定金' }
export const folioEventNames: Record<FolioEventType, string> = { OPENED: '开立客账', CLOSED: '结账', REOPENED: '重开客账' }
export const serviceOrderStatusNames: Record<ServiceOrderStatus, string> = { REQUESTED: '待履约', FULFILLED: '已履约', CANCELLED: '已取消' }
export const policyModeOptions = {
  earlyCheckin: [{ value: 'FULL_NIGHT', label: '按整晚计费' }, { value: 'HOURLY', label: '按小时计费' }, { value: 'FREE', label: '免费' }, { value: 'DECLINED', label: '暂不接待' }],
  lateCheckout: [{ value: 'FREE', label: '免费' }, { value: 'HOURLY', label: '按小时计费' }, { value: 'FULL_NIGHT', label: '按整晚计费' }, { value: 'DECLINED', label: '暂不提供' }],
  earlyCheckout: [{ value: 'FULL_NIGHT', label: '按整晚计费' }, { value: 'CREDIT_UNUSED', label: '退还未住晚数' }, { value: 'DECLINED', label: '暂不退还' }],
  roomChange: [{ value: 'BY_NEW_SEGMENT', label: '按新房间价格' }, { value: 'ORIGINAL_PRICE', label: '沿用原价格' }, { value: 'DECLINED', label: '暂不支持' }],
  extension: [{ value: 'CURRENT_RATE', label: '按当前门市价' }, { value: 'RATE_AT_CHECKIN', label: '按入住时价格' }, { value: 'DECLINED', label: '暂不支持' }],
}
