<template>
  <section v-if="paper.enabled" class="paper-sheet" aria-label="比赛试卷">
    <header><div><h2>比赛试卷</h2><p v-if="paper.minutes">每人独立计时 {{ paper.minutes }} 分钟</p></div>
      <strong v-if="paper.attempt" aria-live="off">{{ locked ? '答题已结束' : '剩余 ' + countdown }}</strong></header>
    <template v-if="!paper.attempt">
      <p>可开始时间为 {{ stamp(paper.open) }} 至 {{ stamp(paper.lastStart) }}。</p>
      <el-button type="primary" :disabled="busy || clock < paper.open || clock >= paper.lastStart" @click="start">开始比赛</el-button>
    </template>
    <template v-else>
      <p>个人截止时间 {{ stamp(paper.attempt.deadline) }}</p>
      <div v-for="(q,i) in exercises" :key="q.id" :ref="'q-'+q.id" class="paper-question">
        <h3>{{ i+1 }}. {{ q.label }} <small>{{ q.points }} 分</small></h3>
        <el-radio-group v-if="q.type==='choice'" v-model="answers[q.id]" :disabled="locked || busyFinal" @change="schedule">
          <el-radio v-for="(op,k) in q.options" :key="k" :label="letter(k)">{{ letter(k) }}. {{ op }}</el-radio>
        </el-radio-group>
        <el-input v-else v-model="answers[q.id]" maxlength="1000" :disabled="locked || busyFinal" @input="schedule"/>
      </div>
      <div v-if="paper.programs && paper.programs.length" class="paper-question">
        <h3>程序设计</h3>
        <router-link v-for="p in paper.programs" :key="p.id" :to="{name:'ContestProblemDetails',params:{contestID:cid,problemID:p.id}}" target="_blank">{{ p.id }} · {{ p.title }} ↗</router-link>
      </div>
      <div v-if="info.length" class="paper-question"><h3>考生资料</h3>
        <label v-for="q in info" :key="q.id" :ref="'q-'+q.id">{{ q.label }}
          <el-input v-model="answers[q.id]" maxlength="1000" :disabled="locked || busyFinal" @input="schedule"/>
        </label>
      </div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="missing.length" class="required" role="alert">尚未填写 {{ missing.join('、') }}</p>
      <template v-if="!locked"><el-button type="primary" :loading="busyFinal" @click="submit">提交试卷</el-button>
        <el-button :disabled="busy || busyFinal" @click="saveNow">保存草稿</el-button></template>
      <p v-if="paper.closed">客观题 {{ paper.writtenScore }} 分 · 编程题 {{ paper.programScore }} 分 · 合计 {{ paper.writtenScore+paper.programScore }} 分</p>
    </template>
  </section>
</template>
<script>
import edu from '@/common/edu-api'
export default {
  props:{cid:{required:true}},
  data(){return {paper:{},answers:{},clock:Date.now(),offset:0,tick:null,delay:null,queue:Promise.resolve(),notice:'',missing:[],busy:false,busyFinal:false,conflict:false,version:0}},
  computed:{
    exercises(){return (this.paper.questions||[]).filter(q=>q.type!=='info')},
    info(){return (this.paper.questions||[]).filter(q=>q.type==='info')},
    locked(){return this.conflict || this.paper.closed || (this.paper.attempt && this.clock>=Number(this.paper.attempt.deadline))},
    countdown(){const s=Math.max(0,Math.ceil((Number(this.paper.attempt.deadline)-this.clock)/1000));return [Math.floor(s/3600),Math.floor(s/60)%60,s%60].map(x=>String(x).padStart(2,'0')).join(':')}
  },
  watch:{cid:{immediate:true,handler(){this.version++;this.paper={};this.answers={};this.conflict=false;this.notice='';this.missing=[];clearTimeout(this.delay);this.load()}}},
  mounted(){this.tick=setInterval(()=>{this.clock=Date.now()+this.offset;if(this.paper.attempt&&!this.paper.closed&&this.clock>=Number(this.paper.attempt.deadline)&&!this.busy)this.load()},1000)},
  beforeDestroy(){clearInterval(this.tick);clearTimeout(this.delay);if(this.delay && !this.locked && !this.busyFinal)this.enqueue(false)},
  methods:{
    stamp(t){return new Date(Number(t)).toLocaleString('zh-CN',{hour12:false})},letter(k){return String.fromCharCode(65+k)},
    apply(s,restore){this.paper=s;this.offset=Number(s.now||Date.now())-Date.now();this.clock=Date.now()+this.offset;if(restore){const a={};(s.questions||[]).forEach(q=>{a[q.id]=(s.answers||{})[q.id]||''});this.answers=a}},
    async load(){const v=this.version;this.busy=true;try{const s=await edu.paper(this.cid,'','get');if(v===this.version)this.apply(s,true)}catch(e){if(v===this.version)this.notice='读取试卷失败，请刷新重试'}finally{if(v===this.version)this.busy=false}},
    async start(){try{await this.$confirm(this.paper.minutes?'点击确定后立即开始个人计时，刷新或退出都不会暂停。':'确定开始本场比赛？','开始比赛');this.busy=true;this.apply(await edu.paper(this.cid,'/start','post'),true)}catch(e){}finally{this.busy=false}},
    schedule(){this.missing=[];this.notice='正在保存…';clearTimeout(this.delay);this.delay=setTimeout(()=>this.saveNow(),450)},
    saveNow(){clearTimeout(this.delay);this.delay=null;return this.enqueue(false)},
    enqueue(final){const cid=this.cid,v=this.version;const job=async()=>{if(this.conflict||v!==this.version||this.paper.closed)return false;
      try{const data={revision:this.paper.attempt.revision,answers:{...this.answers}};const s=await edu.paper(cid,final?'/submit':'/draft',final?'post':'put',data);if(v===this.version){this.apply(s,false);this.notice=final?'试卷已提交':'草稿已保存'}return true}
      catch(e){if(v===this.version){const err=(e.data || (e.response||{}).data || {});if(err.status===409)this.conflict=true;this.notice=err.msg||'保存失败，请重试'}return false}};
      this.queue=this.queue.then(job,job);return this.queue},
    async submit(){const gaps=this.paper.questions.filter(q=>!(this.answers[q.id]||'').trim());this.missing=gaps.map(q=>q.label);
      if(gaps.length){this.$alert('尚未填写：'+this.missing.join('、'),'请补全试卷');const el=this.$refs['q-'+gaps[0].id];if(el&&el[0])el[0].scrollIntoView({block:'center'});return}
      try{await this.$confirm('交卷后，客观题和编程题都将停止接收作答。确定交卷吗？','提交试卷')}catch(e){return}
      this.busyFinal=true;clearTimeout(this.delay);this.delay=null;try{await this.enqueue(true)}finally{this.busyFinal=false}}
  }
}
</script>
<style scoped>
.paper-sheet{background:white;border:1px solid #dbe6ef;border-radius:16px;padding:clamp(16px,3vw,32px);margin:0 0 16px;color:#243a50}.paper-sheet header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.paper-sheet h2,.paper-sheet h3{margin:0 0 10px}.paper-sheet header strong{background:#e7f2ff;color:#195996;border-radius:9px;padding:9px 14px;font-variant-numeric:tabular-nums}.paper-question{border-top:1px solid #e2e9ee;margin:18px 0;padding-top:18px}.paper-question h3{white-space:pre-wrap;line-height:1.6}.paper-question small{color:#667b8e;font-size:13px;white-space:nowrap}.paper-question .el-radio,.paper-question label,.paper-question a{display:block;margin:9px 0;white-space:normal;line-height:1.6}.paper-question .el-input{max-width:680px;display:block}.required{color:#a52d2d}
</style>
