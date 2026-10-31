import { api } from './client'
import type { PageResult } from '../types/room'
import type { BusinessDayView, CloseResultView, DailyReportItem, ShiftView } from '../types/cashier'

// A fresh UUID per business action; retries reuse the same key so a timeout never opens/confirms twice.
export const cashierKey = (): string => crypto.randomUUID()

export const shiftList = (scope: string, status: string, page = 1, pageSize = 20) => {
  const query = new URLSearchParams({ scope, status, page: String(page), pageSize: String(pageSize) })
  return api<PageResult<ShiftView>>(`/api/cashier-shifts?${query}`)
}
export function openShift(key: string, openedFloat: string) {
  return api<ShiftView>('/api/cashier-shifts', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ openedFloat }) })
}
export function submitShift(id: number, key: string, countedCash: string, differenceReason: string, version: number) {
  return api<ShiftView>(`/api/cashier-shifts/${id}/submit`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ countedCash, differenceReason: differenceReason.trim() || null, version }) })
}
export function confirmShift(id: number, key: string, version: number) {
  return api<ShiftView>(`/api/cashier-shifts/${id}/confirm`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ version }) })
}

export const businessDays = (limit = 14) => api<BusinessDayView[]>(`/api/business-days?limit=${limit}`)
export const closeBusinessDay = (date: string, key: string) =>
  api<CloseResultView>(`/api/business-days/${date}/close`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({}) })

export const dailyReports = (from: string, to: string, page = 1, pageSize = 20) => {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  return api<PageResult<DailyReportItem>>(`/api/reports/daily?${query}`)
}
