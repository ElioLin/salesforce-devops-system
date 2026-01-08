import request from '@/utils/request'

// 3. 核心操作
export function runJob(jobId) {
  return request({
    url: '/salesforce/reconcile/run/' + jobId,
    method: 'post'
  })
}