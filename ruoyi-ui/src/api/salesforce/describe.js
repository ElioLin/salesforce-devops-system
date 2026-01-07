import request from '@/utils/request'

// 对应 SfDescribeApiController.getSObjects (无 objectName 参数)
// URL: /salesforce/describe/objects?orgId=xxx
export function listSObjects(orgId) {
  return request({
    url: '/salesforce/describe/objects',
    method: 'get',
    params: { orgId }
  })
}

// 对应 SfDescribeApiController.getSObjects (有 objectName 参数)
// URL: /salesforce/describe/fields?orgId=xxx&objectName=xxx
export function getSObjectFields(orgId, objectName) {
  return request({
    url: '/salesforce/describe/fields',
    method: 'get',
    params: { orgId, objectName }
  })
}