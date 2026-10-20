export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'
export interface HousekeepingTask {
  id: number; roomId: number; roomNumber: string; floor: number; roomTypeName: string
  taskType: 'CHECKOUT_CLEANING' | 'MANUAL_CLEANING' | 'ROOM_CHANGE_CLEANING'; source: 'CHECKOUT' | 'MANUAL' | 'ROOM_CHANGE'; status: TaskStatus
  createdAt: string; createdBy: number; notes: string; completionNotes: string; version: number
  assignedTo: number | null; assignedBy: number | null; assigneeName: string | null
  assignedAt: string | null; claimedAt: string | null; startedAt: string | null; completedAt: string | null
  startedBy: number | null; completedBy: number | null
}
export interface TaskLog {
  id: number; taskId: number; action: string; fromStatus: TaskStatus | null; toStatus: TaskStatus
  fromAssignee: number | null; toAssignee: number | null; operatorId: number; taskVersion: number
  fromAssigneeName: string | null; toAssigneeName: string | null; operatorName: string; operatedAt: string; reason: string
}
export interface TaskStatistics { pending: number; inProgress: number; completedToday: number; mine: number; unassigned: number }
export interface Employee { id: number; displayName: string }
