<template>
  <div class="edu-page" :style="eduPageStyle">
    <header class="page-header"><h2>课程总览</h2><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建课程</el-button></header>
    <el-form class="edu-toolbar" inline @submit.native.prevent="search">
      <el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item>
      <el-form-item v-if="access.isTeacher"><el-select v-model="query.scope" @change="search"><el-option value="library" label="课程库" /><el-option value="mine" label="我创建的" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
    </el-form>
    <div class="edu-grid" v-loading="loading">
      <LearningCard v-for="row in records" :key="row.id" :display-id="row.displayId" :to="courseTarget(row)" :title="row.title" :description="row.description" :icon="row.iconUrl" :owner="row.ownerUsername" :badge="access.isTeacher && row.status !== 1 ? states[row.status] : ''" :meta="row.problemCount + ' 道公开题'" :completed="row.completedCount" :total="row.problemCount" :restricted="!access.isTeacher && !row.canAccess" :show-progress="!!(access.isTeacher || row.canAccess)" @restricted-click="$message.info('加入相关班级后可学习本课程')" />
      <p v-if="!loading && !records.length" class="edu-empty">暂无符合条件的课程</p>
    </div>
    <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
    <el-dialog title="创建共享课程" :visible.sync="createDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item><el-form-item label="课程显示ID（排序）"><el-input v-model.trim="form.displayId" maxlength="10" placeholder="留空自动填写" aria-label="课程显示ID" /></el-form-item><el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item></el-form>

      <span slot="footer"><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :disabled="!form.title" :loading="saving" @click="create">创建草稿</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import LearningCard from './LearningCard.vue'
export default {
  mixins: [eduLayout], components: {LearningCard},
  data: () => ({access: {}, query: {page: 1, limit: 12, keyword: '', scope: 'catalog'}, records: [], total: 0, loading: true, saving: false, createDialog: false, form: {title: '', description: '', displayId: ''}, states: ['草稿', '已发布', '已归档']}),
  async mounted () { try { this.access = await edu.me(); this.query.scope = this.access.isTeacher ? 'library' : 'catalog'; await this.load() } catch (_) { this.loading = false } },
  methods: {
    courseTarget (row) { const target={path:'/course/' + row.id}; if (!this.access.isTeacher && row.sourceClasses && row.sourceClasses.length) target.query={classId:String(row.sourceClasses[0].id)}; return target },
    async load () { this.loading = true; try { const data = await edu.courses(this.query); this.records = data.records; this.total = data.total } catch (_) { this.records = []; this.total = 0 } finally { this.loading = false } },
    search () { this.query.page = 1; this.load() },
    async create () {
      if (!this.form.title || this.saving) return
      if (this.form.displayId && (!/^[1-9]\d{0,9}$/.test(this.form.displayId) || Number(this.form.displayId) > 2147483647)) { this.$message.error('显示ID须为1至2147483647的整数'); return }
      this.saving = true
      try { const body = {...this.form}; if (!body.displayId) delete body.displayId; const row = await edu.createCourse(body); this.createDialog = false; this.$router.push('/course/' + row.id) } catch (_) {} finally { this.saving = false }
    }
  }
}
</script>
