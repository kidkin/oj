import Vue from 'vue'
import axios from 'axios'
import './practice-user.css'

// A single batched lookup feeds every username on a page, including reused table rows.
const client = axios.create({timeout: 15000, headers: {'Url-Type': 'general'}})
const state = Vue.observable({records: {}})
const pending = new Map()
const elements = new Map()
const bindings = new WeakMap()
const TTL = 30000
let timer = null
let busy = false

export function userIdentity (user) {
  if (!user) return null
  const uid = user.uid === null || user.uid === undefined ? '' : String(user.uid).trim()
  const username = typeof user.username === 'string' ? user.username.trim() : ''
  if (uid && uid.length <= 32) return {key: 'uid:' + uid, uid}
  if (username && username.length <= 100) return {key: 'name:' + username.toLowerCase(), username}
  return null
}
function paint (element) {
  const binding = bindings.get(element)
  if (!binding) return
  const record = state.records[binding.identity.key]
  const level = record && record.level
  element.classList.add('practice-user')
  if (binding.tier) element.classList.remove('practice-user--' + binding.tier)
  binding.tier = level ? level.key : 'newbie'
  element.classList.add('practice-user--' + binding.tier)
  element.style.setProperty('--practice-user-color', level ? level.color : '#777777')
  if (level) {
    element.setAttribute('data-practice-score', String(level.score))
    element.setAttribute('data-practice-level', level.key)
    const title = level.key === 'staff' ? '工作人员 · 不参与评级' : level.title + ' · 成长分 ' + level.score + ' · 累计通过 ' + level.solvedCount + ' 题'
    binding.autoTitle = binding.originalTitle ? binding.originalTitle + ' · ' + title : title
    element.setAttribute('title', binding.autoTitle)
  } else {
    element.removeAttribute('data-practice-score')
    element.removeAttribute('data-practice-level')
    if (binding.originalTitle !== null) element.setAttribute('title', binding.originalTitle)
    else element.removeAttribute('title')
  }
}
function store (key, level) {
  Vue.set(state.records, key, {level, at: Date.now()})
  const nodes = elements.get(key)
  if (nodes) nodes.forEach(paint)
}
async function request (identities) {
  const body = {uids: [], usernames: []}
  identities.forEach(identity => identity.uid ? body.uids.push(identity.uid) : body.usernames.push(identity.username))
  const response = await client.post('/api/user-practice-levels', body)
  if (response.data.status !== 200 || !Array.isArray(response.data.data)) throw new Error('Practice levels unavailable')
  const found = new Set()
  response.data.data.forEach(level => {
    const uid = 'uid:' + level.uid; const name = 'name:' + level.username.toLowerCase()
    store(uid, level); store(name, level); found.add(uid); found.add(name)
  })
  identities.forEach(identity => { if (!found.has(identity.key)) store(identity.key, null) })
}
async function flush () {
  timer = null
  if (busy || !pending.size) return
  busy = true
  const selected = [...pending.values()].slice(0, 100)
  selected.forEach(identity => pending.delete(identity.key))
  try { await request(selected) } catch (_) { /* Name links remain usable; refresh retries later. */ }
  finally {
    busy = false
    if (pending.size) timer = window.setTimeout(flush, 20)
  }
}
function enqueue (identity, force = false) {
  if (!identity) return
  const cached = state.records[identity.key]
  if (!force && cached && Date.now() - cached.at < TTL) return
  pending.set(identity.key, identity)
  if (!timer && !busy) timer = window.setTimeout(flush, 20)
}
export function practiceLevel (user) {
  const identity = userIdentity(user)
  if (!identity) return null
  enqueue(identity)
  return state.records[identity.key] ? state.records[identity.key].level : null
}
export async function loadPracticeLevels (users) {
  const selected = new Map()
  users.forEach(user => { const identity = userIdentity(user); if (identity) selected.set(identity.key, identity) })
  const all = [...selected.values()]
  for (let start = 0; start < all.length; start += 100) await request(all.slice(start, start + 100))
  return users.map(user => { const identity = userIdentity(user); return identity && state.records[identity.key] ? state.records[identity.key].level : null })
}
function unbind (element) {
  const old = bindings.get(element)
  if (!old) return
  const nodes = elements.get(old.identity.key)
  if (nodes) { nodes.delete(element); if (!nodes.size) elements.delete(old.identity.key) }
  element.classList.remove('practice-user', 'practice-user--' + old.tier)
  element.style.removeProperty('--practice-user-color')
  element.removeAttribute('data-practice-score'); element.removeAttribute('data-practice-level')
  if (element.getAttribute('title') === old.autoTitle) {
    if (old.originalTitle === null) element.removeAttribute('title')
    else element.setAttribute('title', old.originalTitle)
  }
  bindings.delete(element)
}
function update (element, value) {
  const identity = userIdentity(value)
  const old = bindings.get(element)
  if (!identity) { unbind(element); return }
  if (old && old.identity.key !== identity.key) unbind(element)
  if (!bindings.has(element)) {
    bindings.set(element, {identity, originalTitle: element.getAttribute('title'), tier: null, autoTitle: null})
    if (!elements.has(identity.key)) elements.set(identity.key, new Set())
    elements.get(identity.key).add(element)
  }
  paint(element); enqueue(identity)
}
export default {
  install (vue) {
    vue.directive('user-level', {
      inserted: (element, binding) => update(element, binding.value),
      componentUpdated: (element, binding) => update(element, binding.value),
      unbind
    })
    const refresh = () => {
      if (document.hidden) return
      elements.forEach(nodes => { const first = nodes.values().next().value; const state = first && bindings.get(first); if (state) enqueue(state.identity) })
    }
    window.setInterval(refresh, 60000)
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
  }
}
