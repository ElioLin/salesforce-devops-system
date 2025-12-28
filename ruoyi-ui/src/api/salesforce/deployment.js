import request from '@/utils/request'

// 查询部署包列表
export function listDeployment(query) {
  return request({
    url: '/salesforce/deployment/list',
    method: 'get',
    params: query
  })
}

// 查询部署包详细
export function getDeployment(id) {
  return request({
    url: '/salesforce/deployment/' + id,
    method: 'get'
  })
}

// 新增部署包
export function addDeployment(data) {
  return request({
    url: '/salesforce/deployment',
    method: 'post',
    data: data
  })
}

// 修改部署包
export function updateDeployment(data) {
  return request({
    url: '/salesforce/deployment',
    method: 'put',
    data: data
  })
}

// 删除部署包
export function delDeployment(id) {
  return request({
    url: '/salesforce/deployment/' + id,
    method: 'delete'
  })
}

// 查询包内的明细列表
export function listDeploymentItems(deploymentId) {
  return request({
    url: '/salesforce/deployment/item/list/' + deploymentId,
    method: 'get'
  })
}

// 添加元数据到包中
export function addDeploymentItems(deploymentId, items) {
  return request({
    url: '/salesforce/deployment/item/add/' + deploymentId,
    method: 'post',
    data: items
  })
}

// 移除明细
export function removeDeploymentItems(ids) {
  return request({
    url: '/salesforce/deployment/item/' + ids,
    method: 'delete'
  })
}

// 执行部署/验证 (触发后端异步任务)
export function deployPackage(id, checkOnly) {
  return request({
    url: '/salesforce/deployment/deploy/' + id + '/' + checkOnly,
    method: 'post',
    timeout: 10000 // 设置较短的超时，因为后端现在是立即返回
  })
}

// 检查部署状态 (查询 Salesforce 实时进度)
export function checkDeployStatus(targetOrgId, processId) {
  return request({
    url: '/salesforce/deployment/deploy/status/' + targetOrgId + '/' + processId,
    method: 'get'
  })
}

// 【新增】快速部署
export function quickDeploy(id) {
    return request({
      url: '/salesforce/deployment/quickDeploy/' + id,
      method: 'post'
    })
 }

 // 触发状态计算
export function checkDiffStatus(deploymentId) {
    return request({
      url: '/salesforce/deployment/item/checkStatus/' + deploymentId,
      method: 'post'
    })
}

// 【新增】获取所有支持的元数据类型
export function getMetadataTypes(orgId) {
  return request({
    url: '/system/sf/meta/types',
    method: 'get',
    params: { orgId }
  })
}

// 【新增】预览部署包 (生成ZIP但不部署)
export function previewDeploymentPackage(id) {
  return request({
    url: '/salesforce/deployment/preview/' + id,
    method: 'get',
    timeout: 60000 // 预览需要去Salesforce拉包，设置长超时
  })
}