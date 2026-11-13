<template>
  <section class="learning-summary" aria-labelledby="learning-footprint-title">
    <header><p>学习概览</p><h2 id="learning-footprint-title">我的学习足迹</h2></header>
    <dl class="summary-stats">
      <div><dt>学习天数</dt><dd>{{ summary.learningDays }}<small>天</small></dd></div>
      <div><dt>已完成题目</dt><dd>{{ summary.completedCount }}<small>道</small></dd></div>
      <div><dt>总提交数</dt><dd>{{ summary.submissionCount }}<small>次</small></dd></div>
    </dl>
    <div class="weekly"><h3>本周进展</h3><span>通过 <strong>{{ summary.weeklyCompleted }}</strong> 道题</span><span>提交 <strong>{{ summary.weeklySubmissions }}</strong> 次</span></div>
    <div class="activity"><h3>最近一年提交热力图</h3>
      <div v-if="heatmap" class="heatmap-scroll"><calendar-heatmap :values="heatmap.dataList" :end-date="heatmap.endDate" tooltip-unit="次提交" :range-color="['#e8edf2','#bce5d4','#80c9b4','#489c8c','#226c66']" /></div>
      <p v-else-if="heatmapFailed">热力图暂时无法加载 <el-button type="text" @click="loadHeatmap">重试</el-button></p>
    </div>
    <div class="activity"><h3>已通过题目 · 难度分布</h3>
      <p v-if="!summary.difficultyCounts.length">暂无通过记录</p>
      <ul v-else class="distribution">
        <li v-for="row in summary.difficultyCounts" :key="row.difficulty"><span :style="levelColor(row.difficulty)">{{ levelName(row.difficulty) }}</span><strong>{{ row.completed }} 道</strong></li>
      </ul>
    </div>
  </section>
</template>

<script>
import api from '@/common/api'
import utils from '@/common/utils'
import {CalendarHeatmap} from 'vue-calendar-heatmap'
import 'vue-calendar-heatmap/dist/vue-calendar-heatmap.css'

export default {
  components: {CalendarHeatmap},
  props: {summary: {type: Object, required: true}},
  data: () => ({heatmap: null, heatmapFailed: false}),
  mounted () { this.loadHeatmap() },
  methods: {
    levelColor: utils.getLevelColor,
    levelName: utils.getLevelName,
    async loadHeatmap () {
      this.heatmapFailed = false
      try { const res = await api.getUserCalendarHeatmap(); this.heatmap = res.data.data }
      catch (_) { this.heatmapFailed = true }
    }
  }
}
</script>

<style scoped>
.learning-summary { margin-bottom:24px; padding:24px 28px; border:1px solid #dceae8; border-radius:18px; background:linear-gradient(130deg,#f1faf7,#fff 75%); color:#263e55; }
header p { color:#397c71; margin:0 0 5px; }h2 { margin:0 0 18px; font-size:25px; }h3 { margin:0; font-size:15px; }
.summary-stats { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin:0 0 14px; }.summary-stats div { border:1px solid #e2eeec; border-radius:12px; background:#fff; padding:12px; }.summary-stats dt { color:#527271; }.summary-stats dd { font-weight:700; font-size:clamp(20px,3vw,30px); margin:4px 0 0; }.summary-stats small { font-size:12px; font-weight:400; margin-left:4px; }
.weekly { display:flex; align-items:baseline; flex-wrap:wrap; gap:8px 18px; padding:12px 0; }.weekly strong { color:#1f7667; }.activity { margin-top:14px; }.activity p { color:#657786; }.heatmap-scroll { overflow-x:auto; margin-top:12px; }.heatmap-scroll /deep/ svg { min-width:560px; max-width:950px; }
.distribution { display:flex; gap:8px; flex-wrap:wrap; list-style:none; padding:0; margin:10px 0 0; }.distribution li { display:flex; gap:7px; align-items:center; padding:4px 8px; border:1px solid #e0e9ee; border-radius:8px; background:#fff; }.distribution span { border-radius:4px; padding:2px 6px; }
@media (max-width:600px) { .learning-summary { padding:16px 12px; }.summary-stats { gap:5px; }.summary-stats div { padding:8px 5px; } }
</style>
