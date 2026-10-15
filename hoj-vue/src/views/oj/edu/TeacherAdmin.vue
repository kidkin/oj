<template>
  <div v-loading="loading">
    <p class="hint">给已有账号授予教师身份。教师可以管理自己的班级，不会获得网站管理员权限。</p>
    <el-form inline @submit.native.prevent="grant">
      <el-form-item label="用户名"><el-input v-model.trim="username" maxlength="100" placeholder="已有账号的用户名" /></el-form-item>
      <el-form-item><el-button native-type="submit" type="primary" :loading="saving" :disabled="!username">授予教师身份</el-button></el-form-item>
    </el-form>
    <el-table :data="teachers" empty-text="暂无已授权教师">
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="nickname" label="昵称" />
      <el-table-column label="账号状态"><template slot-scope="scope">{{ scope.row.accountStatus === 0 ? '正常' : '已停用' }}</template></el-table-column>
      <el-table-column label="操作" width="150"><template slot-scope="scope"><el-button type="text" :disabled="saving" @click="revoke(scope.row)">撤销教师身份</el-button></template></el-table-column>
    </el-table>
    <p class="hint">撤销后将失去教师管理权限，原有班级和成员数据保留。</p>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
export default {
  data: () => ({username: '', teachers: [], loading: false, saving: false}),
  mounted () { this.load() },
  methods: {
    async load () { this.loading = true; try { this.teachers = await edu.teachers() } catch (_) {} finally { this.loading = false } },
    async grant () {
      if (!this.username || this.saving) return
      this.saving = true
      try { await edu.grantTeacher(this.username); this.username = ''; this.$message.success('教师授权已保存'); await this.load() } catch (_) {} finally { this.saving = false }
    },
    async revoke (row) {
      this.saving = true
      try { await edu.revokeTeacher(row.uid); this.$message.success('教师身份已撤销'); await this.load() } catch (_) {} finally { this.saving = false }
    }
  }
}
</script>
<style scoped>.hint { color: #606266; line-height: 1.8; }</style>
