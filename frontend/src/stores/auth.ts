import { reactive } from 'vue'
import type { User } from '../types/auth'
import { api, ApiError } from '../api/client'

export const auth = reactive<{ user: User | null; checked: boolean }>({ user: null, checked: false })
export function can(permission: string): boolean { return auth.user?.permissions.includes(permission) ?? false }
export async function refreshUser(): Promise<void> {
  try { auth.user = await api<User>('/api/auth/me', {}, false) }
  catch (error) {
    if (error instanceof ApiError && error.status === 401) auth.user = null
    else throw error
  }
  auth.checked = true
}
export async function login(username: string, password: string): Promise<void> {
  auth.user = await api<User>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }, false)
  auth.checked = true
}
export async function logout(): Promise<void> {
  await api('/api/auth/logout', { method: 'POST' })
  auth.user = null; auth.checked = true
}
window.addEventListener('stayos:unauthorized', () => { auth.user = null; auth.checked = true })
