<template>
  <div class="edu-page" :style="eduPageStyle">
    <header class="page-header">
      <h2 v-if="access.isTeacher">课程总览</h2>
      <nav v-else-if="tracks.length" class="course-tracks" aria-label="选择课程类型">
        <button v-for="track in tracks" :key="track" type="button" :class="{active:activeTrack === track}" :aria-pressed="String(activeTrack === track)" @click="selectTrack(track)">{{ track === 'training' ? '集训课程' : '校本课程' }}</button>
      </nav>
      <h2 v-else>我的课程</h2>
      <el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建课程</el-button>
    </header>
    <section v-if="!access.isTeacher && tracks.length && totalSummary" class="course-total-progress" aria-labelledby="course-total-progress-title">
      <h3 id="course-total-progress-title">{{ activeTrack === 'training' ? '集训课程总进度' : '校本课程总进度' }}</h3>
      <LearningProgress :completed="totalSummary.completedCount" :total="totalSummary.problemCount" />
    </section>
    <el-form class="edu-toolbar" inline @submit.native.prevent="search">
      <el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item>
      <el-form-item v-if="access.isTeacher"><el-select v-model="query.scope" @change="load"><el-option value="library" label="课程库" /><el-option value="mine" label="我创建的" /></el-select></el-form-item>
      <el-form-item v-if="access.isTeacher"><el-select v-model="categoryFilter" clearable placeholder="所有大类"><el-option v-for="category in categories" :key="category.id" :value="category.id" :label="category.title" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
    </el-form>
    <div v-loading="loading">
      <div v-if="access.isTeacher || activeTrack === 'school'" class="edu-grid">
        <LearningCard v-for="row in shownRecords" :key="row.id" :to="courseTarget(row)" :title="row | courseTitle" compact :badge="access.isTeacher && row.status !== 1 ? states[row.status] : ''" :completed="row.completedCount" :total="row.problemCount" :show-progress="true" />
      </div>
      <section v-for="group in groups" v-else :key="group.id" class="course-stage">
        <button type="button" class="stage-toggle" :aria-expanded="String(openStages.includes(group.id))" :aria-controls="'course-stage-' + group.id" @click="toggle(group.id)">
          <span class="stage-name"><i :class="openStages.includes(group.id) ? 'el-icon-arrow-down' : 'el-icon-arrow-right'" aria-hidden="true" />{{ group.title }}</span>
          <span class="stage-progress"><span>{{ group.progress.completedCount }} / {{ group.progress.problemCount }} 题</span><el-progress :percentage="percentage(group.progress)" :show-text="false" :stroke-width="7" /></span>
        </button>
        <div v-show="openStages.includes(group.id)" :id="'course-stage-' + group.id" class="edu-grid stage-cards">
          <LearningCard v-for="row in group.records" :key="row.id" :to="courseTarget(row)" :title="row | courseTitle" compact :completed="row.completedCount" :total="row.problemCount" :show-progress="true" />
        </div>
      </section>
      <p v-if="!loading && !shownRecords.length" class="edu-empty">{{ records.length ? '暂无符合条件的课程' : (access.isTeacher ? '暂无课程' : '暂无已加入的课程') }}</p>
    </div>
    <el-dialog title="创建共享课程" :visible.sync="createDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="课程大类" required><el-select v-model="form.category"><el-option v-for="category in categories" :key="category.id" :value="category.id" :label="category.title" /></el-select></el-form-item>
        <el-form-item label="大类内顺序" required><el-input-number v-model="form.categoryOrder" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" /></el-form-item>
        <el-form-item label="课程显示ID（排序）"><el-input v-model.trim="form.displayId" maxlength="10" placeholder="留空自动填写" aria-label="课程显示ID" /></el-form-item>
        <el-form-item label="章节序号"><el-input-number v-model="form.chapterNumber" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" /></el-form-item>
        <el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :disabled="!form.title" :loading="saving" @click="create">创建草稿</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import LearningCard from './LearningCard.vue'
import LearningProgress from './LearningProgress.vue'
import {loadCourseCatalog} from '@/common/course-catalog'
import {COURSE_CATEGORIES, courseTrack, compareCourses, availableCourseTracks} from '@/common/course-placement'
export default {
  mixins: [eduLayout], components: {LearningCard, LearningProgress},
  data: () => ({access: {}, categories:COURSE_CATEGORIES, categoryFilter:'', query:{keyword:'',scope:'mine'}, keyword:'', records:[], activeTrack:'training', catalogRequest:0, stageProgress:[], courseSummary:null, openStages:[], loading:true, saving:false, createDialog:false, form:{title:'',description:'',displayId:'',chapterNumber:null,category:4,categoryOrder:1}, states:['草稿','已发布','已归档']}),
  async mounted () {
    try {
      this.access = await edu.me()
      this.query.scope = this.access.isTeacher ? 'library' : 'mine'
      await Promise.all([this.load(), ...(this.access.isTeacher ? [] : [this.loadStages(), this.loadCourseSummary()])])
    } catch (_) { this.loading = false }
  },
  computed: {
    tracks () { return availableCourseTracks(this.records) },
    totalSummary () { return this.activeTrack === 'training' ? this.courseSummary : (this.stageProgress.find(row => Number(row.stage) === 4) || {completedCount:0,problemCount:0}) },
    shownRecords () {
      return this.records.filter(row => (this.access.isTeacher || courseTrack(row) === this.activeTrack) && (!this.categoryFilter || !this.access.isTeacher || Number(row.category) === this.categoryFilter) && (!this.keyword || row.title.toLowerCase().includes(this.keyword))).sort(compareCourses)
    },
    groups () {
      if (this.access.isTeacher || this.activeTrack !== 'training') return []
      return this.categories.filter(category => category.id !== 4).map(category => ({...category,records:this.shownRecords.filter(row => Number(row.category) === category.id),progress:this.stageProgress.find(row => Number(row.stage) === category.id) || {completedCount:0,problemCount:0}})).filter(group => group.records.length)
    }
  },
  beforeDestroy () { this.catalogRequest++ },
  methods: {
    courseTarget (row) { return {path:'/course/' + row.id} },
    selectTrack (track) { this.activeTrack = track; this.openStages = []; this.query.keyword = ''; this.keyword = '' },
    async loadStages () { try { this.stageProgress = await edu.courseStages() } catch (_) {} },
    async loadCourseSummary () { try { this.courseSummary = await edu.learningSummary() } catch (_) {} },
    toggle (id) { this.openStages = this.openStages.includes(id) ? this.openStages.filter(stage => stage !== id) : [...this.openStages,id] },
    percentage ({completedCount,problemCount}) { return problemCount ? Math.min(100,Math.round(completedCount * 100 / problemCount)) : 0 },
    async load () {
      const request = ++this.catalogRequest; this.loading = true
      try {
        const records = await loadCourseCatalog(edu.courses,{...this.query,keyword:this.access.isTeacher ? this.query.keyword : ''},() => request !== this.catalogRequest)
        if (request === this.catalogRequest) { this.records = records; if (!this.tracks.includes(this.activeTrack)) this.activeTrack = this.tracks[0] || 'training' }
      } catch (_) { if (request === this.catalogRequest) this.records = [] }
      finally { if (request === this.catalogRequest) this.loading = false }
    },
    search () { if (this.access.isTeacher) this.load(); else this.keyword = this.query.keyword.trim().toLowerCase() },
    async create () {
      if (!this.form.title || this.saving) return
      if (this.form.displayId && (!/^[1-9]\d{0,9}$/.test(this.form.displayId) || Number(this.form.displayId) > 2147483647)) { this.$message.error('显示ID须为1至2147483647的整数'); return }
      this.saving = true
      try { const body = {...this.form}; if (!body.displayId) delete body.displayId; if (!body.chapterNumber) delete body.chapterNumber; const row = await edu.createCourse(body); this.createDialog = false; this.$router.push('/course/' + row.id) } catch (_) {} finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.course-tracks { display:flex; flex-wrap:wrap; gap:8px; padding:5px; border:1px solid #dbe6ef; border-radius:16px; background:#e7eef5; }
.course-tracks button { border:0; border-radius:12px; background:transparent; padding:10px 22px; color:#657b8e; font-size:20px; font-weight:700; cursor:pointer; }
.course-tracks button.active { color:#264b72; background:#fff; box-shadow:0 3px 10px #27435b12; }
.course-tracks button:focus-visible { outline:2px solid #548fce; outline-offset:2px; }
.course-total-progress { margin:0 0 18px; padding:16px 18px; border:1px solid #dce8f0; border-radius:14px; background:#fff; }
.course-total-progress h3 { margin:0 0 10px; color:#263e55; font-size:16px; }
.course-stage { margin:0 0 20px; }
.stage-toggle { display:flex; align-items:center; justify-content:space-between; gap:16px; text-align:left; width:100%; padding:14px 18px; border:1px solid #dce8f0; border-radius:14px; background:#fff; color:#263e55; cursor:pointer; }
.stage-toggle:hover,.stage-toggle:focus-visible { border-color:#7eb7e4; }
.stage-name { font-size:16px; font-weight:650; }.stage-name i { margin-right:8px; }
.stage-progress { width:clamp(90px,30%,290px); color:#47657c; font-size:12px; }.stage-progress span { display:block; text-align:right; margin-bottom:5px; }
.stage-cards { margin:14px 0 24px; }
@media(max-width:530px) { .course-tracks button { font-size:18px; padding:9px 15px; }.stage-toggle { gap:10px; padding:13px 12px; }.stage-name { font-size:14px; }.stage-progress { min-width:85px; } }
</style>
