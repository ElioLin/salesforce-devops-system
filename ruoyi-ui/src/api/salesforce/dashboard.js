import request from '@/utils/request'

// 获取仪表盘聚合数据
export function getDashboardData() {
  return request({
    url: '/salesforce/dashboard/data',
    method: 'get'
  })
}