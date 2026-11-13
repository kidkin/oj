<template>
  <div class="home-page">
    <LearningSummary v-if="footprint && footprint.enabled" :key="token" :summary="footprint" />
    <el-row :gutter="24">
      <el-col :md="15" :sm="24" :xs="24">
        <el-card class="home-carousel-card" :body-style="{padding:'12px'}">
          <el-carousel :interval="5000" height="clamp(240px, 36vw, 500px)" arrow="always" indicator-position="outside">
            <el-carousel-item v-for="(item,index) in carouselImgList" :key="item.id || index">
              <el-image :src="item.url" fit="contain" class="home-carousel-image"><div slot="error" class="carousel-fallback"><i class="el-icon-picture-outline" /></div></el-image>
            </el-carousel-item>
          </el-carousel>
        </el-card>
      </el-col>
      <el-col :md="9" :sm="24" :xs="24" class="home-side-column">
        <section class="weekly-rank" aria-labelledby="weekly-rank-title" v-loading="rankLoading && !rankLoaded">
          <header class="section-heading rank-heading">
            <div class="heading-title"><span class="heading-emblem"><i class="el-icon-trophy" /></span><h2 id="weekly-rank-title">本周排行榜</h2></div>
            <span class="live-label" title="北京时间每周一零点开始统计，每分钟自动更新"><span />实时更新</span>
          </header>
          <div class="rank-caption"><span>每一份努力，都值得闪耀</span><span>过题数</span></div>
          <el-alert v-if="rankError" class="home-alert" title="榜单更新失败，稍后自动重试" type="warning" :closable="false" show-icon />
          <ol v-if="weeklyRank.length" class="rank-list">
            <li v-for="(row,index) in weeklyRank" :key="row.uid" class="rank-row" :class="'place-' + (index + 1)">
              <router-link class="rank-link" :to="{path:'/user-home',query:{uid:row.uid,username:row.username}}">
                <span v-if="index < 3" class="medal" :aria-label="'第' + (index+1) + '名'">
                  <svg viewBox="0 0 40 44" aria-hidden="true"><path class="medal-ribbon" d="M9 5h8l3 9 3-9h8l-6 17H15z" /><path class="medal-shield" d="M20 10l14 6v11c0 7-8 12-14 15C14 39 6 34 6 27V16z" /><path class="medal-star" d="m20 15 2.3 4.8 5.3.8-3.8 3.7.9 5.2-4.7-2.5-4.7 2.5.9-5.2-3.8-3.7 5.3-.8z" /></svg><span>{{ index + 1 }}</span>
                </span>
                <span v-else class="rank-number">{{ String(index+1).padStart(2,'0') }}</span>
                <Avatar :username="row.username" :inline="true" :size="index < 3 ? 31 : 26" color="#fff" :src="row.avatar" />
                <span class="rank-name" :title="row.username"><span v-user-level="{uid: row.uid, username: row.username}">{{ row.username }}</span></span>
                <span class="rank-score">{{ row.ac }}<small>题</small></span>
              </router-link>
            </li>
          </ol>
          <div v-else-if="rankLoaded && !rankError" class="home-empty"><i class="el-icon-trophy" /><span>本周榜单，等你上场</span></div>
        </section>
        <section class="recent-contests" aria-labelledby="recent-contest-title" v-loading="contestLoading && !contestLoaded">
          <header class="section-heading"><div class="heading-title"><span class="contest-emblem"><i class="el-icon-date" /></span><h2 id="recent-contest-title">最近比赛</h2></div><router-link to="/contest" class="view-all">全部<i class="el-icon-arrow-right" /></router-link></header>
          <el-alert v-if="contestError" class="home-alert" title="比赛更新失败，稍后自动重试" type="warning" :closable="false" show-icon />
          <div v-if="recentContests.length" class="contest-list">
            <router-link v-for="contest in recentContests" :key="contest.id" class="contest-entry" :to="'/contest/' + contest.id">
              <div class="contest-entry-top"><span class="contest-status" :class="{'is-live':contest.status===0}"><i />{{ contest.status===0 ? '进行中' : '即将开始' }}</span><span class="contest-type">{{ contest.type===0 ? 'ACM' : 'OI' }}</span></div>
              <h3>{{ contest.title }}</h3><div class="contest-time"><i class="el-icon-time" /><span>{{ formatContestTime(contest.startTime) }}</span><i class="el-icon-arrow-right contest-arrow" /></div>
            </router-link>
          </div>
          <div v-else-if="contestLoaded && !contestError" class="home-empty contest-empty"><i class="el-icon-date" /><span>近期暂无比赛</span><router-link to="/contest">查看全部比赛<i class="el-icon-arrow-right" /></router-link></div>
        </section>
      </el-col>
    </el-row>
  </div>
</template>
<script>
import api from '@/common/api'
import Avatar from 'vue-avatar'
import edu from '@/common/edu-api'
import LearningSummary from './edu/LearningSummary.vue'
import moment from 'moment'
export default {
  name: 'home', components: { Avatar, LearningSummary },
  data: () => ({ footprint: null, carouselImgList: [{url: require('@/assets/home1.jpeg')}], weeklyRank: [], recentContests: [], rankLoading: false, rankLoaded: false, rankError: false, contestLoading: false, contestLoaded: false, contestError: false, refreshTimer: null, refreshActive: false }),
  computed: { token () { return this.$store.getters.token } },
  watch: {token: {immediate: true, handler: 'loadFootprint'}},
  mounted () {
    this.getHomeCarousel(); this.refreshHome()
    this.refreshTimer = window.setInterval(this.refreshHome, 60000)
    document.addEventListener('visibilitychange', this.onVisibilityChange)
    window.addEventListener('focus', this.onVisibilityChange)
  },
  beforeDestroy () {
    window.clearInterval(this.refreshTimer)
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    window.removeEventListener('focus', this.onVisibilityChange)
  },
  methods: {
    async loadFootprint (token) {
      this.footprint = null
      if (!token) return
      try {
        const result = await edu.learningFootprint()
        if (token === this.token) this.footprint = result
      } catch (_) {}
    },
    async getHomeCarousel () {
      try { const res = await api.getHomeCarousel(); if (Array.isArray(res.data.data) && res.data.data.length) this.carouselImgList = res.data.data }
      catch (_) {}
    },
    onVisibilityChange () { if (!document.hidden) this.refreshHome() },
    async refreshHome () {
      if (document.hidden || this.refreshActive) return
      this.refreshActive = true
      try { await Promise.all([this.getWeeklyRank(), this.getRecentContests()]) }
      finally { this.refreshActive = false }
    },
    async getWeeklyRank () {
      this.rankLoading = true
      try { const res = await api.getCurrentWeekACRank(); if (!Array.isArray(res.data.data)) throw new Error('Invalid ranking'); this.weeklyRank = res.data.data.slice(0, 10); this.rankError = false }
      catch (_) { this.rankError = true }
      finally { this.rankLoading = false; this.rankLoaded = true }
    },
    async getRecentContests () {
      this.contestLoading = true
      try {
        const res = await api.getRecentContests()
        if (!Array.isArray(res.data.data)) throw new Error('Invalid contests')
        this.recentContests = res.data.data.filter(row => row.status === 0 || row.status === -1).sort((a, b) => {
          if (a.status !== b.status) return a.status === 0 ? -1 : 1
          const delta = new Date(a.startTime) - new Date(b.startTime)
          return (a.status === 0 ? -delta : delta) || a.id - b.id
        }).slice(0, 3)
        this.contestError = false
      } catch (_) { this.contestError = true }
      finally { this.contestLoading = false; this.contestLoaded = true }
    },
    formatContestTime (value) { return moment(value).utcOffset(8).format('MM月DD日 HH:mm') }
  }
}
</script>
<style scoped>
.home-page { width:100%; }.home-carousel-card { border-radius:16px; }.home-carousel-image { width:100%; height:100%; display:block; }
.carousel-fallback { height:100%; display:flex; align-items:center; justify-content:center; background:#edf4fa; color:#9ab4c9; font-size:44px; }
.weekly-rank,.recent-contests { overflow:hidden; background:#fff; border:1px solid #e5edf4; border-radius:16px; box-shadow:0 6px 22px #294d6d0b; }
.section-heading { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:17px 20px 14px; }.rank-heading { background:linear-gradient(110deg,#f5f9ff,#fffaf0); padding-bottom:16px; }
.heading-title { display:flex; align-items:center; gap:10px; }.heading-title h2 { margin:0; font-size:18px; font-weight:700; color:#2c435b; }
.heading-emblem,.contest-emblem { width:32px; height:32px; display:inline-flex; align-items:center; justify-content:center; border-radius:10px; font-size:20px; }.heading-emblem { color:#c18b20; background:linear-gradient(135deg,#fff5d6,#ffe9a5); box-shadow:0 3px 9px #c3982e20; }.contest-emblem { color:#4885d0; background:#eaf3ff; }
.live-label { display:inline-flex; align-items:center; gap:5px; font-size:11px; white-space:nowrap; color:#5a9183; }.live-label>span,.contest-status>i { width:5px; height:5px; border-radius:50%; background:currentColor; }.live-label>span { box-shadow:0 0 0 3px #57b59a12; }
.rank-caption { display:flex; justify-content:space-between; padding:11px 23px 8px; color:#8a9aaa; font-size:11px; }.rank-list { padding:0 12px 12px; margin:0; list-style:none; }.rank-row { margin:2px 0; border-radius:9px; }
.rank-link { display:flex; align-items:center; gap:10px; padding:5px 10px; min-height:35px; color:#596d83; border-radius:9px; transition:background .2s,transform .2s; }.rank-link:hover { background:#f0f6fc; transform:translateX(2px); }.rank-link:focus-visible,.contest-entry:focus-visible { outline:2px solid #368bd4; outline-offset:-2px; }
.rank-number { width:32px; text-align:center; font-size:12px; color:#9aabba; font-variant-numeric:tabular-nums; }.rank-name { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }.rank-score { margin-left:auto; font-size:17px; font-weight:700; font-variant-numeric:tabular-nums; white-space:nowrap; }.rank-score small { font-size:10px; font-weight:400; padding-left:4px; opacity:.65; }
.place-1 { --medal:#e2aa31; --ribbon:#e0ad48; --name:#a36b0b; background:linear-gradient(100deg,#fff6dd,#fffdf6); }.place-2 { --medal:#8ca7bf; --ribbon:#8eabc5; --name:#55758f; background:linear-gradient(100deg,#eef4fb,#fafcfe); }.place-3 { --medal:#cf8b5d; --ribbon:#ce986a; --name:#a06237; background:linear-gradient(100deg,#fdf0e7,#fffaf6); }
.place-1 .rank-link,.place-2 .rank-link,.place-3 .rank-link { min-height:43px; }.place-1 .rank-score,.place-2 .rank-score,.place-3 .rank-score { color:var(--name); }
.medal { position:relative; display:inline-block; width:32px; height:36px; flex-shrink:0; filter:drop-shadow(0 2px 2px #b47b2518); }.medal svg { width:32px; height:36px; }.medal-ribbon { fill:var(--ribbon); opacity:.5; }.medal-shield { fill:var(--medal); stroke:#fff; stroke-width:1.2; }.medal-star { fill:#fff; opacity:.3; }.medal>span { position:absolute; inset:11px 0 0; color:#fff; text-align:center; font-weight:800; font-size:13px; line-height:20px; }.place-1 .medal { animation:champion-glow 4s ease-in-out infinite; }
@keyframes champion-glow { 0%,100% { filter:drop-shadow(0 2px 2px #b47b2525); } 50% { filter:drop-shadow(0 0 5px #e5ad4e80); } }
.recent-contests { margin-top:18px; }.view-all { color:#8a9bac; font-size:12px; white-space:nowrap; }.view-all i { margin-left:2px; }.contest-list { padding:0 18px 6px; }.contest-entry { display:block; padding:12px 2px; border-top:1px solid #eef3f8; transition:background .2s; }.contest-entry:hover { background:#f8fbff; }.contest-entry-top { display:flex; align-items:center; justify-content:space-between; }.contest-status { display:inline-flex; align-items:center; gap:5px; color:#5586be; font-size:10px; }.contest-status.is-live { color:#2c9c82; }.contest-type { font-size:10px; letter-spacing:1px; color:#8c9aac; }.contest-entry h3 { color:#3b5069; margin:6px 0; font-size:14px; font-weight:600; overflow-wrap:anywhere; }.contest-time { display:flex; align-items:center; gap:5px; color:#95a3b3; font-size:11px; }.contest-arrow { margin-left:auto; }
.home-empty { padding:25px 16px; display:flex; flex-direction:column; gap:8px; align-items:center; color:#98a9bb; font-size:13px; }.home-empty>i { font-size:30px; color:#cbd9e6; }.contest-empty a { font-size:12px; }.home-alert { margin:5px 12px; width:calc(100% - 24px); }
@media(max-width:991px) { .home-side-column { margin-top:22px; } }@media(prefers-reduced-motion:reduce) { .place-1 .medal { animation:none; }.rank-link { transition:none; } }
</style>
