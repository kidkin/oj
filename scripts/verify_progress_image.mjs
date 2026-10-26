import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
const source=await fs.readFile(new URL('../hoj-vue/src/common/progress-image.js',import.meta.url),'utf8')
const {progressPages,PROGRESS_STATES,progressTime}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'))
for (const [rows,columns] of [[0,3],[1,1],[80,12],[81,13],[2000,50]]) {
  const report={students:Array(rows).fill({}),problems:Array(columns).fill({})}
  const pages=progressPages(report)
  assert.equal(pages.length,Math.max(1,Math.ceil(rows/80))*Math.ceil(columns/12))
  const cells=new Set()
  for (const p of pages) {
    assert.ok(p.rowEnd-p.rowStart<=80 && p.colEnd-p.colStart<=12)
    for(let r=p.rowStart;r<p.rowEnd;r++) for(let c=p.colStart;c<p.colEnd;c++) {
      const key=r+':'+c; assert.ok(!cells.has(key)); cells.add(key)
    }
  }
  assert.equal(cells.size,rows*columns)
}
assert.deepEqual(progressPages(null),[])
assert.deepEqual(Object.values(PROGRESS_STATES).map(s=>s.label),['正确','错误','未提交','部分正确','判题中'])
assert.equal(new Set(Object.values(PROGRESS_STATES).map(s=>s.background)).size,5)
assert.match(progressTime(Date.UTC(2026,9,26,0,0,0)),/8:00:00/)
console.log('PROGRESS IMAGE LOGIC PASSED: page boundaries, every student/question retained, status palette and Shanghai time')
