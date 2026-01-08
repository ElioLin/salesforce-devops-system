import request from '@/utils/request'

export function listConfigs(jobId) {
  return request({
    url: '/salesforce/objConfig/list/' + jobId,
    method: 'get'
  })
}

export function batchSaveConfigs(jobId, data) {
  return request({
    url: '/salesforce/objConfig/batchSave/' + jobId,
    method: 'post',
    data: data
  })
}