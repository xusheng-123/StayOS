export type HandoverScope = 'FRONT_DESK' | 'HOUSEKEEPING'
export type HandoverStatus = 'DRAFT' | 'SUBMITTED' | 'ACKNOWLEDGED'
export type HandoverItemCategory = 'OVERDUE_STAY' | 'STRANDED_TASK' | 'DELAYED_TASK' | 'CRITICAL_ISSUE' | 'MAINTENANCE_STOP' | 'CUSTOM'
export type HandoverEventType = 'CREATED' | 'UPDATED' | 'SUBMITTED' | 'REASSIGNED' | 'ACKNOWLEDGED'
export type HandoverOperation = 'HANDOVER_CREATE' | 'HANDOVER_SUBMIT' | 'HANDOVER_ACKNOWLEDGE'

export interface HandoverItemInput { category: HandoverItemCategory; content: string; sourceId?: number }
export interface HandoverCreateInput { scope: HandoverScope; receiverId: number; shiftStartAt: string; shiftEndAt: string; summary: string; items: HandoverItemInput[] }
export interface HandoverUpdateInput { version: number; receiverId: number; shiftStartAt: string; shiftEndAt: string; summary: string; items: HandoverItemInput[] }

export interface HandoverItem { id: number; position: number; category: HandoverItemCategory; content: string; sourceId: number | null }
export interface HandoverEvent { id: number; eventType: HandoverEventType; actorId: number; actorName: string; reason: string | null; detail: Record<string, unknown>; createdAt: string }
export interface HandoverSummary {
  id: number; scope: HandoverScope; status: HandoverStatus; version: number
  handoverById: number; handoverByName: string; receiverId: number; receiverName: string
  shiftStartAt: string; shiftEndAt: string; summary: string; itemCount: number
  createdAt: string; submittedAt: string | null; acknowledgedAt: string | null
}
export interface HandoverDetail {
  id: number; scope: HandoverScope; status: HandoverStatus; version: number
  handoverById: number; handoverByName: string; receiverId: number; receiverName: string
  shiftStartAt: string; shiftEndAt: string; summary: string
  createdAt: string; submittedAt: string | null; acknowledgedAt: string | null
  items: HandoverItem[]; events: HandoverEvent[]
}
export interface HandoverCandidate { id: number; displayName: string; username: string; roleNames: string[] }

export const handoverScopeNames: Record<HandoverScope, string> = { FRONT_DESK: '前台', HOUSEKEEPING: '客房' }
export const handoverStatusNames: Record<HandoverStatus, string> = { DRAFT: '草稿', SUBMITTED: '待接班确认', ACKNOWLEDGED: '接班已确认' }
export const handoverCategoryNames: Record<HandoverItemCategory, string> = {
  OVERDUE_STAY: '超过预计退房', STRANDED_TASK: '任务无人接手', DELAYED_TASK: '清洁处理较久',
  CRITICAL_ISSUE: '严重异常', MAINTENANCE_STOP: '维修停用', CUSTOM: '自定义事项',
}
export const handoverEventNames: Record<HandoverEventType, string> = {
  CREATED: '创建草稿', UPDATED: '修改草稿', SUBMITTED: '提交交班', REASSIGNED: '改派接班人', ACKNOWLEDGED: '确认接班',
}
