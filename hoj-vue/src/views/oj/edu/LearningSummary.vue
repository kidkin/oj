<template>
  <section class="learning-summary" aria-label="我的学习概览" v-loading="loading">
    <div class="summary-heading"><span class="eyebrow">每一道题，都是新的进步</span><h2>我的学习足迹</h2></div>
    <div v-if="failed" class="summary-error">学习概览暂时无法加载 <el-button type="text" @click="$emit('retry')">重试</el-button></div>
    <template v-else-if="summary">
      <div class="summary-stats">
        <div class="summary-stat"><i class="el-icon-sunny" aria-hidden="true" /><div><el-tooltip content="从第一次提交到今天的自然天数，首次提交当天算第1天；没有提交则为0天" placement="top"><span>已学习</span></el-tooltip><strong>{{ summary.learningDays }}<small>天</small></strong></div></div>
        <div class="summary-stat"><i class="el-icon-reading" aria-hidden="true" /><div><span>已加入课程</span><strong>{{ summary.courseCount }}<small>门</small></strong></div></div>
        <div class="summary-stat"><i class="el-icon-circle-check" aria-hidden="true" /><div><span>已完成题目</span><strong>{{ summary.completedCount }}<small>道</small></strong></div></div>
      </div>
      <div class="summary-progress"><div class="progress-label"><span>课程总进度</span><b>{{ summary.completedCount }} / {{ summary.problemCount }} 道</b></div><el-progress :percentage="percentage" :stroke-width="12" :show-text="false" color="#2ca894" /><div class="progress-foot"><span>{{ summary.problemCount ? '继续积累，稳步向前' : '加入课程后，开始记录你的进步' }}</span><strong>{{ percentage }}%</strong></div></div>
    </template>
  </section>
</template>
<script>
export default {
  props: {summary: {type:Object, default:null}, loading:Boolean, failed:Boolean},
  computed: {percentage () { return this.summary && this.summary.problemCount ? Math.min(100, Math.round(this.summary.completedCount / this.summary.problemCount * 100)) : 0 }}
}
</script>
<style scoped>
.learning-summary { margin:4px 0 32px; padding:30px 36px; border-radius:20px; border:1px solid #daebe9; background:linear-gradient(120deg,#f5fcfa,#fff 66%,#eff5ff); box-shadow:0 8px 28px #194f4a08; min-height:220px; color:#263d48; }.eyebrow { font-size:13px; color:#438379; letter-spacing:1px; }.summary-heading h2 { margin:8px 0 24px; font-size:26px; }.summary-stats { display:grid; grid-template-columns:repeat(3,1fr); gap:28px; }.summary-stat { display:flex; align-items:center; gap:14px; }.summary-stat>i { padding:14px; border-radius:16px; background:#e0f3ec; color:#208a74; font-size:26px; }.summary-stat:nth-child(2)>i { background:#e7eefb; color:#5b80bb; }.summary-stat:nth-child(3)>i { background:#fff0d9; color:#bf8d33; }.summary-stat span { display:block; font-size:14px; color:#546d77; }.summary-stat strong { display:block; font-size:32px; line-height:1.5; font-variant-numeric:tabular-nums; }.summary-stat small { font-size:13px; font-weight:400; margin-left:8px; color:#546d77; }.summary-progress { margin-top:26px; }.progress-label,.progress-foot { display:flex; justify-content:space-between; gap:12px; font-size:13px; margin-bottom:10px; }.progress-foot { margin-top:10px; margin-bottom:0; color:#6a808a; }.progress-foot strong { color:#208a74; }.summary-error { padding:20px 0; }
@media(max-width:700px) { .learning-summary { padding:22px 18px; border-radius:14px; }.summary-stats { gap:12px; }.summary-stat { display:block; }.summary-stat>i { display:inline-block; padding:10px; font-size:22px; margin-bottom:10px; }.summary-stat span { font-size:12px; }.summary-stat strong { font-size:27px; }.summary-heading h2 { font-size:23px; } }
</style>
