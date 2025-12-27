import request from '@/utils/request'

// 查询Salesforce环境管理列表
export function listOrg(query) {
  return request({
    url: '/salesforce/org/list',
    method: 'get',
    params: query
  })
}

// 查询Salesforce环境管理详细
export function getOrg(id) {
  return request({
    url: '/salesforce/org/' + id,
    method: 'get'
  })
}

// 新增Salesforce环境管理
export function addOrg(data) {
  return request({
    url: '/salesforce/org',
    method: 'post',
    data: data
  })
}

// 修改Salesforce环境管理
export function updateOrg(data) {
  return request({
    url: '/salesforce/org',
    method: 'put',
    data: data
  })
}

// 删除Salesforce环境管理
export function delOrg(id) {
  return request({
    url: '/salesforce/org/' + id,
    method: 'delete'
  })
}
