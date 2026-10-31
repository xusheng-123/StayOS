import { api } from './client'
import type { PageResult } from '../types/room'
import type {
  HandoverCandidate, HandoverCreateInput, HandoverDetail, HandoverOperation,
  HandoverScope, HandoverSummary, HandoverUpdateInput,
} from '../types/handover'

export function handoverList(scope: HandoverScope, status: string, involving: string, page = 1, pageSize = 20) {
  const query = new URLSearchParams({ scope, page: String(page), pageSize: String(pageSize) })
  if (status) query.set('status', status)
  if (involving) query.set('involving', involving)
  return api<PageResult<HandoverSummary>>(`/api/handovers?${query}`)
}
export const handoverDetail = (id: number) => api<HandoverDetail>(`/api/handovers/${id}`)
export const handoverCandidates = (scope: HandoverScope) => api<HandoverCandidate[]>(`/api/handovers/candidates?scope=${scope}`)
export function createHandover(key: string, body: HandoverCreateInput) {
  return api<HandoverDetail>('/api/handovers', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export function updateHandover(id: number, body: HandoverUpdateInput) {
  return api<HandoverDetail>(`/api/handovers/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}
export function submitHandover(id: number, key: string, version: number) {
  return api<HandoverDetail>(`/api/handovers/${id}/submit`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ version }) })
}
export function acknowledgeHandover(id: number, key: string, version: number) {
  return api<HandoverDetail>(`/api/handovers/${id}/acknowledge`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ version }) })
}
export function reassignHandover(id: number, body: { version: number; receiverId: number; reason: string }) {
  return api<HandoverDetail>(`/api/handovers/${id}/reassign`, { method: 'POST', body: JSON.stringify(body) })
}
export const handoverReceipt = (key: string, operation: HandoverOperation) =>
  api<HandoverDetail>(`/api/handovers/result/${encodeURIComponent(key)}?operation=${operation}`)
