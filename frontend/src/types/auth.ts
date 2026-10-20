export type Role = 'ADMIN' | 'HOTEL_MANAGER' | 'FRONT_DESK' | 'HOUSEKEEPING_MANAGER' | 'HOUSEKEEPING_STAFF'
export interface User {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: Role[]
  permissions: string[]
}
export interface ApiResponse<T> { success: boolean; code: string | null; message: string; data: T }
