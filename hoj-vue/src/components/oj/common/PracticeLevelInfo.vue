<template>
  <div v-if="level" class="practice-level-info">
    <span v-if="level.key === 'staff'" class="practice-level-pill staff-level">
      <i class="el-icon-user-solid" />工作人员<span class="practice-points">不参与评级</span>
    </span>
    <span v-else-if="level.accountType === 'trial'" class="practice-level-pill trial-level">
      <i class="el-icon-user" />试用账号<span class="practice-points">成长分 0</span>
    </span>
    <el-popover v-else placement="bottom" width="270" trigger="hover">
      <div class="practice-level-rules"><strong>成长等级</strong><p>成长分按题目难度和课程、周周练、比赛系数计算。同题只取最高一次。</p><div v-for="rule in rules" :key="rule.key" class="practice-rule"><span :style="{color:rule.color}">{{ rule.title }}</span><span>{{ rule.minimum }} 分{{ rule.topPercent ? '且前 ' + rule.topPercent + '%' : '' }}</span></div></div>
      <span slot="reference" class="practice-level-pill" :style="{color:level.color}"><i class="el-icon-medal" />{{ level.title }}<span class="practice-points">成长分 {{ level.score }}</span><i class="el-icon-info" /></span>
    </el-popover>
  </div>
</template>
<script>
import axios from 'axios'
import {practiceLevel} from '@/common/practice-user'
export default {
  props: {uid: [String, Number], username: String},
  data: () => ({rules: []}),
  computed: {level () { return practiceLevel({uid:this.uid, username:this.username}) }},
  async mounted () { try { const response = await axios.get('/api/practice-level-rules'); this.rules = response.data.data || [] } catch (_) {} }
}
</script>
<style scoped>
.practice-level-info { margin:12px 0 18px; }.practice-level-pill { display:inline-flex; align-items:center; gap:7px; padding:8px 13px; border:1px solid currentColor; border-radius:20px; background:#fff; font-weight:600; cursor:help; }.practice-points { margin-left:6px; color:#526579; font-weight:400; }.practice-level-rules p { font-size:12px; line-height:1.7; color:#607080; }.practice-rule { display:flex; justify-content:space-between; margin-top:8px; }.practice-rule>span:first-child { font-weight:650; }
.trial-level { color:#777; cursor:default; }
.staff-level { color:#000; cursor:default; }
</style>
