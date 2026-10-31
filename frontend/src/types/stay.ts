export interface GuestInput {
  name: string; documentType: 'ID_CARD' | 'PASSPORT' | 'OTHER'; documentNumber: string
  phone: string; notes: string; primary: boolean
}
export interface CheckInInput { roomId: number; expectedCheckOutAt: string; guests: GuestInput[]; notes: string }
export interface StayGuest extends GuestInput { id: number; version: number }
export interface Stay {
  id: number; roomId: number; segmentId: number; roomNumber: string; roomTypeName: string; version: number
  status: 'IN_HOUSE' | 'CHECKED_OUT'; createdBy: number; operatorName: string; primaryGuestName: string
  guestCount: number; notes: string; checkInAt: string; expectedCheckOutAt: string
  actualCheckOutAt: string | null; checkedOutBy: number | null; createdAt: string; updatedAt: string; guests: StayGuest[] | null; roomSegments?: RoomSegment[]; roomChanges?: RoomChange[]
}
export interface CheckoutResult {
  stayId: number; segmentId: number; roomId: number; taskId: number; checkedOutBy: number
  roomNumber: string; roomTypeName: string; actualCheckOutAt: string
  cleaningStatusAtCheckout: 'DIRTY'; taskStatusAtCreation: 'PENDING'
}

export interface RoomChangeInput { segmentId: number; targetRoomId: number; reasonType: 'GUEST_REQUEST' | 'ROOM_ISSUE' | 'OTHER'; reason: string; issueId?: number }
export interface RoomChange {
 id: number; stayRecordId: number; oldRoomId: number; newRoomId: number; oldSegmentId: number; newSegmentId: number
 reasonType: RoomChangeInput['reasonType']; reason: string; issueId: number | null; operatedBy: number; operatorName: string
 oldRoomNumber: string; newRoomNumber: string; oldRoomTypeName: string; newRoomTypeName: string
 changedAt: string; createdAt: string; taskId: number
}
export interface RoomSegment { id: number; roomId: number; roomNumber: string; roomTypeName: string; enteredAt: string; exitedAt: string | null; endReason: 'ROOM_CHANGE' | 'CHECK_OUT' | null }
export const changeReasonNames = { GUEST_REQUEST: '住客主动要求', ROOM_ISSUE: '房间设施或环境问题', OTHER: '其他原因' }

export interface StayExtensionInput { segmentId: number; version: number; newExpectedCheckOutAt: string; reason: string }
export interface StayExtension {
  id: number; stayRecordId: number; segmentId: number; roomId: number; roomNumber: string
  oldExpectedCheckOutAt: string; newExpectedCheckOutAt: string; reason: string
  operatorId: number; operatorName: string; operatedAt: string; stayVersion: number
}

export type GuestCorrectionField = 'name' | 'documentType' | 'documentNumber' | 'phone'
export interface GuestCorrectionInput { version: number; changes: Partial<Record<GuestCorrectionField, string>>; reason: string }
export interface GuestCorrection {
  id: number; stayRecordId: number; guestId: number; guestVersion: number; operatorId: number; operatorName: string
  operatedAt: string; reason: string; changedFields: GuestCorrectionField[]
  before: Record<GuestCorrectionField, string>; after: Record<GuestCorrectionField, string>
}
export const correctionFieldNames = { name: '姓名', documentType: '证件类型', documentNumber: '证件号码', phone: '联系电话' }
