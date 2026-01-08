import request from '@/utils/request'

// 1. 获取任务监控详情 (列出所有对象的运行状态)
export function getJobMonitor(jobId) {
  return request({
    url: '/salesforce/dataRunObjLog/monitor/' + jobId,
    method: 'get'
  })
}

// 2. 在线预览单个对象的比对结果
export function previewObjResult(objLogId, query) {
  return request({
    url: '/salesforce/dataRunObjLog/previewObj/' + objLogId,
    method: 'get',
    params: query
  })
}

// 3. 下载单个对象的比对结果 (通常直接 window.open，但也保留 API 定义)
export function downloadObjUrl(objLogId) {
  return process.env.VUE_APP_BASE_API + "/salesforce/dataRunObjLog/downloadObj/" + objLogId;
}

// 新增：重试对象
export function retryObjLog(objLogId) {
  return request({
    url: '/salesforce/dataRunObjLog/retry/' + objLogId,
    method: 'post'
  })
}