// 客历档案（V5.0 有限客历）：证件/电话只出现掩码，任何接口不返回明文。
export interface CustomerProfile {
  id: number; displayName: string; documentType: string; documentMask: string; phoneMask: string
  notes: string; status: 'ACTIVE' | 'DISABLED' | 'MERGED'; mergedIntoId: number | null
  version: number; createdAt: string; updatedAt: string
}
export interface MergedTarget { id: number; displayName: string; documentMask: string }
export interface ProfileStayLink {
  linkId: number; stayRecordId: number; guestId: number; guestName: string; primary: boolean
  stayStatus: 'IN_HOUSE' | 'CHECKED_OUT'; checkInAt: string; expectedCheckOutAt: string; roomNumber: string | null
}
export interface CustomerProfileDetail extends CustomerProfile {
  mergedInto: MergedTarget | null; stays: ProfileStayLink[]
}
export interface SimilarProfile { id: number; displayName: string; documentMask: string; phoneMask: string; status: string }
export interface CreatedProfile { profile: CustomerProfileDetail; similarActiveProfiles: SimilarProfile[] }
export interface ProfileInput { name: string; documentType: string; documentNumber: string; phone?: string; notes?: string }
export interface ProfileUpdate extends ProfileInput { version: number }
export interface Page<T> { items: T[]; total: number; page: number; pageSize: number }
