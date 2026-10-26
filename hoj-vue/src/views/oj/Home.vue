<template>
  <div class="home-page">
    <el-row :gutter="24">
      <el-col :md="15" :sm="24" :xs="24">
        <el-card class="home-carousel-card" :body-style="{padding:'12px'}">
          <el-carousel :interval="5000" height="clamp(220px, 35vw, 440px)" arrow="always" indicator-position="outside" class="home-carousel">
            <el-carousel-item v-for="(item,index) in carouselImgList" :key="item.id || index">
              <el-image :src="item.url" fit="contain" class="home-carousel-image"><div slot="error" class="carousel-fallback"><i class="el-icon-picture-outline" /></div></el-image>
            </el-carousel-item>
          </el-carousel>
        </el-card>
      </el-col>
      <el-col :md="9" :sm="24" :xs="24" class="home-rank-column">
        <el-card class="home-weekly-rank">
          <div slot="header" class="home-rank-heading"><i class="el-icon-s-data" /><h2>{{ $t('m.Recent_7_Days_AC_Rank') }}</h2></div>
          <el-alert v-if="rankError" title="榜单暂时加载失败，请刷新页面重试。" type="error" :closable="false" />
          <vxe-table border="inner" stripe auto-resize align="center" :data="recentUserACRecord" :loading="rankLoading" max-height="500px" empty-text="暂无过题记录">
            <vxe-table-column title="#" width="52"><template v-slot="{rowIndex}"><span class="rank-badge" :class="'rank-' + (rowIndex+1)">{{ rowIndex+1 }}</span></template></vxe-table-column>
            <vxe-table-column field="username" :title="$t('m.Username')" min-width="180" align="left"><template v-slot="{row}"><div class="home-rank-user"><Avatar :username="row.username" :inline="true" :size="28" color="#fff" :src="row.avatar" /><router-link :to="{path:'/user-home',query:{uid:row.uid,username:row.username}}">{{ row.username }}</router-link><el-tag v-if="row.titleName" size="small" effect="dark" :color="row.titleColor">{{ row.titleName }}</el-tag></div></template></vxe-table-column>
            <vxe-table-column field="ac" :title="$t('m.AC')" width="64" />
          </vxe-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
<script>
import api from '@/common/api'
import Avatar from 'vue-avatar'
export default {
  name:'home',components:{Avatar},
  data:()=>({carouselImgList:[{url:require('@/assets/home1.jpeg')}],recentUserACRecord:[],rankLoading:false,rankError:false}),
  mounted () { this.getHomeCarousel(); this.getRecent7ACRank() },
  methods:{
    async getHomeCarousel () { try { const res=await api.getHomeCarousel(); if(Array.isArray(res.data.data) && res.data.data.length)this.carouselImgList=res.data.data } catch (_) {} },
    async getRecent7ACRank () { this.rankLoading=true; this.rankError=false; try { const res=await api.getRecent7ACRank(); this.recentUserACRecord=res.data.data || [] } catch (_) { this.rankError=true } finally { this.rankLoading=false } }
  }
}
</script>
<style scoped>
.home-page { width:100%; }.home-carousel-card,.home-weekly-rank { border-radius:12px; }.home-carousel-image { width:100%; height:100%; display:block; }.carousel-fallback { height:100%; display:flex; align-items:center; justify-content:center; background:#edf4fa; color:#9ab4c9; font-size:44px; }.home-rank-heading { display:flex; align-items:center; gap:10px; color:#355773; }.home-rank-heading>i { font-size:21px; }.home-rank-heading h2 { margin:0; font-size:18px; font-weight:600; }.home-rank-user { display:flex; align-items:center; flex-wrap:wrap; gap:8px; }.home-rank-user a { overflow-wrap:anywhere; }.rank-badge { display:inline-flex; align-items:center; justify-content:center; width:26px; height:26px; border-radius:7px; color:#6b8192; font-weight:600; }.rank-1 { color:#a97510; background:#fff0c7; }.rank-2 { color:#5f7f96; background:#e9f0f5; }.rank-3 { color:#ad784e; background:#f8eadd; }.home-weekly-rank /deep/ .el-alert { margin-bottom:14px; }
@media(max-width:991px) { .home-rank-column { margin-top:22px; } }
</style>
