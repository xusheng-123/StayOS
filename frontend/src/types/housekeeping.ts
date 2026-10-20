export interface HousekeepingTask {
  id: number; roomId: number; roomNumber: string; floor: number; roomTypeName: string
  taskType: 'CHECKOUT_CLEANING'; source: 'CHECKOUT'; status: 'PENDING'; createdAt: string; notes: string
}
