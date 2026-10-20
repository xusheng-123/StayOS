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
      { path: 'stays/new', component: () => import('../views/CheckInView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays/history', component: () => import('../views/StayListView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays', component: () => import('../views/StayListView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'stays/:id', component: () => import('../views/StayDetailView.vue'), meta: { permission: 'stay:manage' } },
      { path: 'housekeeping/assignment', component: () => import('../views/HousekeepingAssignmentView.vue'), meta: { permission: 'housekeeping:manage' } },
      { path: 'housekeeping/tasks', component: () => import('../views/HousekeepingTasksView.vue'), meta: { permission: 'housekeeping:read' } },
      { path: 'manage/rooms', component: () => import('../views/RoomManageView.vue'), meta: { permission: 'room:manage' } },
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
  return true
})
window.addEventListener('stayos:unauthorized', () => {
  if (router.currentRoute.value.path !== '/login') void router.replace({ path: '/login', query: { reason: 'expired' } })
})
export default router
