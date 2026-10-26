<template>
  <section>
    <div class="page-header"><h3>班级课程</h3><el-button v-if="canManage" type="primary" :disabled="archived" @click="pickerDialog = true">添加共享课程</el-button></div>
    <div class="course-strips" v-loading="loading">
      <div v-for="row in records" :key="row.id" class="course-strip" :class="{managed:canManage}">
        <LearningCard layout="row" :display-id="row.displayId" :to="{path:'/course/' + row.id, query:{classId:String(classId)}}" :title="row.title" :description="row.description" :icon="row.iconUrl" :owner="row.ownerUsername" :completed="row.completedCount" :total="row.problemCount" />
        <el-button v-if="canManage" class="remove-reference" type="text" :disabled="saving" @click="detach(row)">移除本班引用</el-button>
      </div>
      <p v-if="!loading && !records.length" class="empty-state">教师还没有为班级添加课程。</p>
    </div>
    <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
    <el-dialog title="从课程库添加引用" :visible.sync="pickerDialog" width="min(920px, 94vw)" :close-on-click-modal="false" append-to-body>

      <el-form inline @submit.native.prevent="search"><el-form-item><el-input v-model="libraryQuery.keyword" maxlength="128" placeholder="搜索课程名称" clearable @clear="search" /></el-form-item><el-form-item><el-button native-type="submit">搜索</el-button></el-form-item></el-form>
      <el-table :data="library" v-loading="libraryLoading" empty-text="暂无已发布课程">
        <el-table-column prop="displayId" label="课程ID" width="100" /><el-table-column prop="title" label="课程名称" min-width="170" /><el-table-column prop="ownerUsername" label="创建者" min-width="100" /><el-table-column prop="problemCount" label="公开题目" width="100" />
        <el-table-column label="操作" width="110"><template slot-scope="scope"><el-button type="text" :disabled="saving || addedIds.includes(scope.row.id)" @click="attach(scope.row)">{{ addedIds.includes(scope.row.id) ? '已引用' : '加入班级' }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="libraryQuery.page" :page-size="libraryQuery.limit" :total="libraryTotal" @current-change="loadLibrary" />
      <span slot="footer"><el-button @click="pickerDialog = false">完成</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import edu from '@/common/edu-api'
import LearningCard from './LearningCard.vue'
export default {
  components: {LearningCard},
  props: {classId: {type: [String, Number], required: true}, canManage: Boolean, archived: Boolean},
  data: () => ({records: [], total: 0, query: {page: 1, limit: 10}, loading: false, saving: false, pickerDialog: false, library: [], libraryTotal: 0, libraryQuery: {page: 1, limit: 10, keyword: '', publishedOnly: true}, libraryLoading: false, addedIds: []}),
  watch: {classId () { this.query.page = 1; this.addedIds = []; this.load() }, pickerDialog (value) { if (value) this.loadLibrary() }},
  mounted () { this.load() },
  methods: {
    async load () { this.loading = true; try { const data = await edu.classCourses(this.classId, this.query); this.records = data.records; this.total = data.total; this.addedIds = [...new Set([...this.addedIds, ...this.records.map(row => row.id)])] } catch (_) { this.records = [] } finally { this.loading = false } },
    search () { this.libraryQuery.page = 1; this.loadLibrary() },
    async loadLibrary () { this.libraryLoading = true; try { const data = await edu.courses(this.libraryQuery); this.library = data.records; this.libraryTotal = data.total } catch (_) { this.library = [] } finally { this.libraryLoading = false } },
    async attach (row) { this.saving = true; try { await edu.attachCourses(this.classId, [row.id]); this.addedIds.push(row.id); await this.load(); this.$message.success('已加入共享课程') } catch (_) {} finally { this.saving = false } },
    async detach (row) { this.saving = true; try { await edu.detachCourse(this.classId, row.id); this.addedIds = this.addedIds.filter(id => id !== row.id); await this.load(); this.$message.success('已移除本班引用，课程和其他班级保留') } catch (_) {} finally { this.saving = false } }
  }
}
</script>
<style scoped>.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }h3 { margin: 0; }.hint { color: #606266; line-height: 1.8; }.course-strips { display: flex; flex-direction: column; gap: 16px; }.course-strip { position: relative; width: 100%; }.remove-reference { position: absolute; top: 5px; right: 16px; }.managed /deep/ h3 { padding-right: 140px; }.empty-state { padding: 36px 12px; text-align: center; color: #8493a0; }</style>
