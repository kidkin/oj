<template>
  <section v-if="paper.enabled" class="paper-sheet" aria-label="比赛试卷">
    <header class="paper-header">
      <div><h2>比赛试卷</h2><p v-if="paper.minutes">个人答题时长 {{ paper.minutes }} 分钟</p></div>
      <strong v-if="paper.attempt" class="paper-clock">{{ timerLabel }}</strong>
    </header>
    <template v-if="!paper.attempt">
      <p v-if="paper.retake">管理员已允许重考。点击开始后将打开一份空白试卷。</p>
      <p>可开考时间 {{ stamp(paper.open) }} 至 {{ stamp(paper.lastStart) }}</p>
      <el-button type="primary" :loading="busy" :disabled="clock < paper.open || clock >= paper.lastStart" @click="start">开始比赛</el-button>
    </template>
    <template v-else>
      <p class="deadline">个人截止时间 {{ stamp(paper.attempt.deadline) }}</p>
      <div class="paper-layout">
        <nav class="answer-card" aria-label="答题卡">
          <h3>答题卡</h3>
          <p>{{ filled }} / {{ exercises.length }} 道客观题已作答</p>
          <div class="question-nav">
            <button v-for="(q,i) in exercises" :key="q.id" type="button"
              :class="{answered: answered(q), current: active===q.id}"
              :aria-label="'第 ' + (i+1) + ' 题，' + (answered(q) ? '已答' : '未答')" @click="jump(q.id)">{{ i+1 }}</button>
          </div>
          <template v-if="programs.length">
            <p>程序设计</p>
            <div class="question-nav">
              <button v-for="p in programs" :key="p.id" type="button" :class="{answered: p.submissions, current: active==='program-'+p.id}"
                :aria-label="'程序题 ' + p.id + (p.submissions ? '，已送评' : '，未送评')" @click="jump('program-'+p.id)">{{ p.id }}</button>
            </div>
          </template>
          <button v-if="info.length" class="info-nav" type="button" @click="jump(info[0].id)">考生资料 {{ info.filter(answered).length }} / {{ info.length }}</button>
        </nav>
        <div class="paper-content">
          <div v-for="(q,i) in exercises" :key="q.id" :ref="'q-'+q.id" class="paper-question">
            <h3>{{ i+1 }}. {{ q.label }} <small>{{ q.points }} 分</small></h3>
            <el-radio-group v-if="q.type==='choice'" v-model="answers[q.id]" :disabled="locked || busyFinal" @change="schedule(q.id)">
              <el-radio v-for="(option,k) in q.options" :key="k" :label="letter(k)">{{ letter(k) }}. {{ option }}</el-radio>
            </el-radio-group>
            <el-input v-else v-model="answers[q.id]" maxlength="1000" :aria-label="q.label" :disabled="locked || busyFinal" @input="schedule(q.id)"/>
          </div>
          <div v-if="programs.length" class="paper-question">
            <h3>程序设计</h3>
            <div v-for="p in programs" :key="p.id" :ref="'q-program-'+p.id" class="program-item">
              <router-link :to="{name:'ContestProblemDetails',params:{contestID:cid,problemID:p.id}}" target="_blank" @click.native="flush">{{ p.id }} · {{ p.title }} ↗</router-link>
              <span>{{ p.submissions ? (p.score == null ? '评测中' : '已送评') : '未送评' }}<template v-if="p.points!=null"> · {{ p.score == null ? p.points+' 分' : p.score+' / '+p.points+' 分' }}</template></span>
            </div>
          </div>
          <div v-if="info.length" class="paper-question"><h3>考生资料</h3>
            <label v-for="q in info" :key="q.id" :ref="'q-'+q.id">{{ q.label }}
              <el-input v-model="answers[q.id]" maxlength="1000" :disabled="locked || busyFinal" @input="schedule(q.id)"/>
            </label>
          </div>
          <p v-if="notice" role="status">{{ notice }}</p>
          <p v-if="missing.length" class="required" role="alert">尚未填写 {{ missing.join('、') }}</p>
          <div v-if="!locked" class="paper-controls">
            <el-button type="primary" :loading="busyFinal" @click="submit">提交试卷</el-button>
            <el-button :disabled="busy || busyFinal" @click="saveNow">保存草稿</el-button>
          </div>
          <el-button v-if="conflict" @click="load()">重新读取试卷</el-button>
          <div v-if="paper.closed" class="paper-result">
            客观题 {{ paper.writtenScore }} 分 · 编程题 {{ paper.programScore }} 分 · 合计 {{ paper.writtenScore+paper.programScore }} 分
            <el-button v-if="programs.length" size="small" :loading="busy" @click="load()">刷新评测结果</el-button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
<script>
import edu from '@/common/edu-api'

export default {
  props: {cid: {required:true}},
  data() {
    return {paper:{}, answers:{}, active:'', clock:Date.now(), offset:0, tick:null, delay:null,
      queue:Promise.resolve(), notice:'', missing:[], busy:false, busyFinal:false, conflict:false, version:0}
  },
  computed: {
    exercises() { return (this.paper.questions || []).filter(q => q.type !== 'info') },
    info() { return (this.paper.questions || []).filter(q => q.type === 'info') },
    programs() { return this.paper.programs || [] },
    filled() { return this.exercises.filter(this.answered).length },
    expired() { return this.paper.attempt && this.clock >= Number(this.paper.attempt.deadline) },
    locked() { return this.conflict || this.paper.closed || this.expired },
    timerLabel() {
      if (this.conflict) return '请重新读取试卷'
      if (this.locked) return '答题已结束'
      const s = Math.ceil((Number(this.paper.attempt.deadline) - this.clock)/1000)
      return '剩余 ' + [Math.floor(s/3600),Math.floor(s/60)%60,s%60].map(x => String(x).padStart(2,'0')).join(':')
    }
  },
  watch: {
    cid: {immediate:true, handler() {
      this.version++
      this.paper = {}
      this.answers = {}
      this.conflict = false
      this.active = ''
      this.notice = ''
      this.missing = []
      clearTimeout(this.delay)
      this.delay = null
      this.load()
    }}
  },
  mounted() {
    this.tick = setInterval(() => {
      this.clock = Date.now() + this.offset
      if (this.expired && !this.paper.closed && !this.busy && !this.conflict) this.load()
    }, 1000)
    window.addEventListener('focus', this.onFocus)
  },
  beforeDestroy() {
    clearInterval(this.tick)
    window.removeEventListener('focus', this.onFocus)
    this.flush()
  },
  methods: {
    stamp(t) { return new Date(Number(t)).toLocaleString('zh-CN',{hour12:false}) },
    letter(index) { return String.fromCharCode(65+index) },
    answered(q) { return Boolean((this.answers[q.id] || '').trim()) },
    jump(id) {
      this.active = id
      const ref = this.$refs['q-'+id]
      const target = Array.isArray(ref) ? ref[0] : ref
      if (target) target.scrollIntoView({block:'center', behavior:'smooth'})
    },
    apply(state, restore) {
      this.paper = state
      this.offset = Number(state.now || Date.now()) - Date.now()
      this.clock = Date.now() + this.offset
      if (restore) {
        const answers = {}
        for (const q of state.questions || []) answers[q.id] = (state.answers || {})[q.id] || ''
        this.answers = answers
        this.conflict = false
      }
    },
    async onFocus() {
      if (!this.paper.attempt || !this.delay) {
        await this.queue
        if (!this.delay && !this.busy && !this.busyFinal) await this.load()
      }
    },
    async load() {
      const version = this.version
      this.busy = true
      try {
        const state = await edu.paper(this.cid,'','get')
        if (version === this.version) {
          this.apply(state,true)
          this.notice = ''
        }
      } catch (e) {
        if (version === this.version) this.notice = '读取试卷失败，请刷新重试'
      } finally { if (version === this.version) this.busy = false }
    },
    async start() {
      try {
        await this.$confirm(this.paper.minutes ? '确定后立即开始个人计时，刷新或退出都不会暂停。' : '确定开始本场答题？','开始比赛')
        this.busy = true
        this.apply(await edu.paper(this.cid,'/start','post'),true)
      } catch (e) { /* Request layer displays the server error. */ }
      finally { this.busy = false }
    },
    schedule(id) {
      this.active = id
      this.missing = []
      this.notice = '正在保存…'
      clearTimeout(this.delay)
      this.delay = setTimeout(() => this.saveNow(), 350)
    },
    flush() {
      if (this.delay && !this.locked && !this.busyFinal) return this.saveNow()
      clearTimeout(this.delay)
    },
    saveNow() {
      clearTimeout(this.delay)
      this.delay = null
      return this.enqueue(false)
    },
    enqueue(final) {
      const version = this.version
      const job = async () => {
        if (this.conflict || version !== this.version || !this.paper.attempt || this.paper.closed) return
        try {
          const data = {revision:this.paper.attempt.revision, answers:{...this.answers}}
          const state = await edu.paper(this.cid, final ? '/submit' : '/draft', final ? 'post' : 'put', data)
          if (version === this.version) {
            this.apply(state,false)
            this.notice = final ? '试卷已提交' : '草稿已保存'
          }
        } catch (e) {
          if (version !== this.version) return
          const error = e.data || (e.response || {}).data || {}
          if (error.status === 409) this.conflict = true
          this.notice = error.msg || '保存失败，请重试'
        }
      }
      this.queue = this.queue.then(job,job)
      return this.queue
    },
    async submit() {
      const gaps = this.paper.questions.filter(q => !this.answered(q))
      this.missing = gaps.map(q => q.type==='info' ? q.label : '第 '+(this.exercises.indexOf(q)+1)+' 题 '+q.label)
      if (gaps.length) {
        this.jump(gaps[0].id)
        await this.$alert('尚未填写：'+this.missing.join('、'),'请补全试卷')
        return
      }
      const unsubmitted = this.programs.filter(p => !p.submissions).map(p => p.id)
      const reminder = unsubmitted.length ? '程序题 '+unsubmitted.join('、')+' 可能尚未送评。' : ''
      try {
        await this.$confirm(reminder+'交卷后会停止接收作答。确定交卷吗？','提交试卷')
      } catch (e) { return }
      this.busyFinal = true
      clearTimeout(this.delay)
      this.delay = null
      try { await this.enqueue(true) }
      finally { this.busyFinal = false }
    }
  }
}
</script>
<style scoped>
.paper-sheet{background:#fff;border:1px solid #dbe6ef;border-radius:16px;padding:clamp(14px,3vw,32px);margin-bottom:16px;color:#243a50}
.paper-header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
.paper-sheet h2,.paper-sheet h3{margin:0 0 10px}.paper-sheet p{margin:8px 0 12px}
.paper-clock{background:#e7f2ff;color:#195996;border-radius:9px;padding:9px 14px;font-variant-numeric:tabular-nums}
.paper-layout{display:grid;grid-template-columns:190px minmax(0,1fr);gap:24px;align-items:start}
.answer-card{position:sticky;top:12px;max-height:70vh;overflow:auto;border:1px solid #dce7ef;border-radius:12px;background:#f6f9fc;padding:14px}
.answer-card p,.deadline,.program-item span{font-size:13px;color:#52677c}
.question-nav{display:flex;flex-wrap:wrap;gap:6px}.question-nav button,.info-nav{border:1px solid #b7cbdc;background:#fff;border-radius:7px;color:#294b69;cursor:pointer;min-width:32px;min-height:32px}
.question-nav button.answered{background:#ddf2e9;color:#155440;border-color:#91c7b3}
.question-nav button.current{outline:2px solid #397cc1;outline-offset:1px}
.info-nav{margin-top:12px;padding:4px 9px}.paper-question,.program-item,.paper-question label{scroll-margin:24px}
.paper-question{border-top:1px solid #e2e9ee;margin:0 0 16px;padding-top:16px}
.paper-question h3{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.55}
.paper-question small{color:#52677c;font-size:13px;white-space:nowrap}
.paper-question .el-radio,.paper-question label{display:block;margin:9px 0;white-space:normal;line-height:1.55}
.paper-question .el-input{max-width:680px;display:block}.required{color:#a52d2d}
.program-item,.paper-result,.paper-controls{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.program-item{justify-content:space-between;padding:10px 0}.paper-result{margin:12px 0}
@media(max-width:710px){.paper-layout{grid-template-columns:1fr;gap:16px}.answer-card{position:static;max-height:none}}
</style>
