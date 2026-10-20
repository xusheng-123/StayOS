<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import { workspaces, availableWorkspaces } from '../router/workspaces'

const route = useRoute(), error = ref(''), loading = ref(false), verified = ref(false)
const current = computed(() => workspaces.find(item => item.key === route.meta.workspace))
const links = computed(() => availableWorkspaces().filter(item => item.key !== current.value?.key))
let generation = 0
async function load() {
  const requestGeneration = ++generation
  error.value = ''; loading.value = true; verified.value = false
  try {
    await api(`/api/workspaces/${current.value?.key}`)
    if (generation === requestGeneration) verified.value = true
  } catch (cause) { if (generation === requestGeneration) error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { if (generation === requestGeneration) loading.value = false }
}
watch(() => route.meta.workspace, load, { immediate: true })
</script>

<template>
  <section v-loading="loading">
    <div class="page-heading"><div><p class="eyebrow muted">YOUR WORKSPACE</p><h1>{{ current?.title }}</h1><p class="subtle">{{ current?.description }}</p></div><el-tag v-if="verified" type="success" effect="light" round disable-transitions>访问权限已验证</el-tag></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon /><el-button v-if="error" @click="load" class="retry-button">重试</el-button>
    <div v-if="verified" class="welcome-card"><div class="welcome-symbol">S</div><div><h2>工作台已就绪</h2><p>你已成功进入当前角色的工作区域。</p><p class="subtle">通过“房态图”查看酒店房间；入住、退房与清洁任务将在后续阶段开放。</p><router-link to="/rooms" class="card-link">查看房态图 →</router-link></div></div>
    <section v-if="verified && links.length" class="quick-section"><h2>其他可用工作区域</h2><div class="workspace-grid"><router-link v-for="item in links" :key="item.key" :to="`/workspaces/${item.key}`" class="workspace-card"><span class="card-number">{{ item.number }}</span><h3>{{ item.title }}</h3><p>{{ item.description }}</p><span class="card-link">进入工作区 →</span></router-link></div></section>
  </section>
</template>
