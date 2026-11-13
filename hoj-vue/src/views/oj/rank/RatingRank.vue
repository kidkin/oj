<template>
  <main class="rating-page" aria-labelledby="rating-title">
    <header class="rating-hero">
      <div class="hero-copy">
        <p class="eyebrow"><i class="el-icon-trophy" aria-hidden="true" /> 学习成长</p>
        <h1 id="rating-title">成长排行榜</h1>
        <p class="hero-description">按有效通过的题目累计成长分，每道题取最高计分。</p>
      </div>
      <dl v-if="multipliers" class="hero-stats">
        <div><dt>参评账号</dt><dd>{{ formalCount }}</dd></div>
        <div><dt>上榜人数</dt><dd>{{ rankedCount }}</dd></div>
      </dl>
    </header>

    <section v-if="leaders.length && !submittedKeyword" aria-labelledby="podium-title" class="rating-podium">
      <h2 id="podium-title">榜单前三</h2>
      <div class="podium-grid" :class="'podium-size-' + leaders.length">
        <article v-for="row in leaders" :key="row.uid" class="podium-card" :class="'place-' + row.rank">
          <span class="podium-medal" :aria-label="'第 ' + row.rank + ' 名'">{{ row.rank }}</span>
          <div class="podium-person">
            <router-link :to="profile(row)" class="rating-name" :title="row.username">
              {{ row.username }}
            </router-link>
            <span class="tier-pill" :style="{'--tier-color':row.color}"><i aria-hidden="true" />{{ row.title }}</span>
          </div>
          <div class="podium-score"><strong>{{ row.score }}</strong><span>成长分</span></div>
        </article>
      </div>
    </section>

    <section class="rating-board" aria-labelledby="standings-title" v-loading="loading">
      <header class="board-heading">
        <div>
          <h2 id="standings-title">排名明细</h2>
          <p v-if="submittedKeyword">找到 {{ total }} 位选手 · 保留全站排名</p>
          <p v-else>按成长分排列</p>
        </div>
        <el-input v-model="keyword" clearable maxlength="100" placeholder="搜索用户名" aria-label="搜索用户名"
          @keyup.enter.native="search" @clear="search">
          <el-button slot="append" icon="el-icon-search" aria-label="搜索" @click="search" />
        </el-input>
      </header>

      <div v-if="error" class="rating-empty" role="alert">
        <p>暂时无法加载 成长排行榜</p>
        <el-button size="small" @click="load">重试</el-button>
      </div>
      <div v-else-if="!records.length && !loading" class="rating-empty">
        {{ submittedKeyword ? '没有找到符合条件的用户名' : '暂无上榜记录' }}
      </div>
      <table v-else class="standings-table" aria-label="成长分排名">
        <thead>
          <tr><th scope="col">排名</th><th scope="col">选手</th><th scope="col" class="solved-col">计分题数</th><th scope="col">成长分</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in records" :key="row.uid">
            <td><span class="rank-number" :class="row.rank <= 3 ? 'rank-top rank-' + row.rank : ''">{{ row.rank }}</span></td>
            <td>
              <router-link :to="profile(row)" class="rating-name" :title="row.username">{{ row.username }}</router-link>
              <span class="tier-pill table-tier" :style="{'--tier-color':row.color}"><i aria-hidden="true" />{{ row.title }}</span>
              <span class="mobile-solved">{{ row.solvedCount }} 题</span>
            </td>
            <td class="solved-col">{{ row.solvedCount }}</td>
            <td class="score-cell">{{ row.score }}</td>
          </tr>
        </tbody>
      </table>
      <footer v-if="total > limit" class="board-pagination">
        <el-pagination :current-page="page" :page-size="limit" :pager-count="5" :total="total"
          layout="prev, pager, next" @current-change="changePage" />
      </footer>
    </section>

    <details v-if="multipliers" class="rating-rules">
      <summary>成长分与颜色规则</summary>
      <div class="rules-body">
        <p>普通题 ×{{ multipliers.normal }} · 本班课程 ×{{ multipliers.course }} · 周周练 ×{{ multipliers.weekly }} · 正式比赛 ×{{ multipliers.contest }}</p>
        <ul v-if="basePoints.length" class="rule-list" aria-label="题目基础分">
          <li v-for="(points, difficulty) in basePoints" :key="difficulty" :style="{'--tier-color':difficultyColor(difficulty)}">
            <i aria-hidden="true" />{{ difficultyName(difficulty) }} {{ points }} 分
          </li>
        </ul>
        <ul class="rule-list" aria-label="成长等级">
          <li v-for="rule in rules" :key="rule.key" :style="{'--tier-color':rule.color}">
            <i aria-hidden="true" />{{ rule.title }} {{ rule.minimum }} 分<span v-if="rule.topPercent"> · 前 {{ rule.topPercent }}%（{{ rule.seats }} 席）</span>
          </li>
        </ul>
        <p class="rule-note">管理员和教师不占名额。比例名额向下取整，有参评账号时每档至少 1 席。同分先比较计分题数，再按固定账号顺序排列。</p>
      </div>
    </details>
  </main>
</template>

<script>
import axios from 'axios'
import { PROBLEM_LEVEL } from '@/common/constants'

export default {
  name: 'RatingRank',
  data: () => ({
    records: [], leaders: [], rules: [], basePoints: [], multipliers: null, formalCount: 0, rankedCount: 0, total: 0,
    page: 1, limit: 20, keyword: '', submittedKeyword: '', loading: false, error: false, requestId: 0
  }),
  mounted () { this.load() },
  methods: {
    profile (row) { return {name: 'UserHome', query: {uid: row.uid, username: row.username}} },
    difficultyName (difficulty) {
      const names = PROBLEM_LEVEL[difficulty].name
      return names[this.$store.getters.webLanguage] || names['zh-CN']
    },
    difficultyColor (difficulty) { return PROBLEM_LEVEL[difficulty].color },
    search () {
      this.submittedKeyword = this.keyword.trim()
      this.changePage(1)
    },
    changePage (page) {
      this.page = page
      this.load()
    },
    async load () {
      const requestId = ++this.requestId
      this.loading = true
      this.error = false
      try {
        const {data: response} = await axios.get('/api/growth-rating', {
          params: {page: this.page, limit: this.limit, keyword: this.submittedKeyword}
        })
        if (response.status !== 200 || !response.data) throw new Error('Rating unavailable')
        if (requestId !== this.requestId) return
        const data = response.data
        this.records = data.records
        this.leaders = data.leaders
        this.total = data.total
        this.formalCount = data.formalCount
        this.rankedCount = data.rankedCount
        this.rules = data.rules
        this.basePoints = data.basePoints || []
        this.multipliers = data.multipliers
      } catch (_) {
        if (requestId === this.requestId) this.error = true
      } finally {
        if (requestId === this.requestId) this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.rating-page { max-width: 1110px; margin: 0 auto; color: #182638; }
.rating-page h1, .rating-page h2, .rating-page p, .rating-page dl, .rating-page dd { margin: 0; }
.rating-hero {
  display: flex; align-items: flex-end; justify-content: space-between; gap: 26px;
  padding: 34px 38px; border-radius: 22px; color: #fff;
  background: radial-gradient(ellipse at 88% 12%, #345575, transparent 52%), linear-gradient(135deg, #111d31, #203a56);
  box-shadow: 0 12px 28px rgba(19, 37, 62, .15);
}
.eyebrow { color: #ffdc92; font-size: 13px; font-weight: 700; letter-spacing: .12em; }
.eyebrow i { margin-right: 5px; }
.rating-hero h1 { font-size: clamp(34px, 6vw, 54px); line-height: 1.18; letter-spacing: -.04em; margin: 7px 0 9px; }
.hero-description { color: #d1ddeb; font-size: 14px; line-height: 1.6; }
.hero-stats { display: grid; grid-template-columns: repeat(2, minmax(88px, 1fr)); gap: 8px; }
.hero-stats > div { padding: 10px 14px; border: 1px solid rgba(227, 240, 255, .2); border-radius: 12px; background: rgba(255,255,255,.08); }
.hero-stats dt { font-size: 12px; color: #d1ddeb; white-space: nowrap; }
.hero-stats dd { font-size: 24px; font-weight: 750; font-variant-numeric: tabular-nums; }
.rating-podium { margin-top: 24px; }
.rating-page h2 { font-size: 18px; font-weight: 750; }
.podium-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 11px; align-items: end; }
.podium-size-1 { grid-template-columns: minmax(0, 1fr); }
.podium-size-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.podium-card {
  --medal-ink: #576579; --medal-bg: #edf2f9;
  display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px;
  padding: 18px 15px; border: 1px solid #dee6ef; border-radius: 16px; background: #fff;
}
.place-1 { --medal-ink: #875009; --medal-bg: #ffdf9b; border-color: #ebcb88; background: linear-gradient(138deg, #fff8e8, #fff); padding-top: 24px; padding-bottom: 24px; }
.place-2 { background: linear-gradient(138deg, #f1f5fa, #fff); }
.place-3 { --medal-ink: #8b4b29; --medal-bg: #ffe5d1; border-color: #ead9cf; background: linear-gradient(138deg, #fff4eb, #fff); }
.podium-size-3 .podium-card { grid-row: 1; }
.podium-size-3 .place-1 { grid-column: 2; }
.podium-size-3 .place-2 { grid-column: 1; }
.podium-size-3 .place-3 { grid-column: 3; }
.podium-medal { display: grid; place-items: center; width: 39px; height: 39px; border-radius: 50%; background: var(--medal-bg); color: var(--medal-ink); font-size: 18px; font-weight: 800; }
.podium-person { min-width: 0; display: flex; align-items: flex-start; flex-direction: column; gap: 5px; }
.rating-name { display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 750; vertical-align: middle; }
.rating-name:hover, .rating-name:focus-visible { text-decoration: underline; }
.tier-pill { display: inline-flex; align-items: center; gap: 5px; padding: 2px 7px; border: 1px solid #e3e9ef; border-radius: 999px; background: #fff; color: var(--tier-color); font-size: 12px; white-space: nowrap; }
.tier-pill i, .rule-list i { width: 7px; height: 7px; border-radius: 50%; background: var(--tier-color); }
.podium-score { display: grid; justify-items: end; white-space: nowrap; }
.podium-score strong { font-size: 22px; line-height: 1.1; font-variant-numeric: tabular-nums; }
.podium-score span { font-size: 12px; color: #576579; }
.rating-board { margin-top: 22px; border: 1px solid #e0e8f1; border-radius: 17px; background: #fff; overflow: hidden; }
.board-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; padding: 17px 20px; }
.board-heading p { margin-top: 4px; color: #576579; font-size: 13px; }
.board-heading .el-input { width: min(100%, 265px); }
.standings-table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.standings-table th { background: #f2f6fa; color: #405370; font-size: 13px; text-align: left; font-weight: 650; }
.standings-table th, .standings-table td { padding: 11px 16px; border-top: 1px solid #e8edf4; }
.standings-table th:first-child, .standings-table td:first-child { width: 85px; text-align: center; }
.standings-table th:last-child, .standings-table td:last-child, .standings-table .solved-col { width: 120px; text-align: right; }
.standings-table tbody tr:hover { background: #f9fbff; }
.rank-number { font-variant-numeric: tabular-nums; color: #52647a; font-weight: 700; }
.rank-top { display: inline-grid; min-width: 26px; min-height: 26px; place-items: center; border-radius: 8px; background: #edf2f9; }
.rank-1 { background: #ffedc5; color: #875009; }
.rank-3 { background: #fff0e4; color: #8b4b29; }
.score-cell { font-weight: 800; font-variant-numeric: tabular-nums; }
.table-tier { margin-left: 7px; vertical-align: middle; }
.standings-table .rating-name { max-width: calc(100% - 65px); }
.mobile-solved { display: none; }
.rating-empty { padding: 34px 12px; text-align: center; color: #52647a; }
.rating-empty button { margin-top: 8px; }
.board-pagination { padding: 13px 12px; border-top: 1px solid #e8edf4; text-align: right; }
.rating-rules { margin: 15px 0; padding: 11px 16px; border: 1px solid #e0e8f1; border-radius: 12px; background: #fff; }
.rating-rules summary { cursor: pointer; font-weight: 650; }
.rules-body { padding-top: 10px; color: #405370; font-size: 13px; line-height: 1.65; }
.rule-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0; padding: 0; list-style: none; }
.rule-list li { display: inline-flex; align-items: center; gap: 4px; padding: 3px 7px; border: 1px solid #e3e9ef; border-radius: 8px; }
.rule-note { color: #576579; }
@media (max-width: 900px) {
  .podium-card { gap: 8px; padding-left: 10px; padding-right: 10px; }
  .podium-score strong { font-size: 18px; }
}
@media (max-width: 730px) {
  .rating-hero { padding: 22px; flex-wrap: wrap; gap: 15px; border-radius: 16px; }
  .podium-grid { grid-template-columns: minmax(0, 1fr); gap: 7px; }
  .podium-size-3 .podium-card { grid-row: auto; grid-column: auto; }
  .podium-card { padding: 10px 13px; }
  .podium-person { flex-direction: row; align-items: center; gap: 7px; }
  .podium-person .rating-name { max-width: calc(100% - 62px); }
  .podium-medal { width: 31px; height: 31px; font-size: 15px; }
}
@media (max-width: 530px) {
  .board-heading { padding: 13px; }
  .board-heading .el-input { width: 100%; }
  .standings-table th, .standings-table td { padding: 8px; }
  .standings-table th:first-child, .standings-table td:first-child { width: 48px; }
  .standings-table th:last-child, .standings-table td:last-child { width: 75px; }
  .standings-table .solved-col { display: none; }
  .standings-table .rating-name { max-width: 100%; display: block; }
  .table-tier { margin: 3px 0 0; }
  .mobile-solved { display: inline; margin-left: 6px; font-size: 12px; color: #576579; }
  .board-pagination { text-align: center; }
}
</style>
