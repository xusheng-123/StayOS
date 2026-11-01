import { api } from './client'
import type { CustomerProfile, CustomerProfileDetail, CreatedProfile, Page, ProfileInput, ProfileUpdate, ProfileStayLink } from '../types/customer'
import type { Stay } from '../types/stay'

const profileKey = () => crypto.randomUUID()

export function customerList(query: string, status: string, page: number, pageSize = 20) {
  const q = new URLSearchParams({ status, page: String(page), pageSize: String(pageSize) })
  if (query) q.set('query', query)
  return api<Page<CustomerProfile>>(`/api/customer-profiles?${q}`)
}
export function customerDetail(id: number) {
  return api<CustomerProfileDetail>(`/api/customer-profiles/${id}`)
}
export function customerCreate(input: ProfileInput) {
  return api<CreatedProfile>('/api/customer-profiles', { method: 'POST', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify(input) })
}
export function customerUpdate(id: number, input: ProfileUpdate) {
  return api<CustomerProfileDetail>(`/api/customer-profiles/${id}`, { method: 'PUT', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify(input) })
}
export function customerSetStatus(id: number, status: 'ACTIVE' | 'DISABLED') {
  return api<CustomerProfileDetail>(`/api/customer-profiles/${id}/set-status`, { method: 'POST', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify({ status }) })
}
export function customerMerge(id: number, targetId: number) {
  return api<CustomerProfileDetail>(`/api/customer-profiles/${id}/merge`, { method: 'POST', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify({ targetId }) })
}
export function customerLinkStay(id: number, stayRecordId: number, guestId?: number) {
  return api<ProfileStayLink>(`/api/customer-profiles/${id}/stays`, { method: 'POST', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify({ stayRecordId, guestId: guestId ?? null }) })
}
export function customerDetachStay(linkId: number) {
  return api<CustomerProfileDetail>(`/api/customer-profiles/stay-links/${linkId}/detach`, { method: 'POST', headers: { 'Idempotency-Key': profileKey() }, body: JSON.stringify({ linkId }) })
}
// 关联入住时按主要联系人姓名检索在住与历史入住（stay:manage 既有接口，含状态过滤）。
export function staySearch(guestName: string) {
  const q = new URLSearchParams({ status: 'ALL', pageSize: '20' })
  if (guestName) q.set('guestName', guestName)
  return api<{ items: Stay[]; total: number }>(`/api/stays?${q}`)
}
