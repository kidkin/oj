<template>
  <section class="edu-page weekly-detail" :style="eduPageStyle">
    <div class="edu-breadcrumb"><router-link to="/training">周周练</router-link><span>/</span><strong>{{ training.title }}</strong></div>
    <template v-if="!showProblem">
      <header class="weekly-detail-header"><div><el-tag size="small" :type="currentWeek(training) ? '' : 'info'">{{ currentWeek(training) ? '本周周练' : '往期周练' }}</el-tag><span class="weekly-published">{{ date(training.publishTime) }}</span><h2>{{ training.title }}</h2></div><el-button v-if="isSuperAdmin" @click="$router.push({name:'admin-edit-training',params:{trainingId:training.id}})">编辑周练</el-button></header>
      <Markdown v-if="training.description" :content="training.description" :is-avoid-xss="true" class="weekly-description" />
      <div v-if="isAuthenticated" class="weekly-detail-progress"><LearningProgress :completed="training.acCount || 0" :total="training.problemCount || 0" /></div>
      <div class="weekly-problems-heading"><h3>练习题目</h3><span>{{ training.problemCount || 0 }} 道题</span></div>
      <TrainingProblemList v-if="training.id" :key="training.id" />
    </template>
    <template v-else><router-link class="back-weekly-problems" :to="'/training/' + training.id">← 返回本期题目</router-link><router-view /></template>
  </section>
</template>
<script>
import eduLayout from '@/common/edu-layout'
import {weeklyDate,isCurrentWeek} from '@/common/weekly'
import {mapGetters} from 'vuex'
import Markdown from '@/components/oj/common/Markdown'
import LearningProgress from '@/views/oj/edu/LearningProgress.vue'
import TrainingProblemList from './TrainingProblemList.vue'
export default {
  mixins:[eduLayout],components:{Markdown,LearningProgress,TrainingProblemList},props:{training:{type:Object,required:true}},
  computed:{...mapGetters(['isAuthenticated','isSuperAdmin']),showProblem () { return this.$route.name==='TrainingProblemDetails' || this.$route.name==='TrainingRank' }},
  methods:{date:weeklyDate,currentWeek:isCurrentWeek}
}
</script>
<style scoped>
.weekly-detail-header { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:20px; margin:28px 0 22px; }.weekly-published { color:#7b8e9e; font-size:13px; margin-left:14px; }.weekly-detail-header h2 { margin-top:14px; font-size:27px; }.weekly-description { background:#fff; padding:18px 24px; border-radius:10px; border-left:4px solid #8fb9da; }.weekly-detail-progress { max-width:720px; margin:28px 0 36px; }.weekly-problems-heading { display:flex; align-items:center; justify-content:space-between; margin:28px 0 20px; }.weekly-problems-heading h3 { margin:0; font-size:19px; }.weekly-problems-heading span { color:#8192a0; font-size:13px; }.back-weekly-problems { display:block; margin:24px 0; }
</style>
