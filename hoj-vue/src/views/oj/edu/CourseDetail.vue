<template>
  <div class="edu-page" :style="eduPageStyle" v-loading="loading">
    <router-link :to="backTarget">{{ backTarget.startsWith('/class') ? '← 返回班级' : '← 返回课程总览' }}</router-link>
    <section v-if="detail" class="course-content">
      <header class="page-header"><h2>{{ detail.title }}</h2><div><el-tag :type="detail.status === 1 ? 'success' : 'info'">{{ states[detail.status] }}</el-tag><el-button v-if="detail.canEdit" :disabled="editingProblems || uploadingIcon" class="edit-button" @click="editMetadata">编辑资料与发布状态</el-button><el-button :disabled="editingProblems || saving || uploadingIcon" @click="load">刷新课程</el-button></div></header>
      <div class="course-icon-editor">
        <img v-if="detail.iconUrl" :src="detail.iconUrl" alt="课程图标" class="course-icon">
        <i v-else class="el-icon-reading default-icon" aria-hidden="true" />
        <div v-if="detail.canEdit">
          <input ref="iconInput" class="icon-input" type="file" accept="image/png,image/jpeg" aria-label="课程图标文件" @change="uploadIcon">
          <el-button :loading="uploadingIcon" :disabled="editingProblems || saving" @click="$refs.iconInput.click()">{{ detail.iconUrl ? '更换课程图标' : '上传课程图标' }}</el-button>
          <el-button v-if="detail.iconUrl" :disabled="editingProblems || saving || uploadingIcon" @click="removeIcon">恢复默认图标</el-button>
          <p class="hint icon-hint">支持PNG/JPEG，最大2MB，长宽不超过2048像素。图标保存后同步到课程总览与所有班级。</p>
        </div>
      </div>
      <p class="hint">课程显示ID：{{ detail.displayId }} · 创建者：{{ detail.ownerUsername || '原账号已删除' }} · 版本 {{ detail.revision }} · 本人已完成 {{ detail.completedCount }}/{{ detail.problemCount }} 题</p>
      <p class="description">{{ detail.description || '暂无课程介绍' }}</p>
      <p v-if="detail.canEdit" class="hint">当前被 {{ detail.referenceCount }} 个班级引用，保存修改后将统一生效。</p>
      <p><el-tag v-for="item in detail.sourceClasses" :key="item.id" size="small" class="class-tag">{{ item.name }}</el-tag></p>
      <el-alert v-if="detail.unavailableCount" title="有题目已不再公开。学生端已隐藏，请在编辑题目中移除不可用项后保存。" type="warning" :closable="false" show-icon />
      <el-alert v-if="conflict" title="课程已被更新。你的本地编辑仍保留，请取消编辑并刷新，再合并修改。" type="warning" :closable="false" show-icon />
      <el-divider />
      <div class="page-header"><h3>课程题目</h3><div v-if="detail.canEdit"><el-button v-if="!editingProblems" :disabled="uploadingIcon" type="primary" @click="startProblems">编辑题目与顺序</el-button><template v-else><el-button :disabled="saving" @click="pickerDialog = true">从公开题库选题</el-button><el-button :disabled="saving" @click="sortDraft">按ID预览排序</el-button><el-button :disabled="saving" @click="cancelProblems">取消编辑</el-button><el-button type="primary" :loading="saving" @click="saveProblems">保存题目并同步</el-button></template></div></div>
      <p class="hint">{{ editingProblems ? '当前选择 ' + draftProblems.length + '/500 题。填写课程内ID后按数字升序排列，同一课程内不能重复；上移、下移会交换两题的显示ID。' : '按课程内ID从小到大排列，点击题目所在行进入练习。' }}</p>
      <CourseProblemTable :problems="shownProblems" :editing="editingProblems" :saving="saving" @move="move" @remove="removeProblem" />
    </section>
    <p v-else-if="!loading" class="edu-empty">无法访问该课程，请检查班级归属或课程发布状态。</p>
    <el-dialog title="编辑共享课程" :visible.sync="metadataDialog" width="min(600px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="top"><el-form-item label="课程名称" required><el-input v-model.trim="form.title" maxlength="128" show-word-limit /></el-form-item><el-form-item label="课程显示ID（排序）" required><el-input-number v-model="form.displayId" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" /><p class="hint">课程总览和各班课程均按此ID从小到大排列；相同ID按创建先后排列。</p></el-form-item><el-form-item label="课程介绍"><el-input type="textarea" :rows="5" v-model="form.description" maxlength="10000" show-word-limit /></el-form-item><el-form-item label="发布状态"><el-select v-model="form.status"><el-option :value="0" label="草稿" /><el-option :value="1" label="已发布" /><el-option :value="2" label="归档" /></el-select></el-form-item></el-form>
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
import CourseProblemTable from './CourseProblemTable.vue'
export default {
  mixins: [eduLayout], components: {CourseProblemPicker, CourseProblemTable},
  data: () => ({detail: null, loading: false, saving: false, uploadingIcon: false, metadataDialog: false, pickerDialog: false, editingProblems: false, draftProblems: [], problemsRevision: null, conflict: false, form: {}, states: ['草稿', '已发布', '已归档']}),
  computed: {
    id () { return this.$route.params.courseId },
    shownProblems () { return this.editingProblems ? this.draftProblems : (this.detail ? this.detail.problems : []) },
    backTarget () {
      const classId = this.$route.query.classId
      if (this.detail && this.detail.sourceClasses.some(row => String(row.id) === classId)) return '/class/' + classId
      return '/course'
    }
  },
  watch: {id () { this.cancelProblems(); this.load() }},
  mounted () { this.load() },
  methods: {
    async uploadIcon (event) {
      const image = event.target.files[0]; event.target.value = ''
      if (!image) return
      if (!['image/png', 'image/jpeg'].includes(image.type) || image.size > 2 * 1024 * 1024) { this.$message.error('请选择不超过2MB的PNG或JPEG图片'); return }
      this.uploadingIcon = true
      try { this.detail = await edu.uploadCourseIcon(this.id, image, this.detail.revision); this.$message.success('课程图标已保存并同步') } catch (error) { this.markConflict(error) } finally { this.uploadingIcon = false }
    },
    async removeIcon () { this.uploadingIcon = true; try { this.detail = await edu.removeCourseIcon(this.id, this.detail.revision); this.$message.success('已恢复默认图标') } catch (error) { this.markConflict(error) } finally { this.uploadingIcon = false } },
    async load () { this.loading = true; this.detail = null; this.conflict = false; try { this.detail = await edu.course(this.id) } catch (_) {} finally { this.loading = false } },
    editMetadata () { this.form = {title: this.detail.title, description: this.detail.description, status: this.detail.status, revision: this.detail.revision, displayId: this.detail.displayId}; this.metadataDialog = true },
    markConflict (error) { this.conflict = !!(error && error.data && error.data.status === 409) },
    async saveMetadata () { if (!Number.isInteger(this.form.displayId) || this.form.displayId < 1) { this.$message.error('请填写课程显示ID'); return }; this.saving = true; try { this.detail = await edu.updateCourse(this.id, this.form); this.metadataDialog = false; this.conflict = false; this.$message.success('课程资料已统一更新') } catch (error) { this.markConflict(error) } finally { this.saving = false } },
    startProblems () { this.draftProblems = this.detail.problems.map(row => ({...row})); this.problemsRevision = this.detail.revision; this.editingProblems = true; this.conflict = false },
    cancelProblems () { this.editingProblems = false; this.pickerDialog = false; this.draftProblems = []; this.conflict = false },
    pick (row) {
      if (this.draftProblems.length >= 500 || this.draftProblems.some(item => item.pid === row.id)) return
      const used = new Set(this.draftProblems.map(item => item.displayId)); let displayId = 1; while (used.has(displayId)) displayId++
      this.draftProblems.push({pid:row.id, problemId:row.problemId, title:row.title, difficulty:row.difficulty, displayId, available:1, completed:false, myStatus:-10})
    },
    validDisplayIds () {
      const ids = this.draftProblems.map(row => row.displayId)
      if (ids.some(id => !Number.isInteger(id) || id < 1 || id > 2147483647) || new Set(ids).size !== ids.length) { this.$message.error('课程内ID须为不重复的正整数，最大2147483647'); return false }
      return true
    },
    sortDraft () { if (!this.validDisplayIds()) return false; this.draftProblems = [...this.draftProblems].sort((a,b) => a.displayId-b.displayId); return true },
    move (row, delta) {
      if (!this.sortDraft()) return
      const index = this.draftProblems.findIndex(item => item.pid === row.pid); const target = index+delta
      if (target < 0 || target >= this.draftProblems.length) return
      const value = row.displayId; row.displayId = this.draftProblems[target].displayId; this.draftProblems[target].displayId = value; this.sortDraft()
    },
    removeProblem (row) { this.draftProblems = this.draftProblems.filter(item => item.pid !== row.pid) },
    async saveProblems () { if (!this.validDisplayIds()) return; this.saving = true; try { this.detail = await edu.saveCourseProblems(this.id, {problemIds: this.draftProblems.map(item => item.pid), displayIds: this.draftProblems.map(item => item.displayId), revision: this.problemsRevision}); this.cancelProblems(); this.$message.success('题目列表已按ID排序，引用班级统一更新') } catch (error) { this.markConflict(error) } finally { this.saving = false } }
  }
}
</script>
<style scoped>
.course-content { margin-top: 22px; }h3 { margin: 0; }.hint { color: #606266; line-height: 1.8; }.description { white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }.edit-button { margin-left: 10px; }.class-tag { margin: 2px 4px 2px 0; }.course-icon-editor { display: flex; gap: 20px; align-items: center; margin: 16px 0; }.course-icon { width: 88px; height: 88px; object-fit: contain; border-radius: 12px; background: #fff; padding: 6px; }.default-icon { font-size: 60px; color: #c58a32; }.icon-input { display: none; }.icon-hint { margin-bottom: 0; font-size: 12px; }
</style>
