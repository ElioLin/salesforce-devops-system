import request from '@/utils/request'

export function listConfig(query) { return request({ url: '/salesforce/gitConfig/list', method: 'get', params: query }) }
export function getConfig(id) { return request({ url: '/salesforce/gitConfig/' + id, method: 'get' }) }
export function addConfig(data) { return request({ url: '/salesforce/gitConfig/save', method: 'post', data: data }) }
export function updateConfig(data) { return request({ url: '/salesforce/gitConfig/save', method: 'post', data: data }) }
export function delConfig(id) { return request({ url: '/salesforce/gitConfig/' + id, method: 'delete' }) }
export function testConnection(data) { return request({ url: '/salesforce/gitConfig/testConnection', method: 'post', data: data }) }
export function getCurrentConfig() { return request({ url: '/salesforce/gitConfig/current', method: 'get' }) }
export function getRemoteBranches() {
    return request({ url: '/salesforce/gitConfig/branches', method: 'get' })
}