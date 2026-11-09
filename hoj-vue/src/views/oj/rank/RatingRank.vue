<template>
  <el-card class="rating-page" shadow="never">
    <div class="rating-heading">
      <div>
        <h1>Rating 榜</h1>
        <p v-if="formalCount">{{ formalCount }} 个正式账号参与颜色名额计算</p>
      </div>
      <el-input v-model="keyword" clearable maxlength="100" placeholder="搜索用户名"
        @keyup.enter.native="search" @clear="search">
        <el-button slot="append" icon="el-icon-search" aria-label="搜索" @click="search" />
      </el-input>
    </div>

    <el-collapse v-if="multipliers" class="rating-rules">
      <el-collapse-item title="成长分与颜色规则" name="rules">
        <p>每道通过的题按难度计基础分，再乘对应系数。同一道题只取最高一次。</p>
        <p>普通题 ×{{ multipliers.normal }}，本班课程 ×{{ multipliers.course }}，周周练 ×{{ multipliers.weekly }}，正式比赛 ×{{ multipliers.contest }}。</p>
        <div class="rating-rule-list">
          <el-tag v-for="(points, difficulty) in basePoints" :key="difficulty" size="small" effect="plain"
            :style="{color: difficultyColor(difficulty)}">{{ difficultyName(difficulty) }} {{ points }} 分</el-tag>
        </div>
        <div class="rating-rule-list">
          <el-tag v-for="rule in rules" :key="rule.key" size="small" effect="plain" :style="{color: rule.color}">
            {{ rule.title }} · {{ rule.minimum }} 分{{ rule.topPercent ? '且前 ' + rule.topPercent + '%' : '' }}
          </el-tag>
        </div>
      </el-collapse-item>
    </el-collapse>

    <el-alert v-if="error" title="暂时无法加载 Rating 榜" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="records" stripe row-key="uid" empty-text="暂无上榜记录">
      <el-table-column prop="rank" label="排名" width="68" align="center" />
      <el-table-column label="用户" min-width="150">
        <template slot-scope="{row}">
          <router-link :to="{name:'UserHome', query:{uid:row.uid, username:row.username}}">
            <span v-user-level="row">{{ row.username }}</span>
          </router-link>
        </template>
      </el-table-column>
      <el-table-column prop="solvedCount" label="计分题数" min-width="88" align="right" />
      <el-table-column prop="score" label="成长分" min-width="85" align="right" />
      <el-table-column label="等级" min-width="88" align="center">
        <template slot-scope="{row}"><strong :style="{color:row.color}">{{ row.title }}</strong></template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > limit" class="rating-pagination" :current-page="page" :page-size="limit"
      :total="total" layout="prev, pager, next" @current-change="changePage" />
  </el-card>
</template>

<script>
import axios from 'axios'
import { PROBLEM_LEVEL } from '@/common/constants'

export default {
  name: 'RatingRank',
  data: () => ({
    records: [], rules: [], basePoints: [], multipliers: null, formalCount: 0, total: 0,
    page: 1, limit: 20, keyword: '', submittedKeyword: '', loading: false, error: false, requestId: 0
  }),
  mounted () { this.load() },
  methods: {
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
        this.total = data.total
        this.formalCount = data.formalCount
        this.rules = data.rules
        this.basePoints = data.basePoints
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
.rating-page { max-width: 1100px; margin: 0 auto; }
.rating-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 14px; margin-bottom: 14px; }
.rating-heading h1 { margin: 0; font-size: 24px; }
.rating-heading p { margin: 6px 0 0; color: #526579; }
.rating-heading .el-input { width: min(100%, 270px); }
.rating-rules { margin-bottom: 14px; }
.rating-rule-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.rating-pagination { text-align: right; margin-top: 14px; }
</style>
