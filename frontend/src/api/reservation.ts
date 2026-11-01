import { api } from './client'
import type { PageResult } from '../types/room'
import type { AvailabilityView, BlockView, ReservationDetail, ReservationView } from '../types/reservation'

// A fresh UUID per business action; retries reuse the same key so a timeout never books/confirms twice.
export const reservationKey = (): string => crypto.randomUUID()

export interface ReservationFilter {
  status: string
  arrivalFrom?: string
  arrivalTo?: string
  query?: string
  page: number
}

export const listReservations = (filter: ReservationFilter) => {
  const query = new URLSearchParams({ status: filter.status, page: String(filter.page), pageSize: '20' })
  if (filter.arrivalFrom) query.set('arrivalFrom', filter.arrivalFrom)
  if (filter.arrivalTo) query.set('arrivalTo', filter.arrivalTo)
  if (filter.query) query.set('query', filter.query)
  return api<PageResult<ReservationView>>(`/api/reservations?${query}`)
}

export const reservationDetail = (id: number) => api<ReservationDetail>(`/api/reservations/${id}`)

export interface ReservationCreateInput {
  arrivalDate: string
  departureDate: string
  roomTypeId: number
  source: string
  externalRef?: string
  contactName: string
  contactPhone?: string
  notes?: string
}
export function createReservation(key: string, input: ReservationCreateInput) {
  return api<ReservationDetail>('/api/reservations', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
}

export interface ReservationModifyInput {
  version: number
  arrivalDate?: string
  departureDate?: string
  roomTypeId?: number
  contactName?: string
  contactPhone?: string
  notes?: string
}
export function modifyReservation(id: number, key: string, input: ReservationModifyInput) {
  return api<ReservationDetail>(`/api/reservations/${id}/modify`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
}

export const confirmReservation = (id: number, key: string) =>
  api<ReservationDetail>(`/api/reservations/${id}/confirm`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({}) })

export function cancelReservation(id: number, key: string, reason: string) {
  return api<ReservationDetail>(`/api/reservations/${id}/cancel`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ reason }) })
}

export function markNoShow(id: number, key: string, reason: string) {
  return api<ReservationDetail>(`/api/reservations/${id}/mark-no-show`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ reason }) })
}

export function assignReservationRoom(id: number, key: string, roomId: number) {
  return api<ReservationDetail>(`/api/reservations/${id}/assign-room`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ roomId }) })
}

export interface ReservationCheckInInput {
  roomId: number
  expectedCheckOutAt: string
  guests: { name: string; documentType: string; documentNumber: string; phone?: string; primary: boolean }[]
}
export function checkInReservation(id: number, key: string, input: ReservationCheckInInput) {
  return api<{ reservationId: number; stayId: number }>(`/api/reservations/${id}/check-in`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
}

export const releaseExpiredReservations = () =>
  api<{ released: number }>('/api/reservations/release-expired', { method: 'POST', body: JSON.stringify({}) })

export const reservationDepositsKey = reservationKey
export function receiveReservationDeposit(id: number, key: string, method: string, amount: string, methodReference: string, note: string) {
  return api<DepositViewLike>(`/api/reservations/${id}/deposits`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ method, amount, methodReference: methodReference || null, note: note || null }) })
}
export function refundReservationDeposit(id: number, key: string, paymentRecordId: number, amount: string, reason: string) {
  return api<DepositViewLike>(`/api/reservations/${id}/deposits/refund`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ paymentRecordId, amount, reason }) })
}
interface DepositViewLike { id: number; direction: string; amount: string }

export const availability = (arrivalDate: string, departureDate: string) =>
  api<AvailabilityView>(`/api/availability?arrivalDate=${arrivalDate}&departureDate=${departureDate}`)

export const listBlocks = (includeReleased: boolean, page = 1) =>
  api<PageResult<BlockView>>(`/api/inventory/blocks?includeReleased=${includeReleased}&page=${page}&pageSize=20`)

export function createBlock(key: string, roomTypeId: number, startDate: string, endDate: string, reason: string) {
  return api<BlockView>('/api/inventory/blocks', { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ roomTypeId, startDate, endDate, reason }) })
}

export function releaseBlock(id: number, key: string) {
  return api<BlockView>(`/api/inventory/blocks/${id}/release`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({}) })
}
