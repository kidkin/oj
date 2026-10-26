<template>
  <div class="edu-page" :style="eduPageStyle" v-loading="loading">
    <div class="class-toolbar"><div class="edu-breadcrumb"><router-link to="/class">我的班级</router-link><template v-if="detail"><span>/</span><strong>{{ detail.name }}</strong></template></div><div v-if="detail && detail.canManage" class="class-actions"><el-button type="primary" @click="$router.push('/class/' + id + '/progress')">学习情况表</el-button><el-button @click="edit">编辑班级</el-button></div></div>
    <section v-if="detail" class="class-content">
      <div class="class-about"><p class="description">{{ detail.description || '暂无班级介绍' }}</p></div>
      <el-alert v-if="detail.status !== 0" title="班级已归档" type="info" :closable="false" />
      <div class="class-learning-progress"><LearningProgress :completed="detail.completedCount" :total="detail.problemCount" /></div>
      <ClassCourses :class-id="id" :can-manage="detail.canManage" :archived="detail.status !== 0" />
      <template v-if="detail.canManage">
        <el-divider />
        <div class="page-header"><h3>班级成员</h3><el-button type="primary" :disabled="detail.status !== 0" @click="memberDialog = true">添加成员</el-button></div>
        <el-table :data="members" empty-text="暂无成员" v-loading="membersLoading">
          <el-table-column prop="username" label="用户名" min-width="110" />
          <el-table-column prop="realname" label="姓名" min-width="100" />
          <el-table-column prop="number" label="学号" min-width="100" />
          <el-table-column label="身份" width="100"><template slot-scope="scope">{{ scope.row.role === 'TEACHER' ? '教师' : '学生' }}</template></el-table-column>
          <el-table-column label="操作" width="110"><template slot-scope="scope"><el-button type="text" :disabled="saving || detail.status !== 0 || scope.row.uid === detail.ownerUid" @click="remove(scope.row)">移出班级</el-button></template></el-table-column>
        </el-table>
        <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="memberPage" :page-size="20" :total="memberTotal" @current-change="loadMembers" />
      </template>
    </section>
    <p v-else-if="!loading" class="edu-empty">无法访问该班级，请检查成员身份或返回班级列表。</p>
    <el-dialog title="编辑班级" :visible.sync="editDialog" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="班级名称" required><el-input v-model.trim="form.name" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="班级显示ID（排序）" required><el-input-number v-model="form.displayId" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" /></el-form-item>
        <el-form-item label="班级介绍"><el-input v-model="form.description" type="textarea" :rows="5" maxlength="10000" show-word-limit /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option :value="0" label="正常" /><el-option :value="1" label="归档" /></el-select></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="editDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.name" @click="save">保存</el-button></span>
    </el-dialog>
    <el-dialog title="添加已有成员" :visible.sync="memberDialog" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="成员身份"><el-select v-model="memberForm.role"><el-option label="学生" value="STUDENT" /><el-option label="教师" value="TEACHER" /></el-select></el-form-item>
        <el-form-item label="已有账号的用户名" required><el-input type="textarea" v-model="memberForm.names" :rows="7" placeholder="每行一个用户名，也可用逗号分隔；每次最多100个" /></el-form-item>

      </el-form>
      <span slot="footer"><el-button @click="memberDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!memberForm.names.trim()" @click="addMembers">添加</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import ClassCourses from './ClassCourses.vue'
import LearningProgress from './LearningProgress.vue'
export default {
  mixins: [eduLayout],
  components: {ClassCourses, LearningProgress},
  data: () => ({detail: null, loading: false, membersLoading: false, saving: false, members: [], memberTotal: 0, memberPage: 1, editDialog: false, memberDialog: false, form: {}, memberForm: {role: 'STUDENT', names: ''}}),
  computed: {id () { return this.$route.params.classId }},
  watch: {id () { this.memberPage = 1; this.load() }},
  mounted () { this.load() },
  methods: {
    async load () {
      this.loading = true; this.detail = null; this.members = []
      try { this.detail = await edu.detail(this.id); if (this.detail.canManage) await this.loadMembers() } catch (_) {} finally { this.loading = false }
    },
    async loadMembers () { this.membersLoading = true; try { const data = await edu.members(this.id, {page: this.memberPage, limit: 20}); this.members = data.records; this.memberTotal = data.total } catch (_) { this.members = [] } finally { this.membersLoading = false } },
    edit () { this.form = {name: this.detail.name, description: this.detail.description, status: this.detail.status, displayId: this.detail.displayId}; this.editDialog = true },
    async save () { if (!Number.isInteger(this.form.displayId) || this.form.displayId < 1) { this.$message.error('请填写班级显示ID'); return }; this.saving = true; try { await edu.update(this.id, this.form); this.editDialog = false; this.$message.success('班级已保存'); await this.load() } catch (_) {} finally { this.saving = false } },
    async addMembers () {
      const usernames = this.memberForm.names.split(/[\r\n,，]+/).map(s => s.trim()).filter(Boolean)
      if (!usernames.length || usernames.length > 100) { this.$message.error('每次填写1至100个已有账号'); return }
      this.saving = true
      try { await edu.addMembers(this.id, {role: this.memberForm.role, usernames}); this.memberDialog = false; this.memberForm.names = ''; this.memberPage = 1; this.$message.success('成员已保存'); await this.load() } catch (_) {} finally { this.saving = false }
    },
    async remove (row) { this.saving = true; try { await edu.removeMember(this.id, row.uid); this.$message.success('成员已移出班级'); await this.load() } catch (_) {} finally { this.saving = false } }
  }
}
</script>
<style scoped>
.class-content { margin-top: 22px; }
.class-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; }.class-actions { display:flex; gap:12px; }.class-actions .el-button { margin:0; }.class-about { background:#fff; border-left:4px solid #6aa3d4; border-radius:0 10px 10px 0; padding:20px 24px; }.class-about .description { margin:0; }.class-learning-progress { width:92%; margin:28px auto 36px; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 22px; }h3 { margin: 0 0 12px; }.edit-button { margin-left: 12px; }
.meta { color: #606266; line-height: 1.8; }.description { white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }
.pagination { margin-top: 20px; text-align: right; }
</style>
