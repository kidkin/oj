<template>
  <div>
    <el-form inline @submit.native.prevent="search"><el-form-item><el-input v-model="query.keyword" maxlength="128" placeholder="搜索题号或题目名称" clearable @clear="search" /></el-form-item><el-form-item><el-button native-type="submit">搜索</el-button></el-form-item></el-form>
    <el-table :data="records" v-loading="loading" empty-text="暂无符合条件的公开题目">
      <el-table-column prop="problemId" label="题号" min-width="130" /><el-table-column prop="title" label="题目名称" min-width="180" />
      <el-table-column label="操作" width="110"><template slot-scope="scope"><el-button type="text" :disabled="selectedIds.includes(scope.row.id) || selectedIds.length >= 500" @click="$emit('pick', scope.row)">{{ selectedIds.includes(scope.row.id) ? '已选择' : '加入课程' }}</el-button></template></el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="prev, pager, next, total" :current-page.sync="query.page" :page-size="query.limit" :total="total" @current-change="load" />
  </div>
</template>
<script>
import edu from '@/common/edu-api'
export default {
  props: {selectedIds: {type: Array, default: () => []}},
  data: () => ({query: {page: 1, limit: 10, keyword: ''}, records: [], total: 0, loading: false}),
  mounted () { this.load() },
  methods: {
    search () { this.query.page = 1; this.load() },
    async load () { this.loading = true; try { const data = await edu.publicProblems(this.query); this.records = data.records; this.total = data.total } catch (_) { this.records = [] } finally { this.loading = false } }
  }
}
</script>
<style scoped>.pagination { margin-top: 20px; text-align: right; }</style>
