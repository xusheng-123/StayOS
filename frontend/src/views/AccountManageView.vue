<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElDialog, ElCheckbox, ElCheckboxGroup, ElPagination, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import { roleNames } from '../router/workspaces'
import { auth, refreshUser } from '../stores/auth'
import type { User, Role } from '../types/auth'
import type { PageResult } from '../types/room'
const items = ref<User[]>([]), roles = ref<Role[]>([]), page = ref(1), total = ref(0), loading = ref(false), busy = ref(false), search = ref('')
const error = ref(''), success = ref(''), visible = ref(false), mode = ref<'create' | 'roles' | 'password'>('create'), selected = ref<User>()
const form = ref({ username: '', displayName: '', password: '', confirmation: '', roles: [] as Role[] })
const dialogError = ref('')
let generation = 0
function message(e: unknown) { return e instanceof Error ? e.message : '操作失败，请刷新账号列表核对结果' }
async function load() {
  const g = ++generation; loading.value = true; error.value = ''
  try { const r = await api<PageResult<User>>(`/api/users?page=${page.value}&pageSize=20&search=${encodeURIComponent(search.value)}`); if (g === generation) { items.value = r.items; total.value = r.total } }
  catch (e) { if (g === generation) { error.value = message(e); items.value = [] } }
  finally { if (g === generation) loading.value = false }
}
function open(action: typeof mode.value, user?: User) {
  mode.value = action; selected.value = user; dialogError.value = ''; success.value = ''
  form.value = { username: '', displayName: '', password: '', confirmation: '', roles: [...(user?.roles || [])] }; visible.value = true
}
function closed() { form.value.password = ''; form.value.confirmation = '' }
async function save() {
  if (busy.value) return
  const f = form.value; dialogError.value = ''
  if (mode.value !== 'roles' && (f.password.length < 12 || new TextEncoder().encode(f.password).length > 72 || f.password !== f.confirmation)) { dialogError.value = '密码至少 12 个字符、不超过 72 个 UTF-8 字节，两次输入须一致'; return }
  if (mode.value !== 'password' && !f.roles.length) { dialogError.value = '请选择至少一个角色'; return }
  if (mode.value === 'create' && (!f.username.trim() || !f.displayName.trim())) { dialogError.value = '请填写账号和员工姓名'; return }
  busy.value = true
  try {
    if (mode.value === 'create') await api('/api/users', { method: 'POST', body: JSON.stringify({ username: f.username, displayName: f.displayName, password: f.password, roles: f.roles }) })
    else if (mode.value === 'roles') await api(`/api/users/${selected.value!.id}/roles`, { method: 'PUT', body: JSON.stringify({ roles: f.roles }) })
    else await api(`/api/users/${selected.value!.id}/reset-password`, { method: 'POST', body: JSON.stringify({ password: f.password }) })
    visible.value = false; closed(); success.value = '账号设置已保存。角色、密码或启用状态变化后，受影响员工需重新登录。'
    if (selected.value?.id === auth.user?.id) await refreshUser()
    await load()
  } catch (e) { dialogError.value = message(e) + '；结果不确定时请刷新列表核对，不要重复创建账号' }
  finally { busy.value = false }
}
async function toggle(user: User) {
  if (busy.value) return
  try { await ElMessageBox.confirm(`确认${user.enabled ? '禁用' : '启用'} ${user.displayName}（${user.username}）？禁用不会删除其业务记录；已有任务应由主管受控接管。`, '确认账号状态', { confirmButtonText: '确认', cancelButtonText: '取消' }) } catch { return }
  busy.value = true; error.value = ''
  try { await api(`/api/users/${user.id}/set-enabled`, { method: 'POST', body: JSON.stringify({ enabled: !user.enabled }) }); success.value = '账号状态已更新'; await load() }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
onMounted(async () => { try { roles.value = await api<Role[]>('/api/roles'); await load() } catch (e) { error.value = message(e) } })
</script>
<template>
  <section class="rooms-page accounts-page">
    <div class="page-heading"><div><p class="eyebrow muted">STAFF ACCOUNTS</p><h1>账号管理</h1><p class="subtle">维护员工账号与固定岗位角色，离职时禁用账号并保留历史。</p></div><el-button type="primary" :disabled="busy" @click="open('create')">新增员工</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="success" :title="success" type="success" :closable="false" />
    <form class="account-actions" @submit.prevent="page = 1; load()"><el-input v-model="search" aria-label="搜索员工账号或姓名" maxlength="100" placeholder="搜索账号或员工姓名" style="max-width:320px" /><el-button native-type="submit">搜索员工</el-button></form>
    <div class="table-caption"><span>共 {{ total }} 个账号</span><el-button :loading="loading" @click="load">刷新账号</el-button></div>
    <div v-loading="loading" class="account-grid"><article v-for="user in items" :key="user.id" class="catalog-panel account-card" :aria-label="`员工账号 ${user.username}`">
      <div class="table-caption"><h2>{{ user.displayName }}</h2><el-tag :type="user.enabled ? 'success' : 'info'">{{ user.enabled ? '启用' : '禁用' }}</el-tag></div><p>{{ user.username }} · #{{ user.id }}</p>
      <div class="account-roles"><el-tag v-for="role in user.roles" :key="role" effect="plain">{{ roleNames[role] }}</el-tag></div>
      <div class="account-actions"><el-button :disabled="busy" @click="open('roles', user)">调整角色</el-button><el-button :disabled="busy" @click="open('password', user)">重置密码</el-button><el-button :disabled="busy" @click="toggle(user)">{{ user.enabled ? '禁用账号' : '启用账号' }}</el-button></div>
    </article></div>
    <el-pagination v-if="total > 20" layout="prev, pager, next" :total="total" :page-size="20" v-model:current-page="page" @current-change="load" />
    <el-dialog v-model="visible" :title="mode === 'create' ? '新增员工' : mode === 'roles' ? '调整员工角色' : '重置员工密码'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="closed">
      <p v-if="selected">{{ selected.displayName }} · {{ selected.username }}</p><el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="mode === 'create'"><el-form-item label="员工账号" for="account-username"><el-input id="account-username" v-model="form.username" maxlength="100" autocomplete="off" :disabled="busy" /></el-form-item><el-form-item label="员工姓名" for="account-name"><el-input id="account-name" v-model="form.displayName" maxlength="100" :disabled="busy" /></el-form-item></template>
        <template v-if="mode !== 'roles'"><el-form-item label="新密码" for="account-password"><el-input id="account-password" v-model="form.password" type="password" autocomplete="new-password" maxlength="72" :disabled="busy" /></el-form-item><el-form-item label="再次输入密码" for="account-confirmation"><el-input id="account-confirmation" v-model="form.confirmation" type="password" autocomplete="new-password" maxlength="72" :disabled="busy" /></el-form-item></template>
        <el-form-item v-if="mode !== 'password'" label="员工角色"><el-checkbox-group v-model="form.roles" :disabled="busy"><el-checkbox v-for="role in roles" :key="role" :value="role">{{ roleNames[role] }}</el-checkbox></el-checkbox-group></el-form-item>
        <p class="subtle">系统管理员不自动获得酒店业务权限；多角色权限合并。系统会保护最后一名启用的管理员。</p>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存账号设置</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.account-grid { display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr));gap:16px }.account-card { min-width:0;overflow-wrap:anywhere }.account-roles,.account-actions { display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 }.account-actions .el-button { margin-left:0;min-height:44px }.account-card h2 { margin:0 }.el-checkbox-group { display:flex;flex-wrap:wrap;gap:8px }.el-checkbox { margin-right:10px;white-space:normal }
</style>
