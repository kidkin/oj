<template>
  <section>
    <el-tabs v-model="tab">
      <el-tab-pane label="内容审核" name="queue">
        <el-radio-group v-model="state" size="small" @change="resetQueue">
          <el-radio-button label="PENDING">待审核</el-radio-button><el-radio-button label="ALLOW">已通过</el-radio-button><el-radio-button label="DENY">已驳回</el-radio-button>
        </el-radio-group>
        <div v-loading="busy" class="records">
          <article v-for="r in rows" :key="r.id" class="record">
            <strong>{{ r.kind === 'CODE' ? '代码' : '题面' }} · {{ r.subject }}</strong>
            <small>{{ r.username }} · 词表 v{{ r.revision }}<template v-if="r.reviewedBy"> · 复核人 {{ r.reviewedBy }}</template></small>
            <p>{{ r.hits }}</p><pre>{{ r.excerpt }}</pre>
            <div v-if="state === 'PENDING'">
              <el-button size="small" :disabled="acting" @click="decide(r,'ALLOW')">通过</el-button>
              <el-button size="small" type="danger" plain :disabled="acting" @click="decide(r,'DENY')">驳回</el-button>
            </div>
          </article>
          <p v-if="!busy && !rows.length">暂无记录</p>
        </div>
        <el-button v-if="more" size="small" :loading="busy" @click="loadQueue(true)">加载更多</el-button>
      </el-tab-pane>
      <el-tab-pane v-if="!classId" label="关键词设置" name="rules">
        <el-form label-position="top" @submit.native.prevent="saveRules">
          <el-form-item :label="'审核词表 · v' + rules.revision"><el-input type="textarea" v-model="rules.words" :rows="9" maxlength="12000" placeholder="每行一个完整词语" /></el-form-item>
          <el-button native-type="submit" type="primary" :disabled="!rules.revision || acting">保存新版本</el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane v-if="classId" label="代码相似度" name="compare">
        <el-form label-position="top" inline @submit.native.prevent="compare">
          <el-form-item label="题目"><el-select v-model="pid" filterable remote :remote-method="findProblems" placeholder="输入题号或标题" :loading="searching">
            <el-option v-for="p in problems" :key="p.id" :value="p.id" :label="p.problemId+' · '+p.title" />
          </el-select></el-form-item>
          <el-form-item label="语言"><el-select v-model="family"><el-option label="C / C++" value="cpp"/><el-option label="Python" value="python"/><el-option label="Java" value="java"/></el-select></el-form-item>
          <el-form-item label="每人取一次提交"><el-select v-model="ac"><el-option label="最近一次通过" :value="true"/><el-option label="最近一次提交" :value="false"/></el-select></el-form-item>
          <el-form-item label="最低相似度（%）"><el-input-number v-model="threshold" :min="50" :max="100" :precision="0" /></el-form-item>
          <el-form-item label="　"><el-button type="primary" native-type="submit" :disabled="!pid" :loading="scanning">开始查重</el-button></el-form-item>
        </el-form>
        <p v-if="result">{{ result.accounts }} 个账号 · {{ result.tooShort }} 份代码过短 · {{ result.totalPairs }} 组达到阈值<span v-if="result.totalPairs > 50">，显示相似度最高的 50 组</span></p>
        <el-collapse v-if="result" v-model="expanded" accordion>
          <el-collapse-item v-for="pair in result.pairs" :key="pair.left.submitId+'-'+pair.right.submitId" :name="pair.left.submitId+'-'+pair.right.submitId">
            <template slot="title"><b class="score">{{ pair.score }}%</b>{{ person(pair.left) }}　与　{{ person(pair.right) }}</template>
            <div v-for="(block, i) in pair.blocks" :key="i" class="pair">
              <section v-for="side in ['left','right']" :key="side"><small>{{ pair[side].username }} · #{{ pair[side].submitId }} · 第 {{ block[side].line }}–{{ block[side].end }} 行</small><pre>{{ block[side].code }}</pre></section>
            </div>
          </el-collapse-item>
        </el-collapse>
        <p class="muted">比较本班学生的同题练习。相似度用于定位值得复核的片段，尤其要留意教师模板和写法固定的入门题。</p>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
<script>
import edu from '@/common/edu-api'
export default {
  props: {classId: {type: [Number, String], default: null}},
  data () { return {tab: 'queue', state: 'PENDING', rows: [], more: false, busy: false, acting: false,
    rules: {revision: 0, words: ''}, pid: null, problems: [], family: 'cpp', ac: true, threshold: 75,
    searching: false, scanning: false, result: null, expanded: '', queueVersion: 0, searchVersion: 0} },
  watch: {
    tab (value) {
      if (value === 'rules') this.loadRules()
      if (value === 'compare' && !this.problems.length) this.findProblems('')
    },
    pid () { this.result = null }, family () { this.result = null }, ac () { this.result = null }, threshold () { this.result = null }
  },
  mounted () { this.resetQueue() },
  methods: {
    resetQueue () { this.rows = []; this.more = false; this.loadQueue(false) },
    async loadQueue (append) {
      const version = ++this.queueVersion
      this.busy = true
      try {
        const items = await edu.reviewQueue({classId: this.classId, state: this.state, before: append ? this.rows[this.rows.length - 1].id : 0})
        if (version !== this.queueVersion) return
        this.rows = (append ? this.rows : []).concat(items.slice(0, 50)); this.more = items.length > 50
      } catch (_) {} finally { if (version === this.queueVersion) this.busy = false }
    },
    async decide (row, decision) {
      try {
        await this.$confirm(decision === 'ALLOW' ? '确认通过这份内容？' : '确认驳回这份内容？', '复核')
        this.acting = true
        await edu.reviewDecision(row.id, {classId: this.classId, decision})
        this.$message.success('复核结果已保存'); this.resetQueue()
      } catch (_) {} finally { this.acting = false }
    },
    async loadRules () { try { this.rules = await edu.reviewRules() } catch (_) {} },
    async saveRules () {
      this.acting = true
      try { await edu.saveReviewRules(this.rules); await this.loadRules(); this.$message.success('新词表已启用') } catch (_) {} finally { this.acting = false }
    },
    async findProblems (keyword) {
      const version = ++this.searchVersion
      this.searching = true
      try { const data = await edu.progressProblems(this.classId, {page: 1, limit: 30, keyword}); if (version === this.searchVersion) this.problems = data.records } catch (_) {} finally { if (version === this.searchVersion) this.searching = false }
    },
    person (r) { return r.realname ? r.realname + '（' + r.username + '）' : r.username },
    async compare () {
      if (!this.pid || this.scanning) return
      const params = {classId: this.classId, pid: this.pid, family: this.family, ac: this.ac, threshold: this.threshold}
      this.result = null; this.expanded = ''; this.scanning = true
      try {
        const data = await edu.similarity(params)
        if (Object.keys(params).every(k => params[k] === this[k])) this.result = data
      } catch (_) {} finally { this.scanning = false }
    }
  }
}
</script>
<style scoped>
.records { min-height:64px; margin:16px 0; }
.record { border:1px solid #d9e4ea; padding:16px; border-radius:10px; margin:12px 0; }
small { display:block; color:#516677; margin:6px 0; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; background:#f2f6f8; padding:10px; border-radius:6px; max-height:230px; overflow:auto; }
.pair { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.score { margin-right:14px; color:#315f8b; }.muted { color:#516677; }
@media(max-width:650px) { .pair { grid-template-columns:1fr; } }
</style>
