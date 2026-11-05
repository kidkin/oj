const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const root = path.resolve(__dirname, '../..')
const context = {}; vm.createContext(context)
vm.runInContext(fs.readFileSync(path.join(root, 'src/common/course-catalog.js'), 'utf8').replace('export async function', 'async function') + '\nthis.load = loadCourseCatalog;', context)
async function main () {
  const all = Array.from({length: 117}, (_, index) => ({id: index + 1, status: index % 5 === 0 ? 2 : 1}))
  const requests = []
  const rows = await context.load(async query => {
    requests.push(query)
    return {total: all.length, records: all.slice((query.page - 1) * query.limit, query.page * query.limit)}
  }, {keyword: '题', scope: 'mine', page: 99, limit: 12})
  assert.strictEqual(requests.length, 3)
  assert.ok(requests.every(r => r.limit === 50 && r.keyword === '题' && r.scope === 'mine'))
  assert.strictEqual(rows.length, all.filter(r => r.status !== 2).length)
  assert.strictEqual(rows[rows.length - 1].id, 117)
  let cancelled = false; let calls = 0
  assert.strictEqual((await context.load(async () => { calls++; cancelled = true; return {total: 100, records: all.slice(0, 50)} }, {}, () => cancelled)).length, 0)
  assert.strictEqual(calls, 1, 'An old search must not fetch or display further pages')
  assert.strictEqual((await context.load(async () => ({total: 999, records: []}), {})).length, 0)
  const view = fs.readFileSync(path.join(root, 'src/views/oj/edu/CourseList.vue'), 'utf8')
  assert.ok(!view.includes('<el-pagination'), 'Course cards must not have a fixed UI page count')
  assert.ok(view.includes('request === this.catalogRequest'), 'Only the newest search may update the displayed catalog')
  console.log('PASS course catalog: all pages, archived courses hidden, cancellation, stable ordering and no fixed UI page size')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
