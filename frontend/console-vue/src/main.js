import { createApp } from 'vue'
import App from './relay/App.vue'
import router from './relay/router.js'
import primitives from './relay/components/primitives.js'
import './relay/components/primitives.css'
import './relay/styles.css'

const app = createApp(App)
for (const [name, component] of Object.entries(primitives)) app.component(name, component)
app.use(router)
app.mount('#app')
