<template>
  <div class="class-page" v-loading="loading">
    <router-link to="/class">← 返回我的班级</router-link>
    <el-card v-if="detail" class="detail-card">
      <div slot="header" class="page-header"><h2>{{ detail.name }}</h2><div><el-tag :type="detail.status === 0 ? 'success' : 'info'">{{ detail.status === 0 ? '正常' : '已归档' }}</el-tag><el-button v-if="detail.canManage" class="edit-button" @click="edit">编辑班级</el-button></div></div>
      <p class="meta">创建者：{{ detail.ownerUsername }} · 学生 {{ detail.studentCount }} 人</p>
      <p class="description">{{ detail.description || '暂无班级介绍' }}</p>
      <p v-if="!detail.canManage" class="meta">你已加入此班级。</p>
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
    </el-card>
    <el-card v-else-if="!loading" class="detail-card"><p>无法访问该班级，请检查成员身份或返回班级列表。</p></el-card>
    <el-dialog title="编辑班级" :visible.sync="editDialog" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="班级名称" required><el-input v-model.trim="form.name" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="班级介绍"><el-input v-model="form.description" type="textarea" :rows="5" maxlength="10000" show-word-limit /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option :value="0" label="正常" /><el-option :value="1" label="归档" /></el-select><p class="meta">归档后学生无法进入，管理教师可在此恢复。</p></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="editDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.name" @click="save">保存</el-button></span>
    </el-dialog>
    <el-dialog title="添加已有成员" :visible.sync="memberDialog" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="成员身份"><el-select v-model="memberForm.role"><el-option label="学生" value="STUDENT" /><el-option label="教师" value="TEACHER" /></el-select></el-form-item>
        <el-form-item label="已有账号的用户名" required><el-input type="textarea" v-model="memberForm.names" :rows="7" placeholder="每行一个用户名，也可用逗号分隔；每次最多100个" /></el-form-item>
        <p class="meta">添加教师前，需由超级管理员授予该账号教师身份。重复添加可更新成员身份。</p>
      </el-form>
      <span slot="footer"><el-button @click="memberDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!memberForm.names.trim()" @click="addMembers">添加</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
export default {
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
    edit () { this.form = {name: this.detail.name, description: this.detail.description, status: this.detail.status}; this.editDialog = true },
    async save () { this.saving = true; try { await edu.update(this.id, this.form); this.editDialog = false; this.$message.success('班级已保存'); await this.load() } catch (_) {} finally { this.saving = false } },
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
.class-page { max-width: 1180px; margin: 24px auto; padding: 0 12px; }.detail-card { margin-top: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 22px; }h3 { margin: 0 0 12px; }.edit-button { margin-left: 12px; }
.meta { color: #606266; line-height: 1.8; }.description { white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }
.pagination { margin-top: 20px; text-align: right; }
</style>
