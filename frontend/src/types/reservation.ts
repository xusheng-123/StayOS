// 预订与未来库存（V3.0）。日期一律为酒店营业时区的 ISO yyyy-MM-dd；金额为服务端十进制字符串。
export type ReservationStatus = 'HELD' | 'CONFIRMED' | 'CANCELLED' | 'NO_SHOW' | 'CHECKED_IN'
export type ReservationSource = 'PHONE' | 'WALK_IN' | 'OTA' | 'OTHER'

export const reservationStatusNames: Record<ReservationStatus, string> = {
  HELD: '保留单', CONFIRMED: '已确认', CANCELLED: '已取消', NO_SHOW: '未到店', CHECKED_IN: '已入住',
}
export const reservationSourceNames: Record<ReservationSource, string> = {
  PHONE: '电话', WALK_IN: '线下上门', OTA: 'OTA/渠道', OTHER: '其他',
}

export interface ReservationView {
  id: number
  arrivalDate: string
  departureDate: string
  nights: number
  roomTypeId: number
  roomTypeCode: string
  roomTypeName: string
  status: ReservationStatus
  source: ReservationSource
  externalRef: string | null
  contactName: string
  contactPhoneMask: string
  heldUntil: string | null
  confirmedAt: string | null
  confirmedByName: string | null
  cancelledAt: string | null
  cancelledByName: string | null
  cancelReason: string | null
  noShowAt: string | null
  noShowByName: string | null
  checkedInStayId: number | null
  assignedRoomId: number | null
  assignedRoomNumber: string | null
  version: number
  notes: string
  createdAt: string
  createdByName: string
}

export interface ReservationEventView {
  id: number
  action: string
  actorName: string
  detail: string
  createdAt: string
}

export interface DepositView {
  id: number
  direction: 'RECEIPT' | 'REFUND'
  method: string
  purpose: string | null
  amount: string
  currency: string
  folioId: number | null
  methodReference: string | null
  note: string | null
  receivedAt: string
  receivedByName: string
}

export interface ReservationDetail {
  reservation: ReservationView
  events: ReservationEventView[]
  deposits: DepositView[]
}

export interface NightAvailability {
  date: string
  enabledRooms: number
  occupied: number
  sold: number
  blocked: number
  available: number
}

export interface RoomTypeAvailability {
  roomTypeId: number
  typeCode: string
  typeName: string
  nights: NightAvailability[]
  bookable: number
}

export interface AvailabilityView {
  arrivalDate: string
  departureDate: string
  roomTypes: RoomTypeAvailability[]
}

export interface BlockView {
  id: number
  roomTypeId: number
  roomTypeCode: string
  roomTypeName: string
  startDate: string
  endDate: string
  reason: string
  createdByName: string
  createdAt: string
  releasedByName: string | null
  releasedAt: string | null
}

export interface ReservationTypeOption {
  id: number
  typeCode: string
  typeName: string
}
