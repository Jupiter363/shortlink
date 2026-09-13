import http from '../axios'
export default {
  // 查询分组集合
  queryGroup(data) {
    return http({
      url: '/admin/v1/group',
      method: 'get',
      params: data
    })
  },
  // 新增短链分组
  addGroup(data) {
    return http({
      url: '/admin/v1/group',
      method: 'post',
      data
    })
  },
  // 修改短链分组
  editGroup(data) {
    return http({
      url: '/admin/v1/group',
      method: 'put',
      data
    })
  },
  // 删除短链分组
  deleteGroup(data) {
    return http({
      url: '/admin/v1/group',
      method: 'delete',
      params: data
    })
  },
  sortGroup(data) {
    return http({
      url: '/admin/v1/sort',
      method: 'post',
      data
    })
  },
  // 查询分组的图表数据
  queryGroupStats(data) {
    return http({
      method: 'get',
      params: data,
      url: '/admin/v1/stats/group'
    })
  },
  // 查询分组的访问记录
  queryGroupTable(data) {
    return http({
      method: 'get',
      params: data,
      url: '/admin/v1/stats/access-record/group'
    })
  }
}
