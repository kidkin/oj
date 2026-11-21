<template>
  <el-dialog title="代码查重" :visible="true" append-to-body width="min(780px, 96vw)" @close="$emit('close')">
    <h3>{{ row.displayPid || row.displayId || row.pid }} {{ row.title }}</h3>
    <el-form label-position="top" @submit.native.prevent="scan">
      <el-radio-group v-model="mode" size="small">
        <el-radio-button label="problem">整题查重</el-radio-button>
        <el-radio-button label="submission">这次提交 #{{ row.submitId }}</el-radio-button>
      </el-radio-group>
      <div class="filters">
        <el-form-item label="对比最近几天">
          <el-input-number v-model="days" :min="1" :max="3650" :precision="0" size="small" />
        </el-form-item>
        <el-form-item v-if="mode === 'problem'" label="语言">
          <el-select v-model="family" size="small">
            <el-option label="C / C++" value="cpp"/><el-option label="Python" value="python"/><el-option label="Java" value="java"/>
          </el-select>
        </el-form-item>
        <el-form-item label="最低相似度（%）">
          <el-input-number v-model="threshold" :min="50" :max="100" :precision="0" size="small" />
        </el-form-item>
      </div>
      <el-button native-type="submit" type="primary" size="small" :loading="busy">开始查重</el-button>
    </el-form>
    <div v-if="result" aria-live="polite">
      <p>{{ result.accounts }} 位同学 · {{ result.submissions }} 次提交 · {{ result.totalPairs }} 组达到阈值<span v-if="result.tooShort"> · {{ result.tooShort }} 份代码过短</span></p>
      <p v-if="result.totalPairs > 50">仅列出相似度最高的 50 组。</p>
      <el-collapse accordion>
        <el-collapse-item v-for="p in result.pairs" :key="p.left.submitId+'-'+p.right.submitId">
          <template slot="title"><b>{{ p.score }}%</b>{{ person(p.left) }}　与　{{ person(p.right) }}</template>
          <div v-for="(block,i) in p.blocks" :key="i" class="evidence">
            <div v-for="side in ['left','right']" :key="side"><small>#{{ p[side].submitId }} · 第 {{ block[side].line }}–{{ block[side].end }} 行</small><pre>{{ block[side].code }}</pre></div>
          </div>
        </el-collapse-item>
      </el-collapse>
      <small>请结合题目模板和共同写法复核相似片段。</small>
    </div>
  </el-dialog>
</template>
<script>
import edu from '@/common/edu-api'
export default {
  props: {row: {type: Object, required: true}, initial: {type: String, default: 'problem'}},
  data () {
    const lang = (this.row.language || '').toLowerCase()
    return {mode: this.initial, family: /python|pypy/.test(lang) ? 'python' : /^java(?!script)/.test(lang) ? 'java' : 'cpp',
      days: 30, threshold: 75, result: null, busy: false, requestId: 0}
  },
  computed: {
    params () {
      return {pid: this.row.pid, submitId: this.mode === 'submission' ? this.row.submitId : undefined,
        family: this.family, days: this.days, threshold: this.threshold, contestId: this.row.cid || 0, groupId: this.row.gid || undefined}
    }
  },
  watch: {params () { this.requestId++; this.result = null; this.busy = false }},
  methods: {
    person (r) { return (r.realname || r.username) + ' #' + r.submitId },
    async scan () {
      const id = ++this.requestId
      this.busy = true; this.result = null
      try {
        const data = await edu.submissionSimilarity(this.params)
        if (id === this.requestId) this.result = data
      } catch (_) {} finally { if (id === this.requestId) this.busy = false }
    }
  }
}
</script>
<style scoped>
.filters { display:flex; gap:14px; flex-wrap:wrap; margin-top:14px; }
.filters .el-form-item { margin-bottom:12px; }
.filters .el-select { max-width:128px; }
.evidence {display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
pre {white-space:pre-wrap;overflow-wrap:anywhere;background:#f2f5fa;padding:10px;border-radius:8px}
b {color:#285f9e;margin-right:12px}
@media(max-width:600px){.evidence {grid-template-columns:1fr}}
</style>
