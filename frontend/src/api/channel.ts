import { api } from './client'
import type {
  ChannelConnection, ChannelConnectionCreateInput, ChannelConnectionUpdateInput,
  ChannelEventPage, ChannelInboundEvent, ChannelMapping, ChannelMappingInput,
  ChannelOutboxEvent, ChannelProcessResult, ChannelReconcileRun,
} from '../types/channel'

export const channelList = () => api<ChannelConnection[]>('/api/channels')
export const channelDetail = (id: number) => api<ChannelConnection>(`/api/channels/${id}`)
export const createChannel = (body: ChannelConnectionCreateInput) =>
  api<ChannelConnection>('/api/channels', { method: 'POST', body: JSON.stringify(body) })
export const updateChannel = (id: number, body: ChannelConnectionUpdateInput) =>
  api<ChannelConnection>(`/api/channels/${id}`, { method: 'PATCH', body: JSON.stringify(body) })
export const channelMappings = (id: number) => api<ChannelMapping[]>(`/api/channels/${id}/mappings`)
export const addChannelMapping = (id: number, body: ChannelMappingInput) =>
  api<ChannelMapping>(`/api/channels/${id}/mappings`, { method: 'POST', body: JSON.stringify(body) })
export const deleteChannelMapping = (mappingId: number) =>
  api<{ deleted: boolean }>(`/api/channels/mappings/${mappingId}`, { method: 'DELETE' })
export function channelInbound(id: number, status: string, page = 1, pageSize = 20) {
  const query = new URLSearchParams({ status, page: String(page), pageSize: String(pageSize) })
  return api<ChannelEventPage<ChannelInboundEvent>>(`/api/channels/${id}/inbound?${query}`)
}
export function channelOutbox(id: number, status: string, page = 1, pageSize = 20) {
  const query = new URLSearchParams({ status, page: String(page), pageSize: String(pageSize) })
  return api<ChannelEventPage<ChannelOutboxEvent>>(`/api/channels/${id}/outbox?${query}`)
}
export const retryChannelInbound = (eventId: number) =>
  api<{ requeued: boolean }>(`/api/channels/inbound/${eventId}/retry`, { method: 'POST' })
export const retryChannelOutbox = (eventId: number) =>
  api<{ requeued: boolean }>(`/api/channels/outbox/${eventId}/retry`, { method: 'POST' })
export const channelProcessNow = (id: number, inboundLimit = 50, outboxLimit = 50) =>
  api<ChannelProcessResult>(`/api/channels/${id}/process-now`, { method: 'POST', body: JSON.stringify({ inboundLimit, outboxLimit }) })
export const channelReconcile = (id: number) =>
  api<{ connectionId: number; runs: ChannelReconcileRun[] }>(`/api/channels/${id}/reconcile`, { method: 'POST' })
export const channelReconciliations = (id: number) => api<ChannelReconcileRun[]>(`/api/channels/${id}/reconciliations`)
