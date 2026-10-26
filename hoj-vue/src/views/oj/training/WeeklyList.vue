<template>
  <main class="edu-page weekly-page" :style="eduPageStyle">
    <header class="page-header"><div><span class="weekly-eyebrow">WEEKLY PRACTICE</span><h2>周周练</h2></div><el-button v-if="isSuperAdmin" @click="$router.push({name:'admin-training-list'})" icon="el-icon-setting">管理周练</el-button></header>
    <el-form class="weekly-search" inline @submit.native.prevent="search"><el-form-item><el-input v-model.trim="keyword" maxlength="128" placeholder="搜索周练名称" clearable @clear="search" prefix-icon="el-icon-search" /></el-form-item><el-form-item><el-button native-type="submit">搜索</el-button></el-form-item><el-button v-if="queryKeyword || currentPage !== 1" type="text" @click="showLatest">返回最新周练</el-button></el-form>
    <div v-loading="loading" class="weekly-results">
      <div v-if="error" class="weekly-empty"><i class="el-icon-warning-outline" /><h3>周练暂时加载失败</h3><el-button @click="load">重新加载</el-button></div>
      <template v-else>
        <router-link v-if="featured" :to="'/training/' + featured.id" class="weekly-featured">
          <div class="weekly-calendar"><i class="el-icon-date" /><span>{{ date(featured.publishTime).slice(5) }}</span><small>{{ date(featured.publishTime).slice(0,4) }}</small></div>
          <div class="weekly-feature-body"><div class="weekly-kicker"><span class="weekly-current-tag">{{ currentWeek(featured) ? '本周周练' : '最近一期' }}</span><span>{{ featured.problemCount }} 道题</span></div><h3>{{ featured.title }}</h3><div v-if="isAuthenticated" class="weekly-feature-progress"><LearningProgress :completed="featured.acCount || 0" :total="featured.problemCount" /></div></div>
          <span class="weekly-enter">进入练习 <i class="el-icon-right" /></span>
        </router-link>
        <div v-if="remaining.length" class="weekly-section-heading"><h3>{{ queryKeyword ? '搜索结果' : featured ? '更多周练' : '往期周练' }}</h3><span>{{ total }} 期</span></div>
        <div class="weekly-list">
          <router-link v-for="item in remaining" :key="item.id" :to="'/training/' + item.id" class="weekly-item">
            <div class="weekly-item-date"><strong>{{ date(item.publishTime).slice(5) }}</strong><span>{{ date(item.publishTime).slice(0,4) }}</span></div>
            <div class="weekly-item-main"><div class="weekly-item-title"><h3>{{ item.title }}</h3><span v-if="currentWeek(item)" class="weekly-current-tag">本周</span></div><span class="weekly-count">{{ item.problemCount }} 道题<template v-if="isAuthenticated"> · 已完成 {{ item.acCount || 0 }} 题</template></span></div>
            <div v-if="isAuthenticated" class="weekly-item-progress"><el-progress :percentage="percent(item)" :stroke-width="6" :show-text="false" /><span>{{ percent(item) }}%</span></div><i class="el-icon-arrow-right weekly-arrow" />
          </router-link>
        </div>
        <div v-if="!loading && !records.length" class="weekly-empty"><i class="el-icon-notebook-2" /><h3>{{ queryKeyword ? '没有找到符合条件的周练' : '本周练习，敬请期待' }}</h3><el-button v-if="queryKeyword" @click="showLatest">查看全部周练</el-button></div>
      </template>
    </div>
    <el-pagination v-if="total > limit" class="pagination" layout="prev, pager, next" :current-page="currentPage" :page-size="limit" :total="total" @current-change="changePage" />
  </main>
</template>
<script>
import api from '@/common/api'
import eduLayout from '@/common/edu-layout'
import LearningProgress from '@/views/oj/edu/LearningProgress.vue'
import {weeklyDate,isCurrentWeek,weeklyPercent} from '@/common/weekly'
import {mapGetters} from 'vuex'
export default {
  mixins:[eduLayout], components:{LearningProgress},
  data:()=>({keyword:'',records:[],total:0,limit:12,loading:false,error:false,version:0}),
  computed:{...mapGetters(['isAuthenticated','isSuperAdmin']),currentPage () { const n=Number(this.$route.query.page); return Number.isInteger(n) && n>0 && n<100000 ? n : 1 },queryKeyword () { return String(this.$route.query.keyword || '').slice(0,128) },featured () { return !this.queryKeyword && this.currentPage===1 && this.records.length ? this.records[0] : null },remaining () { return this.featured ? this.records.slice(1) : this.records }},
  watch:{'$route.query' () { this.load() },isAuthenticated () { this.load() }},
  mounted () { this.load() },beforeDestroy () { this.version++ },
  methods:{date:weeklyDate,currentWeek:isCurrentWeek,percent:weeklyPercent,
    async load () { const version=++this.version; this.keyword=this.queryKeyword; this.loading=true; this.error=false; try { const res=await api.getTrainingList(this.currentPage,this.limit,{keyword:this.queryKeyword,auth:'Public'}); if(version===this.version){this.records=res.data.data.records; this.total=res.data.data.total} } catch (_) { if(version===this.version){this.records=[];this.total=0;this.error=true} } finally { if(version===this.version)this.loading=false } },
    search () { this.navigate(1,this.keyword) },changePage (page) { this.navigate(page,this.queryKeyword) },showLatest () { this.navigate(1,'') },
    navigate (page,keyword) { const query={}; if(page>1)query.page=String(page); if(keyword)query.keyword=keyword; if(JSON.stringify(query)===JSON.stringify(this.$route.query)){this.load();return}; this.$router.push({path:'/training',query}).catch(()=>{}) }
  }
}
</script>
<style scoped>
.weekly-page { max-width:1440px; margin-left:auto; margin-right:auto; }.weekly-eyebrow { display:block; margin-bottom:7px; color:#4e89b9; font-size:11px; font-weight:600; letter-spacing:2px; }.weekly-page h2 { font-size:30px; color:#293f54; }.weekly-search { display:flex; flex-wrap:wrap; gap:0 10px; margin:24px 0 8px; }.weekly-search .el-input { width:min(340px,65vw); }.weekly-results { min-height:260px; }
.weekly-featured { display:flex; align-items:center; gap:28px; padding:30px; border:1px solid #c5ddee; border-radius:16px; color:#29455d; background:linear-gradient(110deg,#e7f3ff,#f8fcff 72%); text-decoration:none; box-shadow:0 8px 24px rgba(58,104,145,.06); transition:box-shadow .18s,transform .18s; }.weekly-featured:hover { box-shadow:0 12px 30px rgba(58,104,145,.13); transform:translateY(-2px); }.weekly-featured:focus-visible,.weekly-item:focus-visible { outline:3px solid #409eff; outline-offset:3px; }.weekly-calendar { display:flex; flex:0 0 112px; flex-direction:column; align-items:center; gap:8px; color:#4385b9; border-right:1px solid #cfe0ed; padding-right:26px; }.weekly-calendar i { font-size:30px; }.weekly-calendar>span { font-size:26px; font-weight:600; }.weekly-calendar small { font-size:12px; }.weekly-feature-body { flex:1; min-width:0; }.weekly-kicker { display:flex; align-items:center; flex-wrap:wrap; gap:14px; font-size:13px; color:#677f92; }.weekly-current-tag { display:inline-block; padding:4px 9px; border-radius:5px; background:#3f8fc9; color:#fff; font-size:12px; white-space:nowrap; }.weekly-feature-body h3 { font-size:26px; line-height:1.5; margin:14px 0 22px; overflow-wrap:anywhere; }.weekly-feature-progress { max-width:540px; }.weekly-enter { display:flex; align-items:center; gap:8px; font-size:15px; white-space:nowrap; color:#347fb7; }
.weekly-section-heading { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:36px 0 16px; }.weekly-section-heading h3 { margin:0; color:#344e65; font-size:18px; }.weekly-section-heading>span { font-size:13px; color:#7a8d9e; }.weekly-list { display:flex; flex-direction:column; gap:12px; }.weekly-item { display:flex; align-items:center; gap:24px; padding:22px 26px; background:#fff; color:#344e65; border:1px solid #e0e8ef; border-radius:12px; text-decoration:none; transition:border-color .15s,box-shadow .15s; }.weekly-item:hover { border-color:#8db8da; box-shadow:0 4px 16px rgba(60,105,145,.07); }.weekly-item-date { display:flex; flex:0 0 75px; flex-direction:column; gap:5px; }.weekly-item-date strong { font-size:20px; font-weight:500; }.weekly-item-date span { font-size:12px; color:#8a9aa7; }.weekly-item-main { flex:1; min-width:0; }.weekly-item-title { display:flex; align-items:center; flex-wrap:wrap; gap:10px; }.weekly-item h3 { margin:0 0 8px; font-size:17px; font-weight:500; overflow-wrap:anywhere; }.weekly-count { font-size:13px; color:#7a8d9d; }.weekly-item-progress { display:flex; align-items:center; gap:14px; width:180px; color:#5c7d96; font-size:13px; }.weekly-item-progress .el-progress { flex:1; }.weekly-arrow { color:#9eb0bf; }.weekly-empty { padding:64px 12px; text-align:center; color:#6e889d; }.weekly-empty>i { font-size:48px; color:#98b4ca; }.weekly-empty h3 { font-size:18px; font-weight:500; margin:20px 0; }
@media(max-width:700px){.weekly-featured { flex-wrap:wrap; gap:18px; padding:22px 18px; }.weekly-calendar { flex-basis:72px; padding-right:16px; }.weekly-calendar>span { font-size:22px; }.weekly-feature-body h3 { font-size:20px; }.weekly-enter { margin-left:auto; }.weekly-item { gap:14px; padding:18px 14px; flex-wrap:wrap; }.weekly-item-date { flex-basis:62px; }.weekly-item-progress { width:calc(100% - 96px); margin-left:76px; }.weekly-arrow { display:none; }}
</style>
