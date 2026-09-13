import './assets/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import {
  ArrowDown,
  CaretBottom,
  ChatDotRound,
  CircleCheck,
  CircleCheckFilled,
  CircleClose,
  Connection,
  CopyDocument,
  DataAnalysis,
  Delete,
  Document,
  DocumentCopy,
  HelpFilled,
  Histogram,
  Iphone,
  Link,
  List,
  Loading,
  Lock,
  MagicStick,
  Message,
  MoreFilled,
  Promotion,
  Refresh,
  RefreshRight,
  Select,
  Share,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  User,
  Warning,
  WarningFilled
} from '@element-plus/icons-vue'
import './style.scss'
import API from '@/api/index'
import store from './store'

const app = createApp(App)

app.config.globalProperties.$API = API
app.use(router)
app.use(store)
const globalIcons = {
  ArrowDown,
  CaretBottom,
  ChatDotRound,
  CircleCheck,
  CircleCheckFilled,
  CircleClose,
  Connection,
  CopyDocument,
  DataAnalysis,
  Delete,
  Document,
  DocumentCopy,
  HelpFilled,
  Histogram,
  Iphone,
  Link,
  List,
  Loading,
  Lock,
  MagicStick,
  Message,
  MoreFilled,
  Promotion,
  Refresh,
  RefreshRight,
  Select,
  Share,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  User,
  Warning,
  WarningFilled
}

for (const [key, component] of Object.entries(globalIcons)) {
  app.component(key, component)
}

app.mount('#app')
