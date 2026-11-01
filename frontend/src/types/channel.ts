// 渠道连接（V4.0）：连接、映射、入站/出站事件与对账运行的只读视图。
// 凭据只出现“引用名”，任何视图都不含真实密钥值。
export interface ChannelConnection {
  id: number
  supplierCode: string
  name: string
  status: 'ACTIVE' | 'PAUSED' | 'BLOCKED_EXTERNAL'
  baseUrl: string | null
  credentialRef: string
  callbackSecretRef: string
  protocolVersion: number
  lastInboundAt: string | null
  lastOutboundAt: string | null
  lastSyncAt: string | null
  lastError: string | null
  notes: string
  version: number
  createdAt: string
  pendingInbound: number
  manualInbound: number
  pendingOutbox: number
  manualOutbox: number
  recentAttempts?: ChannelAttempt[]
}

export interface ChannelAttempt {
  id: number
  direction: 'OUTBOUND' | 'INBOUND_PROCESS'
  eventKind: 'OUTBOX' | 'INBOUND'
  eventId: number
  outcome: 'SUCCESS' | 'FAILURE' | 'SKIPPED'
  detail: string | null
  durationMs: number | null
  attemptedAt: string
}

export interface ChannelMapping {
  id: number
  connectionId: number
  kind: 'ROOM_TYPE' | 'RATE_PLAN'
  localId: number
  externalCode: string
  active: boolean
  createdAt: string
  localCode: string | null
  localName: string | null
}

export interface ChannelInboundEvent {
  id: number
  connectionId: number
  externalEventId: string
  eventType: string
  sequenceNo: number
  status: 'PENDING' | 'PROCESSED' | 'OBSOLETE' | 'MANUAL'
  attempts: number
  nextAttemptAt: string | null
  lastError: string | null
  resultReservationId: number | null
  receivedAt: string
  processedAt: string | null
}

export interface ChannelOutboxEvent {
  id: number
  connectionId: number
  aggregateType: string
  aggregateId: number
  eventType: string
  status: 'PENDING' | 'SENT' | 'MANUAL'
  attempts: number
  nextAttemptAt: string | null
  lastError: string | null
  createdAt: string
  sentAt: string | null
}

export interface ChannelReconcileRun {
  id: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  checkedCount: number
  missingLocalCount: number
  missingRemoteCount: number
  mismatchCount: number
  detail: string | null
  lastError: string | null
  startedAt: string
  finishedAt: string | null
}

export interface ChannelConnectionCreateInput {
  supplierCode: string
  name: string
  baseUrl?: string
  credentialRef: string
  callbackSecretRef: string
  notes?: string
}

export interface ChannelConnectionUpdateInput {
  version: number
  status?: ChannelConnection['status']
  baseUrl?: string | null
  credentialRef?: string
  callbackSecretRef?: string
  notes?: string | null
}

export interface ChannelMappingInput {
  kind: 'ROOM_TYPE' | 'RATE_PLAN'
  localId: number
  externalCode: string
}

export interface ChannelProcessResult {
  inbound: { processed: number; obsolete: number; failed: number }
  outbound: { sent: number; failed: number; skipped: number }
}

export interface ChannelEventPage<T> { total: number; items: T[] }
