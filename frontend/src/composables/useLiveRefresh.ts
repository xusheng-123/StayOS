import { computed, onMounted, onUnmounted, ref } from 'vue'

// Poll only while this tab is visible and someone has interacted recently.
// An abandoned browser must not keep its Session alive indefinitely.
export function useLiveRefresh(refresh: () => Promise<unknown>, canRefresh: () => boolean = () => true) {
  const now = ref(Date.now()), lastSuccess = ref(0)
  let lastInteraction = Date.now(), lastAttempt = Date.now(), running = false, timer: number | undefined
  const stale = computed(() => lastSuccess.value > 0 && now.value - lastSuccess.value > 60000)
  function fresh() { lastSuccess.value = Date.now(); now.value = Date.now() }
  function interact() { lastInteraction = Date.now() }
  async function tick(force = false) {
    now.value = Date.now()
    if (running || !canRefresh() || document.visibilityState !== 'visible' || now.value - lastInteraction >= 60000 || (!force && now.value - lastAttempt < 30000)) return
    running = true; lastAttempt = now.value
    try { await refresh() } finally { running = false }
  }
  function foreground() { if (document.visibilityState === 'visible') { interact(); void tick(true) } }
  onMounted(() => {
    for (const event of ['pointerdown', 'keydown', 'wheel', 'touchstart']) window.addEventListener(event, interact, { passive: true })
    window.addEventListener('focus', foreground); document.addEventListener('visibilitychange', foreground)
    timer = window.setInterval(() => { void tick() }, 5000)
  })
  onUnmounted(() => {
    if (timer !== undefined) window.clearInterval(timer)
    for (const event of ['pointerdown', 'keydown', 'wheel', 'touchstart']) window.removeEventListener(event, interact)
    window.removeEventListener('focus', foreground); document.removeEventListener('visibilitychange', foreground)
  })
  return { stale, fresh }
}
