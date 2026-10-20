export interface GuestInput {
  name: string; documentType: 'ID_CARD' | 'PASSPORT' | 'OTHER'; documentNumber: string
  phone: string; notes: string; primary: boolean
}
export interface CheckInInput { roomId: number; expectedCheckOutAt: string; guests: GuestInput[]; notes: string }
export interface StayGuest extends GuestInput { id: number }
export interface Stay {
  id: number; roomId: number; segmentId: number; roomNumber: string; roomTypeName: string
  status: 'IN_HOUSE' | 'CHECKED_OUT'; createdBy: number; operatorName: string; primaryGuestName: string
  guestCount: number; notes: string; checkInAt: string; expectedCheckOutAt: string
  actualCheckOutAt: string | null; checkedOutBy: number | null; createdAt: string; updatedAt: string; guests: StayGuest[] | null
}
export interface CheckoutResult {
  stayId: number; segmentId: number; roomId: number; taskId: number; checkedOutBy: number
  roomNumber: string; roomTypeName: string; actualCheckOutAt: string
  cleaningStatusAtCheckout: 'DIRTY'; taskStatusAtCreation: 'PENDING'
}
