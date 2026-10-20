<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login } from '../stores/auth'
import { homePath } from '../router/workspaces'

const router = useRouter(), route = useRoute()
const username = ref(''), password = ref(''), error = ref(''), busy = ref(false)
const expired = computed(() => route.query.reason === 'expired')
async function submit() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  busy.value = true
  try { await login(username.value.trim(), password.value); password.value = ''; await router.replace(homePath()) }
  catch (cause) { password.value = ''; error.value = cause instanceof Error ? cause.message : '登录失败，请重试' }
  finally { busy.value = false }
}
</script>

<template>
  <main class="login-shell">
    <section class="login-story">
      <a class="brand brand-light" href="/login"><span class="brand-mark">S</span><span>StayOS<span class="brand-sub">酒店协同工作台</span></span></a>
      <div class="story-copy"><p class="eyebrow">A BETTER STAY, TOGETHER</p><h1>每一次接待，<br>从有序开始。</h1><p>连接前台与客房部，<br>让日常工作清晰、顺畅。</p></div>
      <div class="story-footer"><span class="status-dot"></span> StayOS V1.0 · 员工工作入口</div>
    </section>
    <section class="login-panel">
      <div class="login-form-wrap">
        <p class="eyebrow muted">WELCOME BACK</p><h2>登录工作台</h2><p class="subtle">请使用你的员工账号继续</p>
        <el-alert v-if="expired" title="登录状态已失效，请重新登录" type="warning" :closable="false" show-icon class="form-alert" />
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" role="alert" />
        <el-form label-position="top" @submit.prevent="submit" class="login-form">
          <el-form-item label="账号" for="username"><el-input id="username" v-model="username" autocomplete="username" placeholder="请输入员工账号" maxlength="100" size="large" :disabled="busy" /></el-form-item>
          <el-form-item label="密码" for="password"><el-input id="password" v-model="password" type="password" show-password autocomplete="current-password" placeholder="请输入密码" maxlength="72" size="large" :disabled="busy" /></el-form-item>
          <el-button type="primary" native-type="submit" size="large" :loading="busy" class="login-button">{{ busy ? '正在登录…' : '登录' }}</el-button>
        </el-form>
        <p class="login-help">需要账号或忘记密码？请联系系统管理员。</p>
      </div>
      <p class="login-bottom">前台与客房，有序协同。</p>
    </section>
  </main>
</template>
