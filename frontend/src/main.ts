import { createApp } from 'vue'
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElLoading, ElResult, ElTag } from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import './rooms.css'
import './stays.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
for (const component of [ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElResult, ElTag]) app.use(component)
app.directive('loading', ElLoading.directive)
app.use(router).mount('#app')
