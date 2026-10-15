<template>
  <div class="course-page" :style="eduPageStyle">
    <el-card>
      <div slot="header" class="page-header"><h2>{{ access.isTeacher ? '课程库' : '我的课程' }}</h2><el-button v-if="access.isTeacher" type="primary" @click="createDialog = true">创建课程</el-button></div>
      <p class="hint">{{ access.isTeacher ? '班级引用同一份课程，内容修改后统一更新。' : '这里汇总你所在班级的课程，同一门课程只显示一次。' }}</p>
      <el-form inline @submit.native.prevent="search">
        <el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item>
        <el-form-item v-if="access.isTeacher"><el-select v-model="query.scope" @change="search"><el-option value="library" label="课程库" /><el-option value="mine" label="我创建的" /></el-select></el-form-item>
        <el-form-item><el-button native-type="submit">搜索</el-button></el-form-item>
      </el-form>
      <el-table :data="records" v-loading="loading" empty-text="暂无可访问的课程">
        <el-table-column label="课程名称" min-width="180"><template slot-scope="scope"><router-link :to="'/course/' + scope.row.id">{{ scope.row.title }}</router-link></template></el-table-column>
        <el-table-column prop="ownerUsername" label="创建者" min-width="100" />
        <el-table-column prop="problemCount" label="公开题目" width="100" />
        <el-table-column v-if="access.isTeacher" label="状态" width="100"><template slot-scope="scope"><el-tag :type="scope.row.status === 1 ? 'success' : 'info'">{{ states[scope.row.status] }}</el-tag></template></el-table-column>
        <el-table-column label="所属班级" min-width="160"><template slot-scope="scope"><el-tag v-for="item in scope.row.sourceClasses" :key="item.id" size="small" class="class-tag">{{ item.name }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template slot-scope="scope"><el-button type="text" @click="$router.push('/course/' + scope.row.id)">{{ scope.row.canEdit ? '查看与编辑' : '进入课程' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
    </el-card>
    <el-dialog title="创建共享课程" :visible.sync="createDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item>
      </el-form>
      <p class="hint">先保存为草稿，加入公开题目并发布后，班级即可引用。</p>
      <span slot="footer"><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :disabled="!form.title" :loading="saving" @click="create">创建草稿</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
export default {
  mixins: [eduLayout],
  data: () => ({access: {}, query: {page: 1, limit: 10, keyword: '', scope: 'library'}, records: [], total: 0, loading: false, saving: false, createDialog: false, form: {title: '', description: ''}, states: ['草稿', '已发布', '已归档']}),
  async mounted () { try { this.access = await edu.me() } catch (_) {} this.load() },
  methods: {
    async load () { this.loading = true; try { const data = await edu.courses(this.query); this.records = data.records; this.total = data.total } catch (_) { this.records = [] } finally { this.loading = false } },
    search () { this.query.page = 1; this.load() },
    async create () { if (!this.form.title || this.saving) return; this.saving = true; try { const row = await edu.createCourse(this.form); this.createDialog = false; this.$router.push('/course/' + row.id) } catch (_) {} finally { this.saving = false } }
  }
}
</script>
<style scoped>
.course-page { max-width: 1180px; margin: 24px auto; padding: 0 12px; }.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }h2 { margin: 0; font-size: 22px; }.hint { color: #606266; line-height: 1.8; }.pagination { margin-top: 20px; text-align: right; }.class-tag { margin: 2px 4px 2px 0; }
</style>
