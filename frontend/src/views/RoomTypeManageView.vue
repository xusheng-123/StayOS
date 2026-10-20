<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElTable, ElTableColumn, ElSelect, ElOption, ElInputNumber, ElDialog, ElPagination, ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { RoomType, PageResult } from '../types/room'

const items = ref<RoomType[]>([]), page = ref(1), total = ref(0), search = ref(''), enabled = ref<boolean | undefined>()
const loading = ref(false), saving = ref(false), error = ref(''), dialog = ref(false), editing = ref<RoomType | null>(null)
const blank = () => ({ typeCode: '', typeName: '', description: '', bedDescription: '', suggestedOccupancy: 2 as number | undefined })
const form = ref(blank())
let generation = 0
async function load() {
  const current = ++generation; loading.value = true; error.value = ''
  const query = new URLSearchParams({ page: String(page.value), pageSize: '20', search: search.value })
  if (enabled.value !== undefined) query.set('enabled', String(enabled.value))
  try { const result = await api<PageResult<RoomType>>(`/api/room-types?${query}`); if (current === generation) { items.value = result.items; total.value = result.total } }
  catch (cause) { if (current === generation) { error.value = cause instanceof Error ? cause.message : '房型加载失败'; items.value = []; total.value = 0 } }
  finally { if (current === generation) loading.value = false }
}
function query() { page.value = 1; void load() }
function open(type?: RoomType) {
  editing.value = type || null; form.value = type ? { typeCode: type.typeCode, typeName: type.typeName, description: type.description, bedDescription: type.bedDescription, suggestedOccupancy: type.suggestedOccupancy } : blank(); dialog.value = true
}
async function save() {
  if (!form.value.typeCode.trim() || !form.value.typeName.trim() || !form.value.suggestedOccupancy) { ElMessage.error('请填写房型编码、名称和建议入住人数'); return }
  saving.value = true
  try {
    const type = editing.value
    await api(type ? `/api/room-types/${type.id}` : '/api/room-types', { method: type ? 'PUT' : 'POST', body: JSON.stringify({ ...form.value, ...(type ? { version: type.version } : {}) }) })
    dialog.value = false; ElMessage.success('房型资料已保存'); await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '保存失败') }
  finally { saving.value = false }
}
async function toggle(type: RoomType) {
  try { await ElMessageBox.confirm(`确定${type.enabled ? '停用' : '启用'}房型“${type.typeName}”？停用不会删除或自动停用现有关联房间。`, '确认操作', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }) }
  catch { return }
  saving.value = true
  try {
    await api(`/api/room-types/${type.id}/set-enabled`, { method: 'POST', body: JSON.stringify({ enabled: !type.enabled, version: type.version }) })
    ElMessage.success('房型启用状态已更新'); await load()
  } catch (cause) { ElMessage.error(cause instanceof Error ? cause.message : '操作失败') }
  finally { saving.value = false }
}
function changePage(value: number) { page.value = value; void load() }
onMounted(load)
</script>

<template>
  <section class="rooms-page">
    <div class="page-heading"><div><p class="eyebrow muted">ROOM TYPES</p><h1>房型管理</h1><p class="subtle">定义酒店自己的房型，保留与房间的稳定关联。</p></div><el-button type="primary" @click="open()">新增房型</el-button></div>
    <form class="room-filters type-filters" @submit.prevent="query"><div class="filter-field"><label for="type-search">名称或编码</label><el-input id="type-search" v-model="search" maxlength="100" placeholder="搜索房型" clearable /></div><div class="filter-field"><label id="type-enabled-label">启用状态</label><el-select v-model="enabled" aria-label="启用状态" placeholder="全部状态" clearable @change="query"><el-option label="启用" :value="true" /><el-option label="停用" :value="false" /></el-select></div><div class="filter-actions"><el-button type="primary" native-type="submit" :loading="loading">查询</el-button><el-button @click="search = ''; enabled = undefined; query()">重置</el-button></div></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-button v-if="error" class="retry-button" @click="load">重试</el-button>
    <div class="catalog-panel" v-loading="loading">
      <div class="table-caption"><h2>房型资料</h2><span class="subtle">共 {{ total }} 种</span></div>
      <el-table :data="items" empty-text="暂无房型，点击新增房型开始维护">
        <el-table-column prop="typeName" label="房型名称" min-width="150" /><el-table-column prop="typeCode" label="编码" min-width="120" /><el-table-column prop="bedDescription" label="床位说明" min-width="150" show-overflow-tooltip /><el-table-column prop="suggestedOccupancy" label="建议人数" min-width="95" /><el-table-column prop="roomCount" label="关联房间" min-width="95" /><el-table-column label="状态" min-width="80"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作" min-width="155" fixed="right"><template #default="{ row }"><el-button text size="small" :disabled="saving" @click="open(row as RoomType)">编辑</el-button><el-button text size="small" :disabled="saving" @click="toggle(row as RoomType)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="total > 20" class="room-pagination" layout="prev, pager, next" :current-page="page" :page-size="20" :total="total" @current-change="changePage" />
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑房型' : '新增房型'" width="min(580px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" @submit.prevent="save">
        <div class="form-columns"><el-form-item label="房型编码" for="type-code" required><el-input id="type-code" v-model="form.typeCode" maxlength="50" placeholder="例如 DELUXE_KING" :disabled="saving" /></el-form-item><el-form-item label="房型名称" for="type-name" required><el-input id="type-name" v-model="form.typeName" maxlength="100" :disabled="saving" /></el-form-item></div>
        <el-form-item label="床位说明" for="type-bed"><el-input id="type-bed" v-model="form.bedDescription" maxlength="200" placeholder="例如 1 张 1.8 米大床" :disabled="saving" /></el-form-item>
        <el-form-item label="建议入住人数" for="type-occupancy" required><el-input-number id="type-occupancy" v-model="form.suggestedOccupancy" :min="1" :max="20" :precision="0" :disabled="saving" /></el-form-item>
        <el-form-item label="房型描述" for="type-description"><el-input id="type-description" v-model="form.description" type="textarea" :rows="3" maxlength="2000" show-word-limit :disabled="saving" /></el-form-item>
        <div class="dialog-actions"><el-button :disabled="saving" @click="dialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存房型</el-button></div>
      </el-form>
    </el-dialog>
  </section>
</template>
