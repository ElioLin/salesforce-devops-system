import request from '@/utils/request'

// 4. 日志与结果
export function listLogs(query) {
    return request({
      url: '/salesforce/runLog/list',
      method: 'get',
      params: query
    })
}

export function downloadLog(query) {
    return request({
      url: '/salesforce/runLog/download',
      method: 'get',
      params: query
    })
}