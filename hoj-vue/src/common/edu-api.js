import '@/common/api'
import axios from 'axios'

const request = (method, path, data, params) => axios({method, url: '/api/edu' + path, data, params}).then(r => r.data.data)

export default {
  me: () => request('get', '/me'),
  classes: params => request('get', '/classes', undefined, params),
  create: data => request('post', '/classes', data),
  detail: id => request('get', `/classes/${id}`),
  update: (id, data) => request('put', `/classes/${id}`, data),
  members: (id, params) => request('get', `/classes/${id}/members`, undefined, params),
  addMembers: (id, data) => request('post', `/classes/${id}/members`, data),
  removeMember: (id, uid) => request('delete', `/classes/${id}/members/${encodeURIComponent(uid)}`),
  teachers: () => request('get', '/teachers'),
  grantTeacher: username => request('post', '/teachers', {username}),
  revokeTeacher: uid => request('delete', `/teachers/${encodeURIComponent(uid)}`)
}
