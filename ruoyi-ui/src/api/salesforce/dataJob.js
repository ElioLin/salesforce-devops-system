import request from '@/utils/request'

// 1. 任务管理 (Job)
export function listJob(query) {
  return request({
    url: '/salesforce/dataJob/list',
    method: 'get',
    params: query
  })
}

export function getJob(id) {
  return request({
    url: '/salesforce/dataJob/' + id,
    method: 'get'
  })
}

export function addJob(data) {
  return request({
    url: '/salesforce/dataJob',
    method: 'post',
    data: data
  })
}

export function updateJob(data) {
  return request({
    url: '/salesforce/dataJob',
    method: 'put',
    data: data
  })
}

export function delJob(id) {
  return request({
    url: '/salesforce/dataJob/' + id,
    method: 'delete'
  })
}