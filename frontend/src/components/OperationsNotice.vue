<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { operations, refreshOperations, clearOperations } from '../stores/operations'
import { useLiveRefresh } from '../composables/useLiveRefresh'
const attention = computed(() => (operations.data?.criticalIssues || 0) + (operations.data?.strandedTasks || 0))
useLiveRefresh(refreshOperations, () => !operations.loading)
onMounted(refreshOperations); onUnmounted(clearOperations)
</script>
<template><router-link to="/operations" class="operations-notice" :class="{ urgent: attention > 0 }">运营待办<template v-if="attention"> · {{ attention }} 项需关注</template><template v-if="operations.error"> · 暂不可用</template></router-link></template>
<style scoped>.operations-notice { color:inherit;text-decoration:underline;text-underline-offset:4px;font-size:13px;overflow-wrap:anywhere }.urgent { color:#a33e2d;font-weight:600 }</style>
