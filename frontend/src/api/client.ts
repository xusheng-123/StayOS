import type { ApiResponse } from '../types/auth'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message) }
}
async function send<T>(path: string, options: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000) })
  } catch { throw new ApiError(0, 'NETWORK_ERROR', '暂时无法连接服务器，请检查服务后重试') }
  let body: ApiResponse<T>
  try { body = await response.json() as ApiResponse<T> }
  catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务器响应异常，请稍后重试') }
  if (!response.ok || !body.success) throw new ApiError(response.status, body.code || 'REQUEST_FAILED', body.message || '操作失败')
  return body.data
}
export async function api<T>(path: string, options: RequestInit = {}, notifyUnauthorized = true): Promise<T> {
  try {
    const headers = new Headers(options.headers)
    const method = (options.method || 'GET').toUpperCase()
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      // A fresh token also handles login/logout and Session ID changes in another tab.
      const csrf = await send<{ headerName: string; token: string }>('/api/auth/csrf', {})
      headers.set(csrf.headerName, csrf.token)
      if (options.body) headers.set('Content-Type', 'application/json')
    }
    return await send<T>(path, { ...options, headers })
  } catch (error) {
    if (notifyUnauthorized && error instanceof ApiError && error.status === 401) window.dispatchEvent(new Event('stayos:unauthorized'))
    throw error
  }
}
