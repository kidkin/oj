import '@/common/api'
import axios from 'axios'

const request = (method, path, data, params) => axios({method, url: '/api/edu' + path, data, params}).then(r => r.data.data)

export default {
  courses: params => request('get', '/courses', undefined, params),
  createCourse: data => request('post', '/courses', data),
  course: id => request('get', `/courses/${id}`),
  updateCourse: (id, data) => request('put', `/courses/${id}`, data),
  uploadCourseIcon: (id, image, revision) => { const form = new FormData(); form.append('image', image); form.append('revision', revision); return request('post', `/courses/${id}/icon`, form) },
  removeCourseIcon: (id, revision) => request('delete', `/courses/${id}/icon`, undefined, {revision}),
  saveCourseProblems: (id, data) => request('put', `/courses/${id}/problems`, data),
  publicProblems: params => request('get', '/public-problems', undefined, params),
  classCourses: (id, params) => request('get', `/classes/${id}/courses`, undefined, params),
  attachCourses: (id, courseIds) => request('post', `/classes/${id}/courses`, {courseIds}),
  detachCourse: (id, courseId) => request('delete', `/classes/${id}/courses/${courseId}`),
  me: () => request('get', '/me'),
  classes: params => request('get', '/classes', undefined, params),
  create: data => request('post', '/classes', data),
  detail: id => request('get', `/classes/${id}`),
  progressProblems: (id, params) => request('get', `/classes/${id}/progress/problems`, undefined, params),
  classProgress: (id, problemIds) => request('post', `/classes/${id}/progress`, {problemIds}),
  update: (id, data) => request('put', `/classes/${id}`, data),
  members: (id, params) => request('get', `/classes/${id}/members`, undefined, params),
  addMembers: (id, data) => request('post', `/classes/${id}/members`, data),
  removeMember: (id, uid) => request('delete', `/classes/${id}/members/${encodeURIComponent(uid)}`),
  teachers: () => request('get', '/teachers'),
  grantTeacher: username => request('post', '/teachers', {username}),
  revokeTeacher: uid => request('delete', `/teachers/${encodeURIComponent(uid)}`)
}
