<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auth, logout, refreshUser, can } from '../stores/auth'
import { availableWorkspaces, homePath, roleNames } from '../router/workspaces'

const router = useRouter(), route = useRoute(), busy = ref(false)
const navigation = computed(availableWorkspaces)
async function check() {
  // Check on focus; avoid polling, which would keep an otherwise idle Session alive.
  if (document.visibilityState !== 'visible' || busy.value) return
  try { await refreshUser(); if (!auth.user) await router.replace({ path: '/login', query: { reason: 'expired' } }) }
  catch { ElMessage.error('暂时无法验证登录状态，请稍后重试') }
}
onMounted(() => { window.addEventListener('focus', check) })
onUnmounted(() => { window.removeEventListener('focus', check) })
async function leave() {
  busy.value = true
  try { await logout(); await router.replace('/login') }
  catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '退出失败，请重试') }
  finally { busy.value = false }
}
</script>

<template>
  <div class="workspace-shell">
    <aside class="sidebar">
      <router-link :to="homePath()" class="brand brand-light"><span class="brand-mark">S</span><span>StayOS<span class="brand-sub">酒店协同工作台</span></span></router-link>
      <p class="nav-caption">工作区域</p>
      <nav aria-label="工作区域"><router-link v-for="item in navigation" :key="item.key" :to="`/workspaces/${item.key}`" :class="['nav-link', { active: route.path === `/workspaces/${item.key}` }]"><span class="nav-number">{{ item.number }}</span>{{ item.label }}<span class="nav-arrow">›</span></router-link></nav>
      <p class="nav-caption room-nav-caption">房间功能</p>
      <nav aria-label="房间功能">
        <router-link v-if="can('room:read')" to="/rooms" :class="['nav-link', { active: route.path === '/rooms' }]"><span class="nav-number">06</span>房态图<span class="nav-arrow">›</span></router-link>
        <router-link v-if="can('room:manage')" to="/manage/rooms" :class="['nav-link', { active: route.path === '/manage/rooms' }]"><span class="nav-number">07</span>房间管理<span class="nav-arrow">›</span></router-link>
        <router-link v-if="can('room:manage')" to="/manage/room-types" :class="['nav-link', { active: route.path === '/manage/room-types' }]"><span class="nav-number">08</span>房型管理<span class="nav-arrow">›</span></router-link>
      </nav>
      <template v-if="can('stay:manage')"><p class="nav-caption room-nav-caption">入住功能</p><nav aria-label="入住功能">
        <router-link to="/stays/new" :class="['nav-link', { active: route.path === '/stays/new' }]"><span class="nav-number">09</span>办理入住<span class="nav-arrow">›</span></router-link>
        <router-link to="/stays" :class="['nav-link', { active: route.path === '/stays' }]"><span class="nav-number">10</span>当前入住<span class="nav-arrow">›</span></router-link>
        <router-link to="/stays/history" :class="['nav-link', { active: route.path === '/stays/history' }]"><span class="nav-number">11</span>历史入住<span class="nav-arrow">›</span></router-link>
      </nav></template>
      <div class="sidebar-bottom"><span class="status-dot"></span> StayOS V1.0</div>
    </aside>
    <div class="workspace-body">
      <header class="topbar"><span class="topbar-label">员工工作台</span><div class="user-actions"><span class="user-avatar">{{ auth.user?.displayName.slice(0, 1) }}</span><span class="user-name">{{ auth.user?.displayName }}</span><el-button text :loading="busy" @click="leave">退出登录</el-button></div></header>
      <main class="workspace-main"><div class="identity-line"><el-tag v-for="role in auth.user?.roles" :key="role" effect="plain" round>{{ roleNames[role] }}</el-tag><span class="subtle">已登录 · {{ auth.user?.username }}</span></div><router-view /></main>
      <footer class="workspace-footer">StayOS · 让酒店日常工作有序发生</footer>
    </div>
  </div>
</template>
