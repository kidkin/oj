<template>
  <div class="edu-page" :style="eduPageStyle">
    <header class="page-header"><h2>{{ access.isTeacher ? '课程总览' : '我的课程' }}</h2><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建课程</el-button></header>
    <el-form class="edu-toolbar" inline @submit.native.prevent="search">
      <el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item>
      <el-form-item v-if="access.isTeacher"><el-select v-model="query.scope" @change="search"><el-option value="library" label="课程库" /><el-option value="mine" label="我创建的" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
    </el-form>
    <div v-loading="loading">
      <div v-if="access.isTeacher" class="edu-grid">
      <LearningCard v-for="row in records" :key="row.id" :to="courseTarget(row)" :title="row | courseTitle" compact :icon="row.iconUrl" :owner="row.ownerUsername" :owner-uid="row.ownerUid" :badge="access.isTeacher && row.status !== 1 ? states[row.status] : ''" :meta="row.problemCount + ' 道公开题'" :completed="row.completedCount" :total="row.problemCount" :restricted="!access.isTeacher && !row.canAccess" :show-progress="!!(access.isTeacher || row.canAccess)" @restricted-click="$message.info('加入相关班级后可学习本课程')" />
      </div>
      <section v-for="group in groups" v-else :key="group.id" class="course-stage">
        <button type="button" class="stage-toggle" :aria-expanded="String(openStages.includes(group.id))" :aria-controls="'course-stage-' + group.id" @click="toggle(group.id)">
          <span class="stage-name"><i :class="openStages.includes(group.id) ? 'el-icon-arrow-down' : 'el-icon-arrow-right'" aria-hidden="true" />{{ group.title }}</span>
          <span class="stage-progress"><span>{{ group.progress.completedCount }} / {{ group.progress.problemCount }} 题</span><el-progress :percentage="percentage(group.progress)" :show-text="false" :stroke-width="7" /></span>
        </button>
        <div v-show="openStages.includes(group.id)" :id="'course-stage-' + group.id" class="edu-grid stage-cards">
      <LearningCard v-for="row in group.records" :key="row.id" :to="courseTarget(row)" :title="row | courseTitle" compact :icon="row.iconUrl" :owner="row.ownerUsername" :owner-uid="row.ownerUid" :badge="access.isTeacher && row.status !== 1 ? states[row.status] : ''" :meta="row.problemCount + ' 道公开题'" :completed="row.completedCount" :total="row.problemCount" :restricted="!access.isTeacher && !row.canAccess" :show-progress="!!(access.isTeacher || row.canAccess)" @restricted-click="$message.info('加入相关班级后可学习本课程')" />
        </div>
      </section>
      <p v-if="!loading && !records.length" class="edu-empty">{{ access.isTeacher ? '暂无符合条件的课程' : '暂无已加入的课程，请联系老师开通课程' }}</p>
    </div>
    <el-dialog title="创建共享课程" :visible.sync="createDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item><el-form-item label="课程显示ID（排序）"><el-input v-model.trim="form.displayId" maxlength="10" placeholder="留空自动填写" aria-label="课程显示ID" /></el-form-item><el-form-item label="章节序号"><el-input-number v-model="form.chapterNumber" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" /></el-form-item><el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item></el-form>

      <span slot="footer"><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :disabled="!form.title" :loading="saving" @click="create">创建草稿</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import LearningCard from './LearningCard.vue'
import {loadCourseCatalog} from '@/common/course-catalog'
export default {
  mixins: [eduLayout], components: {LearningCard},
  data: () => ({access: {}, query: {keyword: '', scope: 'mine'}, records: [], catalogRequest: 0, stageProgress: [], openStages: [1, 2, 3, 4], loading: true, saving: false, createDialog: false, form: {title: '', description: '', displayId: '', chapterNumber: null}, states: ['草稿', '已发布', '已归档']}),
  async mounted () { try { this.access = await edu.me(); this.query.scope = this.access.isTeacher ? 'library' : 'mine'; await Promise.all([this.load(), this.access.isTeacher ? Promise.resolve() : this.loadStages()]) } catch (_) { this.loading = false } },
  computed: {
    groups () {
      if (this.access.isTeacher) return []
      const names = ['流程控制与循环迭代', '过程抽象与泛型编程', '算法设计与分析', '其他课程']
      return names.map((title, index) => {
        const id = index + 1
        const records = this.records.filter(row => (row.id <= 8 ? 1 : row.id <= 16 ? 2 : row.id <= 26 ? 3 : 4) === id)
        return {id, title, records, progress: this.stageProgress.find(row => row.stage === id) || {completedCount: 0, problemCount: 0}}
      }).filter(group => group.records.length)
    }
  },
  beforeDestroy () { this.catalogRequest++ },
  methods: {
    courseTarget (row) { return {path:'/course/' + row.id} },
    async loadStages () { try { this.stageProgress = await edu.courseStages() } catch (_) {} },
    toggle (id) { this.openStages = this.openStages.includes(id) ? this.openStages.filter(stage => stage !== id) : [...this.openStages, id] },
    percentage ({completedCount, problemCount}) { return problemCount ? Math.min(100, Math.round(completedCount * 100 / problemCount)) : 0 },
    async load () {
      const request = ++this.catalogRequest; this.loading = true
      try {
        const records = await loadCourseCatalog(edu.courses, {...this.query}, () => request !== this.catalogRequest)
        if (request === this.catalogRequest) this.records = records
      } catch (_) { if (request === this.catalogRequest) this.records = [] }
      finally { if (request === this.catalogRequest) this.loading = false }
    },
    search () { this.load() },
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
.course-stage { margin: 0 0 20px; }.stage-toggle { display:flex; align-items:center; justify-content:space-between; gap:16px; text-align:left; width:100%; padding:14px 18px; border:1px solid #dce8f0; border-radius:14px; background:#fff; color:#263e55; cursor:pointer; }
.stage-toggle:hover,.stage-toggle:focus-visible { border-color:#7eb7e4; }.stage-name { font-size:16px; font-weight:650; }.stage-name i { margin-right:8px; }.stage-progress { width:clamp(90px, 30%, 290px); color:#47657c; font-size:12px; }.stage-progress span { display:block; text-align:right; margin-bottom:5px; }.stage-cards { margin:14px 0 24px; }
</style>
