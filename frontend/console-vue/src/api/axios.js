import axios from 'axios'
import { getToken, getUsername } from '@/core/auth.js'
import { isNotEmpty } from '@/utils/plugins.js'
import router from '@/router'
import { ElMessage } from 'element-plus'

const parseJsonWithSafeIntegers = (data) => {
  if (typeof data !== 'string' || data.length === 0) {
    return data
  }
  try {
    return JSON.parse(
      data.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, (token) => {
        if (token.startsWith('"') || /[.eE]/.test(token)) {
          return token
        }
        const value = window.BigInt(token)
        return value > Number.MAX_SAFE_INTEGER || value < Number.MIN_SAFE_INTEGER
          ? `"${token}"`
          : token
      })
    )
  } catch {
    return data
  }
}

const http = axios.create({
  baseURL: '/api/short-link',
  timeout: 60000,
  transformResponse: [parseJsonWithSafeIntegers]
})

http.interceptors.request.use(
  (config) => {
    config.headers.Token = isNotEmpty(getToken()) ? getToken() : ''
    config.headers.Username = isNotEmpty(getUsername()) ? getUsername() : ''
    return config
  },
  (error) => Promise.reject(error)
)

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      router.push('/login')
      ElMessage.error('登录状态已失效，请重新登录')
    }
    return Promise.reject(error)
  }
)

export default http
