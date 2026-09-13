import http from '../axios'
export default {
  // 注册
  addUser(data) {
    return http({
      url: '/admin/v1/user',
      method: 'post',
      data
    })
  },
  // 编辑信息
  editUser(data) {
    return http({
      url: '/v1/user',
      method: 'put',
      data
    })
  },
  // 登录
  login(data) {
    return http({
      url: '/admin/v1/user/login',
      method: 'post',
      data
    })
  },
  // 退出登录
  logout(data) {
    return http({
      url: '/admin/v1/user/logout',
      method: 'delete',
      params: data
    })
  },
  // 检查用户名是否可用
  hasUsername(data) {
    return http({
      url: '/v1/user/has-username',
      method: 'get',
      params: data
    })
  },
  // 根据用户名查找用户信息
  queryUserInfo(data) {
    return http({
      url: '/v1/user/' + encodeURIComponent(data),
      method: 'get'
    })
  }
}
