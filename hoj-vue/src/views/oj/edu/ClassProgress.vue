<template>
  <div class="edu-page progress-page" :style="eduPageStyle" v-loading="loading">
    <router-link :to="'/class/' + id">← 返回班级</router-link>
    <template v-if="detail && detail.canManage">
      <header class="page-header progress-heading"><h2>{{ detail.name }} · 学习情况</h2><el-tag>班级 ID {{ detail.displayId }}</el-tag></header>
      <p class="hint">选择题目后，为本班全部学生生成表格。按历史最佳结果统计：正确优先于部分正确，部分正确优先于错误；比赛和原团队提交不计入。</p>
      <section class="problem-selector">
        <div class="page-header"><h3>1. 选择题目</h3><span class="hint">已选 {{ selected.length }}/50 题，按选择顺序排列</span></div>
        <el-form inline class="progress-filters" @submit.native.prevent="search">
          <el-form-item><el-select v-model="query.courseId" placeholder="全部公开题库" clearable :disabled="generating" @change="search"><el-option v-for="course in courses" :key="course.id" :label="course.displayId + ' · ' + course.title" :value="course.id" /></el-select></el-form-item>
          <el-form-item><el-input v-model.trim="query.keyword" maxlength="128" placeholder="搜索原题号或题目名称" clearable :disabled="generating" @clear="search" /></el-form-item>
          <el-form-item><el-button native-type="submit" :disabled="generating">搜索</el-button><el-button :disabled="generating || !records.length || selected.length >= 50" @click="addPage">选择本页</el-button></el-form-item>
        </el-form>
        <vxe-table border="inner" stripe auto-resize align="center" :data="records" :loading="pickerLoading" empty-text="没有符合条件的公开题目">
          <vxe-table-column field="problemId" title="原题号" min-width="150" show-overflow /><vxe-table-column field="title" title="题目名称" min-width="200" show-overflow />
          <vxe-table-column title="操作" width="110"><template v-slot="{row}"><el-button type="text" :disabled="generating || selectedIds.includes(row.id) || selected.length >= 50" @click="add(row)">{{ selectedIds.includes(row.id) ? '已选择' : '选择' }}</el-button></template></vxe-table-column>
        </vxe-table>
        <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="10" :total="total" :disabled="generating" @current-change="loadPicker" />
        <div v-if="selected.length" class="selected-problems">
          <div v-for="(problem,index) in selected" :key="problem.id" class="selected-problem"><span>{{ index+1 }}. {{ problem.title }} <small>（{{ problem.problemId }}）</small></span><div><el-button type="text" :disabled="generating || index === 0" @click="move(index,-1)">上移</el-button><el-button type="text" :disabled="generating || index === selected.length-1" @click="move(index,1)">下移</el-button><el-button type="text" :disabled="generating" @click="selected.splice(index,1)">取消选择</el-button></div></div>
        </div>
        <div class="generate-actions"><el-button type="primary" :disabled="!selected.length" :loading="generating" @click="generate">生成全班进度表</el-button><el-button v-if="selected.length" :disabled="generating" @click="selected = []">清空选择</el-button></div>
      </section>
      <section v-if="report" class="report-section">
        <div class="page-header"><h3>2. 查看并复制图片</h3><div class="image-actions"><el-button :disabled="generating || dirty" @click="generate">刷新结果</el-button><el-button type="primary" :disabled="dirty || !image || rendering || generating" :loading="copying" @click="copyImage">{{ pages.length > 1 ? '复制本页图片' : '复制图片' }}</el-button><el-button :disabled="dirty || !image || rendering || generating" @click="downloadImage">下载PNG</el-button></div></div>
        <el-alert v-if="dirty" title="题目选择已改变，请重新生成表格后再复制图片。" type="warning" :closable="false" show-icon />
        <el-alert v-if="report.pendingCells" title="有提交正在判题：先显示已有的最佳结果，暂无结果的标为“判题中”。请稍后刷新。" type="info" :closable="false" show-icon />
        <p class="hint">共 {{ report.studentCount }} 名学生、{{ report.problemCount }} 道题 · 生成于 {{ formatTime(report.generatedAt) }}<template v-if="pages.length > 1"> · 大表分为 {{ pages.length }} 张图片，每张至多80名学生、12道题，所有学生均已包含。</template></p>
        <div class="status-legend"><span v-for="key in finalStates" :key="key" :style="badgeStyle(key)">{{ states[key].label }}</span></div>
        <div v-if="pages.length > 1" class="image-pages"><el-button :disabled="pageIndex === 0" @click="pageIndex--">上一张</el-button><span>第 {{ pageIndex+1 }}/{{ pages.length }} 张 · 学生 {{ page.rowStart + (page.rowEnd > page.rowStart ? 1 : 0) }}—{{ page.rowEnd }} · 题目 {{ page.colStart+1 }}—{{ page.colEnd }}</span><el-button :disabled="pageIndex === pages.length-1" @click="pageIndex++">下一张</el-button></div>
        <div class="report-preview" v-loading="rendering">
          <table class="progress-table"><caption>{{ report.className }} · 学习情况</caption><thead><tr><th class="student-name">姓名/题目</th><th v-for="problem in visibleProblems" :key="problem.id"><span>{{ problem.title }}</span><small>{{ problem.problemId }}</small></th></tr></thead><tbody><tr v-for="student in visibleStudents" :key="student.uid"><th class="student-name" :title="student.username">{{ student.name }}</th><td v-for="(problem,index) in visibleProblems" :key="problem.id"><span class="result-badge" :style="badgeStyle(student.results[page.colStart+index])">{{ stateLabel(student.results[page.colStart+index]) }}</span></td></tr><tr v-if="!visibleStudents.length"><td :colspan="visibleProblems.length+1">本班暂无有效学生</td></tr></tbody></table>
        </div>
        <p v-if="!clipboardSupported" class="hint">当前浏览器或访问地址不支持复制图片，可先下载PNG；复制图片需使用本机地址或支持剪贴板的HTTPS页面。</p>
      </section>
    </template>
    <p v-else-if="!loading" class="edu-empty">仅本班管理教师和超级管理员可查看此表。</p>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import {PROGRESS_STATES, progressPages, progressTime, renderProgressImage} from '@/common/progress-image'
export default {
  mixins: [eduLayout],
  data: () => ({detail:null, loading:false, courses:[], records:[], total:0, pickerLoading:false, query:{page:1,keyword:'',courseId:null}, selected:[], generating:false, report:null, pageIndex:0, image:null, rendering:false, copying:false, states:PROGRESS_STATES, finalStates:['CORRECT','WRONG','NOT_SUBMITTED','PARTIAL'], pickerVersion:0, renderVersion:0, loadVersion:0, reportVersion:0, clipboardSupported:!!(window.isSecureContext && navigator.clipboard && navigator.clipboard.write && window.ClipboardItem)}),
  computed: {
    id () { return this.$route.params.classId },
    selectedIds () { return this.selected.map(problem => problem.id) },
    pages () { return progressPages(this.report) },
    page () { return this.pages[this.pageIndex] || {rowStart:0,rowEnd:0,colStart:0,colEnd:0} },
    visibleStudents () { return this.report ? this.report.students.slice(this.page.rowStart,this.page.rowEnd) : [] },
    visibleProblems () { return this.report ? this.report.problems.slice(this.page.colStart,this.page.colEnd) : [] },
    dirty () { return !!this.report && this.selectedIds.join(',') !== this.report.problems.map(problem => problem.id).join(',') }
  },
  watch: {id () { this.load() }, pageIndex () { this.prepareImage() }},
  mounted () { this.load() },
  beforeDestroy () { this.renderVersion++; this.pickerVersion++; this.loadVersion++; this.reportVersion++ },
  methods: {
    formatTime:progressTime,
    stateLabel (value) { return (this.states[value] || this.states.PENDING).label },
    badgeStyle (value) { const state=this.states[value] || this.states.PENDING; return {backgroundColor:state.background,color:state.color} },
    async load () {
      const version=++this.loadVersion; this.loading=true; this.detail=null; this.report=null; this.image=null; this.selected=[]; this.courses=[]; this.records=[]; this.total=0; this.pageIndex=0; this.generating=false; this.rendering=false; this.query={page:1,keyword:'',courseId:null}; this.renderVersion++; this.pickerVersion++; this.reportVersion++
      try {
        const detail=await edu.detail(this.id); if (version!==this.loadVersion) return
        if (!detail.canManage) return; this.detail=detail
        await Promise.all([this.loadPicker(),this.loadCourses(version)])
      } catch (_) {} finally { if (version===this.loadVersion) this.loading=false }
    },
    async loadCourses (version) {
      const classId=this.id; const all=[]; let page=1
      while (true) { const result=await edu.classCourses(classId,{page,limit:50}); if (classId!==this.id || version!==this.loadVersion) return; all.push(...result.records); if (all.length>=result.total || !result.records.length) break; page++ }
      this.courses=all
    },
    search () { this.query.page=1; this.loadPicker() },
    async loadPicker () {
      const version=++this.pickerVersion; this.pickerLoading=true
      try { const result=await edu.progressProblems(this.id,{...this.query,courseId:this.query.courseId || undefined,limit:10}); if (version===this.pickerVersion) { this.records=result.records; this.total=result.total } } catch (_) { if (version===this.pickerVersion) { this.records=[]; this.total=0 } } finally { if (version===this.pickerVersion) this.pickerLoading=false }
    },
    add (problem) { if (this.selected.length<50 && !this.selectedIds.includes(problem.id)) this.selected.push({...problem}) },
    addPage () { this.records.forEach(this.add) },
    move (index,delta) { const next=index+delta; if (next<0 || next>=this.selected.length) return; const value=this.selected.splice(index,1)[0]; this.selected.splice(next,0,value) },
    async generate () {
      if (!this.selected.length || this.generating) return
      const classId=this.id; const version=++this.reportVersion; this.generating=true; this.image=null; this.renderVersion++
      try { const report=await edu.classProgress(classId,this.selectedIds); if (version!==this.reportVersion) return; this.report=report; this.pageIndex=0; await this.prepareImage() } catch (_) { if (version===this.reportVersion) this.report=null } finally { if (version===this.reportVersion) this.generating=false }
    },
    async prepareImage () {
      const version=++this.renderVersion; this.image=null; if (!this.report || !this.pages.length) return; this.rendering=true
      try { const image=await renderProgressImage(this.report,this.page,this.pageIndex,this.pages.length); if (version===this.renderVersion) this.image=image } catch (error) { if (version===this.renderVersion) this.$message.error(error.message) } finally { if (version===this.renderVersion) this.rendering=false }
    },
    async copyImage () {
      if (!this.image || this.dirty) return
      if (!this.clipboardSupported) { this.$message.warning('此浏览器或访问地址不支持复制图片，请使用下载PNG'); return }
      this.copying=true
      try { await navigator.clipboard.write([new window.ClipboardItem({'image/png':this.image.blob})]); this.$message.success('图片已复制，可以直接粘贴') } catch (_) { this.$message.error('复制失败，请允许浏览器使用剪贴板后重试，或下载PNG') } finally { this.copying=false }
    },
    downloadImage () {
      if (!this.image || this.dirty) return
      const url=URL.createObjectURL(this.image.blob); const link=document.createElement('a'); link.href=url; link.download='班级学习情况-'+this.report.classDisplayId+'-'+(this.pageIndex+1)+'.png'; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000)
    }
  }
}
</script>
<style scoped>
.progress-heading { margin-top:22px; }.hint { color:#606266; line-height:1.8; }h3 { margin:0; }.problem-selector { margin-top:24px; }.progress-filters { margin:16px 0; }.pagination { text-align:right; margin:16px 0; }.selected-problems { max-height:280px; overflow:auto; border:1px solid #dce5ee; background:#fff; padding:6px 14px; margin:16px 0; border-radius:6px; }.selected-problem { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:6px 0; border-bottom:1px solid #edf1f5; }.selected-problem:last-child { border:0; }.selected-problem>span { min-width:0; overflow-wrap:anywhere; }.selected-problem>div { flex-shrink:0; }.selected-problem small { color:#909399; }.generate-actions { margin:20px 0; }.report-section { margin-top:34px; padding-top:24px; border-top:1px solid #dce5ee; }.status-legend { display:flex; flex-wrap:wrap; gap:12px; margin:16px 0; }.status-legend span,.result-badge { display:inline-block; padding:4px 12px; font-size:18px; line-height:26px; border-radius:3px; white-space:nowrap; }.image-actions { display:flex; flex-wrap:wrap; gap:10px; }.image-actions .el-button { margin:0; }.image-pages { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin:16px 0; }.report-preview { max-width:100%; overflow:auto; background:#fff; margin-top:16px; }.progress-table { width:max-content; min-width:100%; border-collapse:collapse; color:#202020; background:#fff; table-layout:fixed; }.progress-table caption { padding:20px 12px; font-size:22px; font-weight:600; text-align:left; }.progress-table th,.progress-table td { border:2px solid #202020; text-align:center; padding:8px 12px; min-width:176px; max-width:240px; height:32px; overflow-wrap:anywhere; }.progress-table thead th { font-size:20px; font-weight:500; line-height:1.5; padding:14px 12px; }.progress-table thead small { display:block; font-size:12px; color:#606266; }.progress-table .student-name { min-width:196px; font-size:20px; font-weight:500; }.progress-page /deep/ .el-alert { margin:10px 0; }
@media (max-width:700px) { .selected-problem { flex-wrap:wrap; }.image-actions { width:100%; }.status-legend { gap:8px; }.status-legend span { font-size:15px; } }
</style>
