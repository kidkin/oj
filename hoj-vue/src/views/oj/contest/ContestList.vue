<template>
  <div>
      <el-row type="flex" justify="space-around">
        <el-col :span="24">
          <el-card class="page-panel" shadow="never">
            <div slot="header">
              <span class="panel-title"
                >{{
                  query.type === '' ? $t('m.All') : parseContestType(query.type)
                }}
                {{ $t('m.Contests') }}</span
              >
              <div class="filter-row">
                <span>
                  <el-dropdown
                    @command="onRuleChange"
                    placement="bottom"
                    trigger="hover"
                    class="drop-menu"
                  >
                    <span class="el-dropdown-link">
                      {{
                        query.type === ''
                          ? $t('m.Contest_Rule')
                          : parseContestType(query.type)
                      }}
                      <i class="el-icon-caret-bottom"></i>
                    </span>
                    <el-dropdown-menu slot="dropdown">
                      <el-dropdown-item command="">{{
                        $t('m.All')
                      }}</el-dropdown-item>
                      <el-dropdown-item command="0">ACM</el-dropdown-item>
                      <el-dropdown-item command="1">OI</el-dropdown-item>
                    </el-dropdown-menu>
                  </el-dropdown>
                </span>

                <span>
                  <el-dropdown
                    @command="onStatusChange"
                    placement="bottom"
                    trigger="hover"
                    class="drop-menu"
                  >
                    <span class="el-dropdown-link">
                      {{
                        query.status === ''
                          ? $t('m.Status')
                          : $t('m.' + CONTEST_STATUS_REVERSE[query.status]['name'])
                      }}
                      <i class="el-icon-caret-bottom"></i>
                    </span>
                    <el-dropdown-menu slot="dropdown">
                      <el-dropdown-item command="">{{
                        $t('m.All')
                      }}</el-dropdown-item>
                      <el-dropdown-item command="-1">{{
                        $t('m.Scheduled')
                      }}</el-dropdown-item>
                      <el-dropdown-item command="0">{{
                        $t('m.Running')
                      }}</el-dropdown-item>
                      <el-dropdown-item command="1">{{
                        $t('m.Ended')
                      }}</el-dropdown-item>
                    </el-dropdown-menu>
                  </el-dropdown>
                </span>

                <span>
                  <vxe-input
                    v-model="query.keyword"
                    :placeholder="$t('m.Enter_keyword')"
                    type="search"
                    size="medium"
                    @keyup.enter.native="onKeywordChange"
                    @search-click="onKeywordChange"
                  ></vxe-input>
                </span>
              </div>
            </div>
            <div v-loading="loading">
              <el-empty v-if="!loading && !contests.length" :description="$t('m.No_contest')" />
              <ul v-if="contests.length" class="contest-cards">
                <li v-for="contest in contests" :key="contest.id">
                  <ContestCard :contest="contest" @rule="onRuleChange" />
                </li>
              </ul>
            </div>
          </el-card>
          <Pagination
            :total="total"
            :pageSize="limit"
            @on-change="onCurrentPageChange"
            :current.sync="currentPage"
          ></Pagination>
        </el-col>
      </el-row>
  </div>
</template>

<script>
import api from '@/common/api';
import utils from '@/common/utils';
import { CONTEST_STATUS_REVERSE } from '@/common/constants';
import ContestCard from './ContestCard.vue';

const Pagination = () => import('@/components/oj/common/Pagination');
const limit = 10;

export default {
  name: 'contest-list',
  components: {
    Pagination,
    ContestCard,
  },
  data() {
    return {
      currentPage: 1,
      query: {
        status: '',
        keyword: '',
        type: '',
      },
      limit: limit,
      total: 0,
      contests: [],
      CONTEST_STATUS_REVERSE,
      loading: true,
    };
  },
  mounted() {
    this.init();
  },
  methods: {
    init() {
      let route = this.$route.query;
      this.query.status = Object.prototype.hasOwnProperty.call(CONTEST_STATUS_REVERSE, route.status)
        ? route.status : '';
      if(route.type === 0 || route.type === '0'){
        this.query.type = 0;
      }else if(route.type === 1 || route.type === '1'){
        this.query.type = 1;
      }else{
        this.query.type = '';
      }
      this.query.keyword = route.keyword || '';
      this.currentPage = parseInt(route.currentPage) || 1;
      this.getContestList();
    },
    getContestList() {
      this.loading = true;
      api.getContestList(this.currentPage, this.limit, this.query).then(
        (res) => {
          this.contests = res.data.data.records;
          this.total = res.data.data.total;
          this.loading = false;
        },
        (err) => {
          this.loading = false;
        }
      );
    },

    filterByChange() {
      let query = Object.assign({}, this.query);
      query.currentPage = this.currentPage;
      this.$router.push({
        name: 'ContestList',
        query: utils.filterEmptyValue(query),
      });
    },

    parseContestType(type) {
      if (type == 0) {
        return 'ACM';
      } else if (type == 1) {
        return 'OI';
      }
    },

    onCurrentPageChange(page) {
      this.currentPage = page;
      this.filterByChange();
    },
    onRuleChange(rule) {
      this.query.type = rule;
      this.currentPage = 1;
      this.filterByChange();
    },
    onStatusChange(status) {
      this.query.status = status;
      this.currentPage = 1;
      this.filterByChange();
    },
    onKeywordChange() {
      this.currentPage = 1;
      this.filterByChange();
    },
  },
  watch: {
    $route(newVal, oldVal) {
      if (newVal !== oldVal) {
        this.init();
      }
    },
  },
};
</script>
<style scoped>
.page-panel { border:1px solid #dce8f0; border-radius:16px; box-shadow:0 6px 22px #294d6d0b; }
.page-panel /deep/ .el-card__header > div { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px 20px; }
.filter-row { display:flex; flex-wrap:wrap; align-items:center; gap:10px 20px; }
.drop-menu { cursor:pointer; }
.contest-cards { display:grid; gap:12px; padding:0; margin:0; list-style:none; }
@media (max-width:600px) {
  .page-panel /deep/ .el-card__body { padding:12px; }
}
</style>
