<template><main class="unavailable"><el-result icon="error" title="暂时无法连接服务器" sub-title="请检查后端服务是否启动，然后重新尝试。"><template #extra><el-button type="primary" @click="reload">重新连接</el-button></template></el-result></main></template>
<script setup lang="ts">function reload() { window.location.assign('/') }</script>
