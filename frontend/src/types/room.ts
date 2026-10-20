export type CleaningStatus = 'CLEAN' | 'DIRTY' | 'CLEANING' | 'INSPECTION'
export type Availability = 'DISABLED' | 'OCCUPIED' | 'OUT_OF_SERVICE' | 'CLEANING' | 'INSPECTION' | 'DIRTY' | 'UNAVAILABLE' | 'VACANT_CLEAN'
export interface PageResult<T> { items: T[]; total: number; page: number; pageSize: number }
export interface RoomType {
  id: number; typeCode: string; typeName: string; description: string; bedDescription: string
  suggestedOccupancy: number; enabled: boolean; version: number; createdAt: string; updatedAt: string; roomCount?: number
}
export interface Room {
  id: number; roomNumber: string; floor: number; roomTypeId: number; notes: string
  enabled: boolean; cleaningStatus: CleaningStatus; version: number; createdAt: string; updatedAt: string
  roomTypeName: string; roomTypeCode: string; roomTypeEnabled: boolean; occupied: boolean
  hasOpenIssue: boolean; hasCriticalIssue: boolean; hasManualMaintenanceStop: boolean;
  hasBlockingIssue: boolean; hasOpenCleaningTask: boolean; hasUsageHistory: boolean
  occupancyStatus: 'VACANT' | 'OCCUPIED'; maintenanceStatus: 'NORMAL' | 'OUT_OF_SERVICE'
  enabledStatus: 'ENABLED' | 'DISABLED'; canCheckIn: boolean; availability: Availability
}
export interface RoomStatistics {
  total: number; available: number; occupied: number; dirty: number; cleaning: number
  inspection: number; outOfService: number; disabled: number
}
export interface RoomBoard extends PageResult<Room> { statistics: RoomStatistics }
export interface Floor { floor: number; roomCount: number }
export interface RoomFilters {
  roomNumber: string; roomTypeId?: number; floor?: number; availability?: Availability; enabled?: boolean
}
