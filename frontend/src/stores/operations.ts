import { reactive } from 'vue'
import { api } from '../api/client'
import { auth } from './auth'
export interface Notice { kind: 'OVERDUE_STAY' | 'STRANDED_TASK' | 'DELAYED_TASK' | 'CRITICAL_ISSUE'; targetId: number; roomNumber: string; since: string }
export interface Summary { generatedAt: string; overdueStays: number | null; unassignedTasks: number | null; delayedTasks: number | null; strandedTasks: number | null; criticalIssues: number; maintenanceStops: number; pendingWarningMinutes: number; cleaningWarningMinutes: number; items: Notice[] }
export const operations = reactive<{ data: Summary | null; error: string; loading: boolean; owner: number | null }>({ data: null, error: '', loading: false, owner: null })
let generation = 0
export function clearOperations() { generation++; operations.data = null; operations.error = ''; operations.loading = false; operations.owner = null }
export async function refreshOperations() {
  const owner = auth.user?.id
  if (!owner) { clearOperations(); return }
  if (operations.owner !== owner) { clearOperations(); operations.owner = owner }
  if (operations.loading) return
  const current = ++generation; operations.loading = true
  try { const data = await api<Summary>('/api/operations/summary'); if (generation === current && auth.user?.id === owner) { operations.data = data; operations.error = '' } }
  catch (e) { if (generation === current) { operations.data = null; operations.error = e instanceof Error ? e.message : '运营待办暂不可用' } }
  finally { if (generation === current) operations.loading = false }
}
