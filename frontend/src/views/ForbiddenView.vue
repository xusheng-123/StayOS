<script setup lang="ts">
import { homePath } from '../router/workspaces'
</script>
<template><el-result icon="warning" title="无权访问此工作区" sub-title="请从左侧导航选择你有权限的工作区域。"><template #extra><router-link :to="homePath()"><el-button type="primary">返回我的工作台</el-button></router-link></template></el-result></template>
