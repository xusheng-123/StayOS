import { createRouter, createWebHistory } from 'vue-router'
import { auth, refreshUser, can } from '../stores/auth'
import { homePath, workspaces } from './workspaces'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', component: () => import('../layouts/WorkspaceLayout.vue'), children: [
      { path: '', component: () => import('../views/WorkspaceView.vue') },
      ...workspaces.map(item => ({ path: `workspaces/${item.key}`, component: () => import('../views/WorkspaceView.vue'), meta: { permission: item.permission, workspace: item.key } })),
      { path: 'room-issues', component: () => import('../views/RoomIssuesView.vue'), meta: { permission: 'issue:report' } },
      { path: 'rooms', component: () => import('../views/RoomBoardView.vue'), meta: { permission: 'room:read' } },
      { path: 'audit-logs', component: () => import('../views/AuditLogView.vue'), meta: { anyPermissions: ['audit:management', 'audit:business', 'audit:housekeeping'] } },
      { path: 'stays/new', component: () => import('../views/CheckInView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays/history', component: () => import('../views/StayListView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays', component: () => import('../views/StayListView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays/:id', component: () => import('../views/StayDetailView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'reservations', component: () => import('../views/ReservationsView.vue'), meta: { permission: 'reservation:manage' } },
      { path: 'reservations/:id', component: () => import('../views/ReservationDetailView.vue'), meta: { permission: 'reservation:manage' } },
      { path: 'availability', component: () => import('../views/AvailabilityView.vue'), meta: { anyPermissions: ['reservation:manage', 'inventory:manage'] } },
      { path: 'housekeeping/assignment', component: () => import('../views/HousekeepingAssignmentView.vue'), meta: { permission: 'housekeeping:manage' } },
      { path: 'housekeeping/tasks', component: () => import('../views/HousekeepingTasksView.vue'), meta: { permission: 'housekeeping:read' } },
      { path: 'manage/rooms', component: () => import('../views/RoomManageView.vue'), meta: { permission: 'room:manage' } },
      { path: 'manage/accounts', component: () => import('../views/AccountManageView.vue'), meta: { permission: 'system:manage' } },
      { path: 'operations', component: () => import('../views/OperationsView.vue'), meta: { anyPermissions: ['stay:manage', 'housekeeping:read', 'issue:report'] } },
      { path: 'handovers', component: () => import('../views/HandoverListView.vue'), meta: { anyPermissions: ['handover:front-desk', 'handover:housekeeping'] } },
      { path: 'handovers/:id', component: () => import('../views/HandoverDetailView.vue'), meta: { anyPermissions: ['handover:front-desk', 'handover:housekeeping'] } },
      { path: 'billing', component: () => import('../views/BillingListView.vue'), meta: { permission: 'billing:read' } },
      { path: 'billing/config', component: () => import('../views/BillingConfigView.vue'), meta: { permission: 'billing:read' } },
      { path: 'billing/folios/:id', component: () => import('../views/FolioDetailView.vue'), meta: { permission: 'billing:read' } },
      { path: 'cashier', component: () => import('../views/CashierShiftView.vue'), meta: { permission: 'cashier:operate' } },
      { path: 'reports/daily', component: () => import('../views/DailyReportsView.vue'), meta: { permission: 'report:finance' } },
      { path: 'manage/room-types', component: () => import('../views/RoomTypeManageView.vue'), meta: { permission: 'room:manage' } },
      { path: 'forbidden', component: () => import('../views/ForbiddenView.vue') },
    ] },
    { path: '/unavailable', component: () => import('../views/UnavailableView.vue'), meta: { public: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async to => {
  if (to.path === '/unavailable') return true
  const hadUser = auth.user !== null
  try { await refreshUser() }
  catch { return '/unavailable' }
  if (to.path === '/login') return auth.user ? homePath() : true
  if (!auth.user) return { path: '/login', query: hadUser ? { reason: 'expired' } : {} }
  if (to.path === '/') return homePath()
  if (typeof to.meta.permission === 'string' && !can(to.meta.permission)) return '/forbidden'
  if (Array.isArray(to.meta.anyPermissions) && !to.meta.anyPermissions.some(p => typeof p === 'string' && can(p))) return '/forbidden'
  return true
})
window.addEventListener('stayos:unauthorized', () => {
  if (router.currentRoute.value.path !== '/login') void router.replace({ path: '/login', query: { reason: 'expired' } })
})
export default router
