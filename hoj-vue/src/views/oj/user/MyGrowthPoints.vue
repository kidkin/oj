<template>
  <el-card class="points-page" shadow="never" v-loading="loading">
    <h1>{{ account ? '账号积分' : '我的积分' }}</h1>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-else-if="wallet">
      <p v-if="account">{{ wallet.username }}</p>
      <div class="points-balance"><strong>{{ wallet.balance }}</strong><span>可用积分</span></div>
      <dl>
        <div><dt>累计获得</dt><dd>{{ wallet.earned }}</dd></div>
        <div><dt>已消耗</dt><dd>{{ wallet.spent }}</dd></div>
      </dl>
      <p>积分与成长分按同一规则获得。积分消耗独立记录。</p>
    </template>
  </el-card>
</template>

<script>
import axios from 'axios'

export default {
  name: 'MyGrowthPoints',
  data: () => ({wallet: null, error: '', loading: false, requestId: 0}),
  computed: {
    account () { return typeof this.$route.query.uid === 'string' ? this.$route.query.uid : '' }
  },
  watch: {account: 'load'},
  mounted () { this.load() },
  methods: {
    async load () {
      const requestId = ++this.requestId
      this.wallet = null
      this.error = ''
      this.loading = false
      if (this.account && !this.$store.getters.isSuperAdmin) {
        this.error = '你只能查看自己的积分'
        return
      }
      this.loading = true
      const endpoint = this.account ? '/api/admin/growth-points/' + encodeURIComponent(this.account) : '/api/my-growth-points'
      try {
        const {data} = await axios.get(endpoint)
        if (data.status !== 200 || !data.data) throw new Error('Points unavailable')
        if (requestId === this.requestId) this.wallet = data.data
      } catch (_) {
        if (requestId === this.requestId) this.error = '暂时无法加载积分'
      } finally {
        if (requestId === this.requestId) this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.points-page { max-width: 650px; margin: 0 auto; }
.points-page h1 { margin: 0 0 14px; }
.points-page p, .points-page dt { color: #526579; }
.points-balance { display: flex; align-items: baseline; gap: 12px; margin: 14px 0 20px; }
.points-balance strong { color: #164dcc; font-size: 38px; }
.points-page dl { display: flex; gap: 36px; }
.points-page dd { margin: 4px 0 0; font-weight: 600; }
</style>
