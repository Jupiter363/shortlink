import http from '../axios'

export default {
  health() {
    return http({
      url: '/admin/v1/agent/health',
      method: 'get',
      timeout: 15000
    })
  },
  chat(data) {
    return http({
      url: '/admin/v1/agent/chat',
      method: 'post',
      data,
      timeout: 120000
    })
  }
}
