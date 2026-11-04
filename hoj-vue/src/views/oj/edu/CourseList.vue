<template>
  <div class="edu-page" :style="eduPageStyle">
    <LearningSummary v-if="access.isTeacher === false" :summary="summary" :loading="summaryLoading" :failed="summaryFailed" @retry="loadSummary" />
    <header class="page-header"><h2>{{ access.isTeacher ? '课程总览' : '我的课程' }}</h2><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建课程</el-button></header>
    <el-form class="edu-toolbar" inline @submit.native.prevent="search">
      <el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item>
      <el-form-item v-if="access.isTeacher"><el-select v-model="query.scope" @change="search"><el-option value="library" label="课程库" /><el-option value="mine" label="我创建的" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
    </el-form>
    <div class="edu-grid" v-loading="loading">
      <LearningCard v-for="row in records" :key="row.id" :to="courseTarget(row)" :title="row | courseTitle" :description="row.description" :icon="row.iconUrl" :owner="row.ownerUsername" :owner-uid="row.ownerUid" :badge="access.isTeacher && row.status !== 1 ? states[row.status] : ''" :meta="row.problemCount + ' 道公开题'" :completed="row.completedCount" :total="row.problemCount" :restricted="!access.isTeacher && !row.canAccess" :show-progress="!!(access.isTeacher || row.canAccess)" @restricted-click="$message.info('加入相关班级后可学习本课程')" />
      <p v-if="!loading && !records.length" class="edu-empty">{{ access.isTeacher ? '暂无符合条件的课程' : '暂无已加入的课程，请联系老师开通课程' }}</p>
    </div>
    <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
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
import LearningSummary from './LearningSummary.vue'
export default {
  mixins: [eduLayout], components: {LearningCard, LearningSummary},
  data: () => ({access: {}, query: {page: 1, limit: 12, keyword: '', scope: 'mine'}, records: [], total: 0, summary: null, summaryLoading: true, summaryFailed: false, loading: true, saving: false, createDialog: false, form: {title: '', description: '', displayId: '', chapterNumber: null}, states: ['草稿', '已发布', '已归档']}),
  async mounted () { try { this.access = await edu.me(); this.query.scope = this.access.isTeacher ? 'library' : 'mine'; await Promise.all([this.load(), this.access.isTeacher ? Promise.resolve() : this.loadSummary()]) } catch (_) { this.loading = false } },
  methods: {
    courseTarget (row) { return {path:'/course/' + row.id} },
    async loadSummary () { this.summaryLoading = true; this.summaryFailed = false; try { this.summary = await edu.learningSummary() } catch (_) { this.summaryFailed = true } finally { this.summaryLoading = false } },
    async load () { this.loading = true; try { const data = await edu.courses(this.query); this.records = data.records; this.total = data.total } catch (_) { this.records = []; this.total = 0 } finally { this.loading = false } },
    search () { this.query.page = 1; this.load() },
    async create () {
      if (!this.form.title || this.saving) return
      if (this.form.displayId && (!/^[1-9]\d{0,9}$/.test(this.form.displayId) || Number(this.form.displayId) > 2147483647)) { this.$message.error('显示ID须为1至2147483647的整数'); return }
      this.saving = true
      try { const body = {...this.form}; if (!body.displayId) delete body.displayId; if (!body.chapterNumber) delete body.chapterNumber; const row = await edu.createCourse(body); this.createDialog = false; this.$router.push('/course/' + row.id) } catch (_) {} finally { this.saving = false }
    }
  }
}
</script>
