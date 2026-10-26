<template>
  <div class="learning-progress" role="group" :aria-label="'已完成 ' + completed + ' / ' + total + ' 题'">
    <div class="progress-caption"><span>已完成 <strong>{{ completed }}</strong><span class="progress-total"> / {{ total }} 题</span></span><strong class="progress-percent">{{ percentage }}%</strong></div>
    <el-progress :percentage="percentage" :show-text="false" :stroke-width="8" :color="percentage === 100 ? '#31ad76' : '#528fd2'" />
  </div>
</template>
<script>
export default {
  props: {completed:{type:Number,default:0},total:{type:Number,default:0}},
  computed: {percentage () { return this.total ? Math.min(100,Math.round(this.completed/this.total*100)) : 0 }}
}
</script>
<style scoped>
.learning-progress { width:100%; }.progress-caption { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:12px; color:#425c74; font-size:14px; }.progress-caption strong { font-size:18px; }.progress-total { color:#8192a1; }.progress-percent { color:#3d82bc; }
</style>
