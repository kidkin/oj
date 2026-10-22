<template>
  <div class="edu-page" :style="eduPageStyle">
      <header class="page-header"><h2>{{ access.isRoot ? '班级管理' : '我的班级' }}</h2><div><el-button v-if="access.isRoot" @click="teacherDialog = true">教师授权</el-button><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建班级</el-button></div></header>
      <el-form inline @submit.native.prevent="search">
        <el-form-item><el-input v-model="query.keyword" maxlength="128" clearable placeholder="搜索班级名称" @clear="search" /></el-form-item>
        <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
      </el-form>
      <p v-if="!access.isTeacher" class="student-hint">这里只显示你已加入的班级。完成情况按本班课程中的公开题目去重统计。</p>
      <div class="edu-grid" v-loading="loading">
        <LearningCard v-for="row in records" :key="row.id" kind="class" :to="'/class/' + row.id" :title="row.name" :description="row.description" :owner="row.ownerUsername" :badge="row.status !== 0 ? '已归档' : (row.canManage ? '管理班级' : '已加入')" :meta="row.courseCount + ' 门课程 · ' + row.studentCount + ' 位同学'" :completed="row.completedCount" :total="row.problemCount" />
        <p v-if="!loading && !records.length" class="empty-state">还没有加入班级，请联系教师将你的账号加入班级。</p>
      </div>
      <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
    <el-dialog title="创建班级" :visible.sync="createDialog" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top" @submit.native.prevent="create">
        <el-form-item label="班级名称" required><el-input v-model.trim="form.name" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="班级介绍"><el-input v-model="form.description" type="textarea" :rows="5" maxlength="10000" show-word-limit /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :disabled="!form.name" :loading="saving" @click="create">创建</el-button></span>
    </el-dialog>
    <el-dialog title="教师授权" :visible.sync="teacherDialog" width="min(820px, 94vw)"><TeacherAdmin v-if="teacherDialog" /></el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import TeacherAdmin from './TeacherAdmin.vue'
import eduLayout from '@/common/edu-layout'
import LearningCard from './LearningCard.vue'
export default {
  mixins: [eduLayout],
  components: {TeacherAdmin, LearningCard},
  data: () => ({access: {}, query: {page: 1, limit: 10, keyword: ''}, records: [], total: 0, loading: false, saving: false, createDialog: false, teacherDialog: false, form: {name: '', description: ''}}),
  async mounted () { try { this.access = await edu.me() } catch (_) {} await this.load() },
  methods: {
    async load () { this.loading = true; try { const data = await edu.classes(this.query); this.records = data.records; this.total = data.total } catch (_) { this.records = [] } finally { this.loading = false } },
    search () { this.query.page = 1; this.load() },
    open (row) { this.$router.push('/class/' + row.id) },
    async create () {
      if (!this.form.name || this.saving) return
      this.saving = true
      try { const data = await edu.create(this.form); this.createDialog = false; this.$message.success('班级已创建'); this.open(data) } catch (_) {} finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 22px; }.pagination { margin-top: 20px; text-align: right; }
.student-hint { margin: 0 0 20px; color: #6b7d8d; line-height: 1.8; }.empty-state { grid-column: 1 / -1; text-align: center; padding: 48px 12px; color: #8493a0; }
</style>
