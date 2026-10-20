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
  actualCheckOutAt: string | null; createdAt: string; updatedAt: string; guests: StayGuest[] | null
}
