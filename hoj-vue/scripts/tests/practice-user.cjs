const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const root = path.resolve(__dirname, '../..')

async function main () {
  const requests = []; const listeners = {}; let directive; let now = Date.now(); let fail = false
  const users = {a: {uid:'a', username:'alice', score:10, solvedCount:10, key:'pupil', color:'#008000', title:'起步'}, b:{uid:'b', username:'bob', score:70, solvedCount:70, key:'expert', color:'#164dcc', title:'熟练'}}
  const context = {
    Vue: {observable:value => value, set:(object,key,value) => { object[key] = value }},
    axios: {create: () => ({post:async (url, body) => {
      requests.push({url,body}); await wait(15); if (fail) throw new Error('offline')
      return {data:{status:200, data:Object.values(users).filter(user => body.uids.includes(user.uid) || body.usernames.includes(user.username))}}
    }})},
    window: {setTimeout, setInterval:callback => { listeners.interval = callback }, addEventListener:(event, callback) => { listeners[event] = callback }},
    document: {hidden:false, addEventListener:(event,callback) => { listeners[event] = callback }},
    Date: {now:() => now}, Map, Set, WeakMap, Error, String, Array, Object, Promise
  }
  const source = fs.readFileSync(path.join(root,'src/common/practice-user.js'),'utf8')
    .replace(/^import .*\n/gm,'').replace(/export (async )?function /g,'$1function ').replace('export default {','const plugin = {')
  vm.createContext(context); vm.runInContext(source+'\nthis.api={plugin,loadPracticeLevels,userIdentity,practiceLevel};',context)
  context.api.plugin.install({directive:(name,value) => { directive = value }})
  const element = () => {
    const attrs = new Map(); const classes = new Set(); const styles = new Map()
    return {attrs,classes,styles,classList:{add:(...values) => values.forEach(v => classes.add(v)),remove:(...values) => values.forEach(v => classes.delete(v))},
      style:{setProperty:(key,value) => styles.set(key,value),removeProperty:key => styles.delete(key)},
      getAttribute:key => attrs.has(key) ? attrs.get(key) : null, setAttribute:(key,value) => attrs.set(key,value), removeAttribute:key => attrs.delete(key)}
  }
  const names = Array.from({length:80}, element)
  names.forEach(node => directive.inserted(node,{value:{uid:'a',username:'alice'}}))
  await wait(100)
  assert.strictEqual(requests.length,1,'Repeated names must share one batch')
  assert.strictEqual(requests[0].body.uids.length,1)
  names.forEach(node => assert.strictEqual(node.styles.get('--practice-user-color'),'#008000'))
  const alias = element(); directive.inserted(alias,{value:{username:'ALICE'}}); await wait(50)
  assert.strictEqual(requests.length,1,'UID and username aliases must share their result')
  assert.strictEqual(alias.attrs.get('data-practice-score'),'10')
  names.forEach(node => directive.unbind(node)); directive.unbind(alias)
  now += 60000
  const recycled = element(); directive.inserted(recycled,{value:{uid:'a'}})
  await wait(25); directive.componentUpdated(recycled,{value:{uid:'b'}})
  await wait(120)
  assert.strictEqual(recycled.styles.get('--practice-user-color'),'#164dcc','A stale response must not color a recycled row')
  assert.strictEqual(recycled.attrs.get('data-practice-score'),'70')
  const previous = requests.length; context.document.hidden = true; now += 60000; listeners.interval(); await wait(50)
  assert.strictEqual(requests.length,previous,'Hidden pages do not poll')
  context.document.hidden = false; fail = true; listeners.focus(); await wait(80)
  assert.strictEqual(recycled.styles.get('--practice-user-color'),'#164dcc','Lookup failure must keep the existing name usable')
  fail = false; users.b = {...users.b,score:160,solvedCount:160,key:'master',color:'#c77700',title:'精英'}
  listeners.focus(); await wait(80)
  assert.strictEqual(recycled.attrs.get('data-practice-score'),'160','A later focus retries an unsuccessful refresh')
  directive.unbind(recycled)
  assert.strictEqual(recycled.styles.has('--practice-user-color'),false)
  assert.strictEqual(recycled.classes.has('practice-user'),false)
  assert.strictEqual(context.api.userIdentity({uid:'',username:''}),null)

  // A profile opened directly must update when the first lookup adds a new cache key.
  const RealVue = require('vue')
  const reactive = {...context, Vue:RealVue}; vm.createContext(reactive)
  vm.runInContext(source+'\nthis.api={practiceLevel};',reactive)
  const profile = new RealVue({computed:{level () { return reactive.api.practiceLevel({uid:'b'}) }}})
  assert.strictEqual(profile.level,null)
  await wait(100); await RealVue.nextTick()
  assert.strictEqual(profile.level.score,160,'First-time profile lookup must invalidate the computed badge')
  profile.$destroy()

  const chapter = fs.readFileSync(path.join(root,'src/common/course-chapter.js'),'utf8').replace(/export function /g,'function ')
  const scope = {}; vm.createContext(scope); vm.runInContext(chapter+'\nthis.api={chineseNumber,courseTitle};',scope)
  for (const [number,text] of [[1,'一'],[10,'十'],[11,'十一'],[20,'二十'],[101,'一百零一'],[1001,'一千零一'],[10000,'一万'],[10001,'一万零一'],[10010,'一万零一十'],[100000001,'一亿零一'],[2147483647,'二十一亿四千七百四十八万三千六百四十七']]) {
    assert.strictEqual(scope.api.chineseNumber(number),text)
  }
  assert.strictEqual(scope.api.courseTitle({title:'选择与分支',chapterNumber:2,displayId:99}),'第二章 · 选择与分支')
  assert.strictEqual(scope.api.courseTitle({title:'第一章 · 入门',chapterNumber:1}),'第一章 · 入门')
  assert.strictEqual(scope.api.courseTitle({title:'第一章 · 入门',chapterNumber:2}),'第二章 · 入门')
  assert.strictEqual(scope.api.chineseNumber(0),'')
  console.log('PASS practice usernames: batching, aliases, recycled rows, refresh, failures, cleanup; chapter numbering and independent ordering')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
