import { api } from './client'
import type { PageResult } from '../types/room'
import type {
  AdjustInput, CatalogCreateInput, CatalogUpdateInput, CatalogView, ChargeInput, ChargeView, FolioDetail,
  FolioListItem, PaymentInput, PaymentView, PolicyInput, PolicyView, RateDayInput, RateDayView,
  RatePlanCreateInput, RatePlanUpdateInput, RatePlanView, RefundInput, ServiceOrderCreateInput,
  ServiceOrderUpdateInput, ServiceOrderView,
} from '../types/billing'

// A fresh UUID per business action; retries reuse the same key so a timeout never double-charges.
export const billingKey = (): string => crypto.randomUUID()

export const policyOverview = () => api<{ active: PolicyView | null; history: PolicyView[] }>('/api/billing/policies')
export const createPolicy = (body: PolicyInput) => api<PolicyView>('/api/billing/policies', { method: 'POST', body: JSON.stringify(body) })
export const ratePlans = () => api<RatePlanView[]>('/api/billing/rate-plans')
export const createRatePlan = (body: RatePlanCreateInput) => api<RatePlanView>('/api/billing/rate-plans', { method: 'POST', body: JSON.stringify(body) })
export const updateRatePlan = (id: number, body: RatePlanUpdateInput) => api<RatePlanView>(`/api/billing/rate-plans/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export const rateDays = (planId: number, from: string, to: string) => api<RateDayView[]>(`/api/billing/rate-plans/${planId}/rate-days?from=${from}&to=${to}`)
export const upsertRateDay = (planId: number, body: RateDayInput) => api<RateDayView>(`/api/billing/rate-plans/${planId}/rate-days`, { method: 'PUT', body: JSON.stringify(body) })
export const serviceCatalog = () => api<CatalogView[]>('/api/billing/service-catalog')
export const createCatalogItem = (body: CatalogCreateInput) => api<CatalogView>('/api/billing/service-catalog', { method: 'POST', body: JSON.stringify(body) })
export const updateCatalogItem = (id: number, body: CatalogUpdateInput) => api<CatalogView>(`/api/billing/service-catalog/${id}`, { method: 'PUT', body: JSON.stringify(body) })

export const folioList = (status: string, page = 1, pageSize = 20) => {
  const query = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (status) query.set('status', status)
  return api<PageResult<FolioListItem>>(`/api/billing/folios?${query}`)
}
export const folioDetail = (id: number) => api<FolioDetail>(`/api/folios/${id}`)
export const folioByStay = (stayId: number) => api<FolioDetail>(`/api/stays/${stayId}/folio`)
export function openFolio(stayId: number, key: string, chargesFrom?: string) {
  return api<FolioDetail>(`/api/stays/${stayId}/folio`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(chargesFrom ? { chargesFrom } : {}) })
}
export function postCharge(folioId: number, key: string, body: ChargeInput) {
  return api<ChargeView>(`/api/folios/${folioId}/charges`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export function adjustCharge(folioId: number, key: string, body: AdjustInput) {
  return api<ChargeView>(`/api/folios/${folioId}/adjustments`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export function receivePayment(folioId: number, key: string, body: PaymentInput) {
  return api<PaymentView>(`/api/folios/${folioId}/payments`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export function refundPayment(folioId: number, key: string, body: RefundInput) {
  return api<PaymentView>(`/api/folios/${folioId}/refunds`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export function closeFolio(folioId: number, key: string, version: number, acknowledgeOutstanding = false) {
  return api<FolioDetail>(`/api/folios/${folioId}/close`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(acknowledgeOutstanding ? { version, acknowledgeOutstanding: true } : { version }) })
}
export function reopenFolio(folioId: number, key: string, version: number, reason: string) {
  return api<FolioDetail>(`/api/folios/${folioId}/reopen`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ version, reason }) })
}

export const serviceOrdersByStay = (stayId: number) => api<ServiceOrderView[]>(`/api/stays/${stayId}/service-orders`)
export function createServiceOrder(stayId: number, key: string, body: ServiceOrderCreateInput) {
  return api<ServiceOrderView>(`/api/stays/${stayId}/service-orders`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(body) })
}
export const updateServiceOrder = (id: number, body: ServiceOrderUpdateInput) => api<ServiceOrderView>(`/api/service-orders/${id}`, { method: 'PUT', body: JSON.stringify(body) })
export function fulfillServiceOrder(id: number, key: string) {
  return api<ServiceOrderView>(`/api/service-orders/${id}/fulfill`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({}) })
}
export function cancelServiceOrder(id: number, key: string, reason: string) {
  return api<ServiceOrderView>(`/api/service-orders/${id}/cancel`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ reason }) })
}
