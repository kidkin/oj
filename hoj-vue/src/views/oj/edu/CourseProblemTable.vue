<template>
  <div class="course-problem-table">
    <vxe-table :key="editing ? 'edit' : 'view'" border="inner" stripe auto-resize highlight-hover-row align="center" :data="visibleProblems" :row-class-name="rowClass" empty-text="暂无课程题目" @cell-click="openProblem">
      <vxe-table-column title="" width="50">
        <template v-slot="{row}"><el-tooltip v-if="row.available && row.myStatus !== -10" :content="statusInfo(row).name" placement="top"><i :class="row.completed ? 'el-icon-check' : 'el-icon-minus'" :aria-label="statusInfo(row).name" :style="{color:statusInfo(row).rgb, fontWeight:600, fontSize:'16px'}" /></el-tooltip></template>
      </vxe-table-column>
      <vxe-table-column field="displayId" title="课程内ID" :width="editing ? 190 : 110">
        <template v-slot="{row}"><el-input-number v-if="editing" v-model="row.displayId" :aria-label="row.problemId + ' 的课程内ID'" :min="1" :max="2147483647" :precision="0" step-strictly controls-position="right" size="small" :disabled="saving" /><span v-else>{{ row.displayId }}</span></template>
      </vxe-table-column>
      <vxe-table-column field="problemId" title="原题号" width="180" show-overflow>
        <template v-slot="{row}"><router-link v-if="row.available && !editing" :to="{name:'ProblemDetails', params:{problemID:row.problemId}}" @click.native.stop>{{ row.problemId }}</router-link><span v-else>{{ row.problemId || '不可用' }}</span></template>
      </vxe-table-column>
      <vxe-table-column field="title" title="题目名称" min-width="200" show-overflow />
      <vxe-table-column field="difficulty" title="难度" min-width="100"><template v-slot="{row}"><span v-if="row.available" class="el-tag el-tag--small" :style="levelColor(row.difficulty)">{{ levelName(row.difficulty) }}</span><span v-else>不可用</span></template></vxe-table-column>
      <vxe-table-column v-if="!editing" key="tags" title="标签" min-width="140"><template v-slot:header><el-link :type="showTags ? 'danger' : 'primary'" :underline="false" @click.stop="showTags = !showTags">{{ showTags ? '隐藏标签' : '显示标签' }}</el-link></template><template v-slot="{row}"><div v-if="showTags"><span v-for="tag in row.tags" :key="tag.id" class="el-tag el-tag--small problem-tag" :style="{backgroundColor:tag.color || '#409eff'}">{{ tag.name }}</span></div></template></vxe-table-column>
      <vxe-table-column v-if="!editing" key="rate" title="通过率" min-width="150"><template v-slot="{row}"><el-tooltip v-if="row.available" :content="'公开普通提交：' + (row.ac || 0) + '/' + (row.total || 0)" placement="top"><el-progress :text-inside="true" :stroke-width="20" :percentage="passingRate(row)" /></el-tooltip></template></vxe-table-column>
      <vxe-table-column v-if="editing" key="actions" title="操作" width="220"><template v-slot="{row}"><el-button type="text" :disabled="saving || problems[0] === row" @click.stop="$emit('move',row,-1)">上移</el-button><el-button type="text" :disabled="saving || problems[problems.length-1] === row" @click.stop="$emit('move',row,1)">下移</el-button><el-button type="text" :disabled="saving" @click.stop="$emit('remove',row)">移除</el-button></template></vxe-table-column>
    </vxe-table>
    <Pagination v-if="!editing" :total="problems.length" :page-size="pageSize" :current.sync="page" :layout="'prev, pager, next, sizes'" @on-change="page = $event" @on-page-size-change="changeSize" />
  </div>
</template>
<script>
import utils from '@/common/utils'
import {JUDGE_STATUS} from '@/common/constants'
import Pagination from '@/components/oj/common/Pagination'
export default {
  components: {Pagination},
  props: {problems: {type:Array, default:() => []}, editing:Boolean, saving:Boolean},
  data: () => ({showTags:false, page:1, pageSize:10}),
  computed: {visibleProblems () { return this.editing ? this.problems : this.problems.slice((this.page-1)*this.pageSize, this.page*this.pageSize) }},
  watch: {problems () { this.page = 1 }, editing () { this.page = 1 }},
  methods: {
    levelName:utils.getLevelName, levelColor:utils.getLevelColor,
    statusInfo (row) { return JUDGE_STATUS[row.myStatus] || JUDGE_STATUS[-10] },
    passingRate (row) { return row.total ? Math.min(100, Math.round((row.ac || 0) / row.total * 100)) : 0 },
    rowClass ({row}) { return !this.editing && row.available ? 'course-problem-clickable' : '' },
    openProblem ({row}) { if (!this.editing && row.available) this.$router.push({name:'ProblemDetails',params:{problemID:row.problemId}}) },
    changeSize (value) { this.pageSize = value; this.page = 1 }
  }
}
</script>
<style scoped>
.course-problem-table { min-width: 0; display: flow-root; }.course-problem-table /deep/ .course-problem-clickable { cursor: pointer; }.problem-tag { color: #fff; margin: 2px 4px; }.course-problem-table /deep/ .el-input-number { width: 160px; }
</style>
