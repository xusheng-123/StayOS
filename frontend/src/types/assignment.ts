export interface AssignmentEmployee {
  id: number; username: string; displayName: string; enabled: boolean
  pending: number; inProgress: number; completedToday: number
}
export interface AssignmentRoom {
  roomId: number; roomNumber: string; floor: number; roomTypeId: number; roomTypeName: string
  cleaningStatus: string; occupied: boolean; enabled: boolean; hasBlockingIssue: boolean
  taskId: number | null; taskStatus: string | null; assignedTo: number | null; assigneeName: string | null
  taskVersion: number | null; assignmentState: string; assignable: boolean
}
export interface AssignmentReceipt {
  id: number; operatorId: number; assigneeId: number; createdAt: string
  items: { roomId: number; taskId: number; roomNumber: string; manualCreated: boolean; assignmentLogId: number }[]
}
