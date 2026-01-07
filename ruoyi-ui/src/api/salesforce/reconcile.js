import request from '@/utils/request'

// 1. 任务管理 (Job)
export function listJob(query) {
  return request({
    url: '/salesforce/reconcile/list',
    method: 'get',
    params: query
  })
}

export function getJob(id) {
  return request({
    url: '/salesforce/reconcile/' + id,
    method: 'get'
  })
}

export function addJob(data) {
  return request({
    url: '/salesforce/reconcile',
    method: 'post',
    data: data
  })
}

export function updateJob(data) {
  return request({
    url: '/salesforce/reconcile',
    method: 'put',
    data: data
  })
}

export function delJob(id) {
  return request({
    url: '/salesforce/reconcile/' + id,
    method: 'delete'
  })
}

// 2. 配置管理 (Config)
export function listConfigs(jobId) {
  return request({
    url: '/salesforce/reconcile/config/list/' + jobId,
    method: 'get'
  })
}

export function batchSaveConfigs(jobId, data) {
  return request({
    url: '/salesforce/reconcile/config/batchSave/' + jobId,
    method: 'post',
    data: data
  })
}

// 3. 核心操作
export function runJob(jobId) {
  return request({
    url: '/salesforce/reconcile/run/' + jobId,
    method: 'post'
  })
}

// 4. 日志与结果
export function listLogs(query) {
  return request({
    url: '/salesforce/reconcile/log/list',
    method: 'get',
    params: query
  })
}

// 修改后：指向新的 /objects 接口
export function getSObjectList(orgId) {
    return request({
      url: '/salesforce/describe/objects',
      method: 'get',
      params: { orgId }
    })
  }

  export function getSObjectFields(orgId, objectName) {
    return request({
      url: '/salesforce/describe/fields', // 需确认后端是否有此接口，若无请补充
      method: 'get',
      params: { orgId, objectName }
    })
  }