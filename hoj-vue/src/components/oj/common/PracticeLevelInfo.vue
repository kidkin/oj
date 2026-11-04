<template>
  <div v-if="level" class="practice-level-info">
    <el-popover placement="bottom" width="270" trigger="hover">
      <div class="practice-level-rules"><strong>成长等级</strong><p>每通过一道不同题目获得 1 分，重复通过不叠加。</p><div v-for="rule in rules" :key="rule.key" class="practice-rule"><span :style="{color:rule.color}">{{ rule.title }}</span><span>{{ rule.minimum }} 分起</span></div></div>
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
</style>
