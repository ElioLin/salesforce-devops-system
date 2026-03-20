import request from '@/utils/request'

// 3. 核心操作
export function runJob(jobId) {
  return request({
    url: '/salesforce/reconcile/run/' + jobId,
    method: 'post'
  })
}

// 【新增】停止整个比对任务
export function stopJob(jobId) {
  return request({
    url: '/salesforce/reconcile/stop/' + jobId,
    method: 'post'
  })
}

// 【新增】停止单个对象比对任务
export function stopObject(objLogId) {
  return request({
    url: '/salesforce/reconcile/stopObj/' + objLogId,
    method: 'post'
  })
}