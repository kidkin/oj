<template>
  <div class="classes-page" :style="eduPageStyle">
    <el-card>
      <div slot="header" class="page-header"><h2>我的班级</h2><div><el-button v-if="access.isRoot" @click="teacherDialog = true">教师授权</el-button><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建班级</el-button></div></div>
      <el-form inline @submit.native.prevent="search">
        <el-form-item><el-input v-model="query.keyword" maxlength="128" clearable placeholder="搜索班级名称" @clear="search" /></el-form-item>
        <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="records" empty-text="暂无可访问的班级" @row-click="open">
        <el-table-column label="班级名称" min-width="180"><template slot-scope="scope"><router-link :to="'/class/' + scope.row.id">{{ scope.row.name }}</router-link></template></el-table-column>
        <el-table-column prop="ownerUsername" label="创建者" min-width="100" />
        <el-table-column prop="studentCount" label="学生人数" width="100" />
        <el-table-column label="状态" width="100"><template slot-scope="scope"><el-tag :type="scope.row.status === 0 ? 'success' : 'info'">{{ scope.row.status === 0 ? '正常' : '已归档' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="110"><template slot-scope="scope"><el-button type="text" @click.stop="open(scope.row)">{{ scope.row.canManage ? '管理班级' : '进入班级' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
    </el-card>
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
export default {
  mixins: [eduLayout],
  components: {TeacherAdmin},
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
.classes-page { max-width: 1180px; margin: 24px auto; padding: 0 12px; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 22px; }.pagination { margin-top: 20px; text-align: right; }
</style>
