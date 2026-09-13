import { createStore } from 'vuex'

// 创建一个新的 store 实例
const store = createStore({
  state() {
    return {
      // null 表示由 Command 使用受控的 shortlink.default-domain，避免前端硬编码域名。
      domain: null
    }
  }
})

export default store
