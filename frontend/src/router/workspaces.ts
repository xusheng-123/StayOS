import type { Role } from '../types/auth'
import { auth, can } from '../stores/auth'

export const roleNames: Record<Role, string> = {
  ADMIN: '系统管理员', HOTEL_MANAGER: '酒店总经理', FRONT_DESK: '前台员工',
  HOUSEKEEPING_MANAGER: '客房主管', HOUSEKEEPING_STAFF: '客房服务员',
}
export const workspaces = [
  { key: 'admin', title: '系统管理员工作区', label: '系统管理', permission: 'system:manage', number: '01', description: '员工账号与系统基础资料的管理入口。' },
  { key: 'hotel-manager', title: '酒店总经理工作区', label: '总经理工作台', permission: 'manager:workspace', number: '02', description: '从同一工作台进入前台与客房部的授权区域。' },
  { key: 'front-desk', title: '前台工作区', label: '前台工作台', permission: 'stay:manage', number: '03', description: '前台接待与日常协同的工作入口。' },
  { key: 'housekeeping-manager', title: '客房主管工作区', label: '客房管理', permission: 'housekeeping:manage', number: '04', description: '客房部任务安排与日常管理的工作入口。' },
  { key: 'housekeeping-staff', title: '客房服务员工作区', label: '客房工作台', permission: 'housekeeping:execute', number: '05', description: '客房服务与日常协同的工作入口。' },
] as const
export function availableWorkspaces() { return workspaces.filter(item => can(item.permission)) }
export function homePath(): string {
  // Multi-role users may visit all granted areas; this only selects the initial landing page.
  const preferred = auth.user?.roles.includes('HOTEL_MANAGER') ? 'hotel-manager'
    : auth.user?.roles.includes('ADMIN') ? 'admin'
    : auth.user?.roles.includes('HOUSEKEEPING_MANAGER') ? 'housekeeping-manager'
    : auth.user?.roles.includes('FRONT_DESK') ? 'front-desk' : 'housekeeping-staff'
  return availableWorkspaces().some(item => item.key === preferred) ? `/workspaces/${preferred}` : '/forbidden'
}
