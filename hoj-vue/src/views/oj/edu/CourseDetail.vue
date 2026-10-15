<template>
  <div class="course-page" :style="eduPageStyle" v-loading="loading">
    <router-link to="/course">← 返回课程列表</router-link>
    <el-card v-if="detail" class="course-card">
      <div slot="header" class="page-header"><h2>{{ detail.title }}</h2><div><el-tag :type="detail.status === 1 ? 'success' : 'info'">{{ states[detail.status] }}</el-tag><el-button v-if="detail.canEdit" :disabled="editingProblems" class="edit-button" @click="editMetadata">编辑资料与发布状态</el-button><el-button :disabled="editingProblems || saving" @click="load">刷新课程</el-button></div></div>
      <p class="hint">创建者：{{ detail.ownerUsername || '原账号已删除' }} · 版本 {{ detail.revision }} · 本人已完成 {{ detail.completedCount }}/{{ detail.problemCount }} 题</p>
      <p class="description">{{ detail.description || '暂无课程介绍' }}</p>
      <p v-if="detail.canEdit" class="hint">当前被 {{ detail.referenceCount }} 个班级引用，保存修改后将统一生效。</p>
      <p><el-tag v-for="item in detail.sourceClasses" :key="item.id" size="small" class="class-tag">{{ item.name }}</el-tag></p>
      <el-alert v-if="detail.unavailableCount" title="有题目已不再公开。学生端已隐藏，请在编辑题目中移除不可用项后保存。" type="warning" :closable="false" show-icon />
      <el-alert v-if="conflict" title="课程已被更新。你的本地编辑仍保留，请取消编辑并刷新，再合并修改。" type="warning" :closable="false" show-icon />
      <el-divider />
      <div class="page-header"><h3>课程题目</h3><div v-if="detail.canEdit"><el-button v-if="!editingProblems" type="primary" @click="startProblems">编辑题目与顺序</el-button><template v-else><el-button @click="pickerDialog = true">从公开题库选题</el-button><el-button :disabled="saving" @click="cancelProblems">取消编辑</el-button><el-button type="primary" :loading="saving" @click="saveProblems">保存题目并同步</el-button></template></div></div>
      <p v-if="editingProblems" class="hint">当前选择 {{ draftProblems.length }}/500 题。使用上移、下移调整顺序；移除只解除课程引用，题库原题和提交保留。</p>
      <el-table :data="shownProblems" empty-text="暂无题目">
        <el-table-column type="index" label="顺序" width="70" />
        <el-table-column label="题号" min-width="130"><template slot-scope="scope"><router-link v-if="scope.row.available" :to="{name:'ProblemDetails', params:{problemID:scope.row.problemId}}">{{ scope.row.problemId }}</router-link><span v-else>不可用</span></template></el-table-column>
        <el-table-column prop="title" label="题目名称" min-width="180" />
        <el-table-column label="本人完成情况" width="130"><template slot-scope="scope"><el-tag v-if="scope.row.available" :type="scope.row.completed ? 'success' : 'info'">{{ scope.row.completed ? '已完成' : '未完成' }}</el-tag></template></el-table-column>
        <el-table-column v-if="editingProblems" label="调整" width="200"><template slot-scope="scope"><el-button type="text" :disabled="saving || scope.$index === 0" @click="move(scope.$index, -1)">上移</el-button><el-button type="text" :disabled="saving || scope.$index === draftProblems.length - 1" @click="move(scope.$index, 1)">下移</el-button><el-button type="text" :disabled="saving" @click="draftProblems.splice(scope.$index, 1)">移除</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-card v-else-if="!loading" class="course-card"><p>无法访问该课程，请检查班级归属或课程发布状态。</p></el-card>
    <el-dialog title="编辑共享课程" :visible.sync="metadataDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item><el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item><el-form-item label="发布状态"><el-select v-model="form.status"><el-option :value="0" label="草稿" /><el-option :value="1" label="已发布" /><el-option :value="2" label="归档" /></el-select></el-form-item></el-form>
      <p class="hint">有班级引用时，须先解除引用才能取消发布或归档。发布后，教师可从课程库添加到自己的班级。</p>
      <span slot="footer"><el-button :disabled="saving" @click="metadataDialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.title" @click="saveMetadata">保存并同步到引用班级</el-button></span>
    </el-dialog>
    <el-dialog title="选择公开题目" :visible.sync="pickerDialog" width="min(920px, 94vw)" :close-on-click-modal="false"><CourseProblemPicker v-if="pickerDialog" :selected-ids="draftProblems.map(item => item.pid)" @pick="pick" /><span slot="footer"><el-button @click="pickerDialog = false">完成选择</el-button></span></el-dialog>
  </div>
</template>
<script>
import edu from '@/common/edu-api'
import eduLayout from '@/common/edu-layout'
import CourseProblemPicker from './CourseProblemPicker.vue'
export default {
  mixins: [eduLayout], components: {CourseProblemPicker},
  data: () => ({detail: null, loading: false, saving: false, metadataDialog: false, pickerDialog: false, editingProblems: false, draftProblems: [], problemsRevision: null, conflict: false, form: {}, states: ['草稿', '已发布', '已归档']}),
  computed: {id () { return this.$route.params.courseId }, shownProblems () { return this.editingProblems ? this.draftProblems : this.detail.problems }},
  watch: {id () { this.cancelProblems(); this.load() }},
  mounted () { this.load() },
  methods: {
    async load () { this.loading = true; this.detail = null; this.conflict = false; try { this.detail = await edu.course(this.id) } catch (_) {} finally { this.loading = false } },
    editMetadata () { this.form = {title: this.detail.title, description: this.detail.description, status: this.detail.status, revision: this.detail.revision}; this.metadataDialog = true },
    markConflict (error) { this.conflict = !!(error && error.data && error.data.status === 409) },
    async saveMetadata () { this.saving = true; try { this.detail = await edu.updateCourse(this.id, this.form); this.metadataDialog = false; this.conflict = false; this.$message.success('课程资料已统一更新') } catch (error) { this.markConflict(error) } finally { this.saving = false } },
    startProblems () { this.draftProblems = this.detail.problems.map(row => ({...row})); this.problemsRevision = this.detail.revision; this.editingProblems = true; this.conflict = false },
    cancelProblems () { this.editingProblems = false; this.pickerDialog = false; this.draftProblems = []; this.conflict = false },
    pick (row) { if (this.draftProblems.length >= 500 || this.draftProblems.some(item => item.pid === row.id)) return; this.draftProblems.push({pid: row.id, problemId: row.problemId, title: row.title, available: 1, completed: false}) },
    move (index, delta) { const target = index + delta; if (target < 0 || target >= this.draftProblems.length) return; const item = this.draftProblems.splice(index, 1)[0]; this.draftProblems.splice(target, 0, item) },
    async saveProblems () { this.saving = true; try { this.detail = await edu.saveCourseProblems(this.id, {problemIds: this.draftProblems.map(item => item.pid), revision: this.problemsRevision}); this.cancelProblems(); this.$message.success('题目列表已保存，引用班级统一更新') } catch (error) { this.markConflict(error) } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.course-page { max-width: 1180px; margin: 24px auto; padding: 0 12px; }.course-card { margin-top: 16px; }.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }h2 { margin: 0; font-size: 22px; }h3 { margin: 0 0 12px; }.hint { color: #606266; line-height: 1.8; }.description { white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }.edit-button { margin-left: 10px; }.class-tag { margin: 2px 4px 2px 0; }
</style>
