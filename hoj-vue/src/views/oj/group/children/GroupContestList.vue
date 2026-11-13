<template>
  <el-card>
    <div class="filter-row">
      <el-row>
        <el-col :md="3" :xs="24">
          <span class="title">{{ $t('m.Group_Contest') }}</span>
        </el-col>
        <el-col
          :md="18"
          :xs="24"
          v-if="
            (isSuperAdmin || isGroupAdmin) &&
              !problemPage &&
              !editProblemPage &&
              !announcementPage
          "
        >
          <el-button
            v-if="!editPage"
            :type="createPage ? 'warning' : 'primary'"
            size="small"
            @click="handleCreatePage"
            :icon="createPage ? 'el-icon-back' : 'el-icon-plus'"
            >{{ createPage ? $t('m.Back_To_Admin_Contest_List') : $t('m.Create') }}</el-button
          >
          <el-button
            v-if="editPage && adminPage"
            type="warning"
            size="small"
            @click="handleEditPage"
            icon="el-icon-back"
            >{{ $t('m.Back_To_Admin_Contest_List') }}</el-button
          >
          <el-button
            v-if="!editPage&&!createPage"
            :type="adminPage ? 'danger' : 'success'"
            size="small"
            @click="handleAdminPage"
            :icon="adminPage ? 'el-icon-back' : 'el-icon-s-opportunity'"
            >{{ adminPage ? $t('m.Back_To_Contest_List') : $t('m.Contest_Admin') }}</el-button
          >
        </el-col>
        <el-col
         :md="18"
         :xs="24"
          v-else-if="
            (isSuperAdmin || isGroupAdmin) &&
              problemPage &&
              !editProblemPage &&
              !createProblemPage &&
              !announcementPage
          "
        >
          <el-button
            type="primary"
            size="small"
            @click="handleCreateProblemPage"
            icon="el-icon-plus"
            >{{ $t('m.Create') }}</el-button
          >
          <el-button
            type="primary"
            size="small"
            @click="publicPage = true"
            icon="el-icon-plus"
            >{{ $t('m.Add_From_Public_Problem') }}</el-button
          >
          <el-button
            type="success"
            size="small"
            @click="handleGroupPage"
            icon="el-icon-plus"
            >{{ $t('m.Add_From_Group_Problem') }}</el-button
          >
          <el-button
            type="warning"
            size="small"
            @click="handleProblemPage(null)"
            icon="el-icon-back"
            >{{ $t('m.Back_To_Admin_Contest_List') }}</el-button
          >
        </el-col>
        <el-col
         :md="18"
         :xs="24"
          v-else-if="
            (isSuperAdmin || isGroupAdmin) &&
              (editProblemPage || createProblemPage)
          "
        >
          <el-button
            v-if="editProblemPage"
            type="primary"
            size="small"
            @click="handleEditProblemPage"
            icon="el-icon-back"
            >{{ $t('m.Back_Admin_Contest_Problem_List') }}</el-button
          >`
          <el-button
            v-if="createProblemPage"
            type="primary"
            size="small"
            @click="handleCreateProblemPage"
            icon="el-icon-back"
            >{{ $t('m.Back_Admin_Contest_Problem_List') }}</el-button
          >`
        </el-col>
        <el-col
          :md="18"
          :xs="24"
          v-else-if="(isSuperAdmin || isGroupAdmin) && announcementPage"
        >
          <el-button
            type="primary"
            size="small"
            @click="handleCreateAnnouncementPage"
            icon="el-icon-plus"
            >{{ $t('m.Create') }}</el-button
          >
          <el-button
            type="warning"
            size="small"
            @click="handleAnnouncementPage"
            icon="el-icon-back"
            >{{ $t('m.Back_To_Admin_Contest_List') }}</el-button
          >
        </el-col>
      </el-row>
    </div>
    <div v-if="!adminPage && !createPage && !problemPage">
      <el-empty v-if="!loading && !contestList.length" :description="$t('m.No_contest')" />
      <ul v-if="contestList.length" class="contest-cards" v-loading="loading">
        <li v-for="contest in contestList" :key="contest.id">
          <ContestCard :contest="contest" />
        </li>
      </ul>
      <Pagination
        :total="total"
        :page-size="limit"
        @on-change="currentChange"
        :current.sync="currentPage"
        @on-page-size-change="onPageSizeChange"
        :layout="'prev, pager, next, sizes'"
      ></Pagination>
    </div>
    <ContestList
      v-if="adminPage && !createPage && !problemPage && !announcementPage"
      @handleEditPage="handleEditPage"
      @currentChange="currentChange"
      @handleProblemPage="handleProblemPage"
      @handleAnnouncementPage="handleAnnouncementPage"
      ref="contestList"
    ></ContestList>
    <ProblemList
      v-if="problemPage && !createProblemPage"
      :contestId="contestId"
      @currentChangeProblem="currentChangeProblem"
      @handleEditProblemPage="handleEditProblemPage"
      ref="contestProblemList"
    >
    </ProblemList>
    <AnnouncementList
      v-if="announcementPage"
      :contestId="contestId"
      ref="contestAnnouncementList"
    >
    </AnnouncementList>
    <Contest
      v-if="createPage && !editPage && !problemPage"
      mode="add"
      :title="$t('m.Create_Contest')"
      apiMethod="addGroupContest"
      @handleCreatePage="handleCreatePage"
      @currentChange="currentChange"
    ></Contest>
    <Problem
      v-if="createProblemPage"
      mode="add"
      :contestId="contestId"
      :title="$t('m.Create_Problem')"
      apiMethod="addGroupContestProblem"
      @handleCreateProblemPage="handleCreateProblemPage"
      @currentChange="currentChange"
    ></Problem>
    <el-dialog
      :title="$t('m.Add_Contest_Problem')"
      width="90%"
      :visible.sync="publicPage"
      :close-on-click-modal="false"
    >
      <AddPublicProblem
        v-if="publicPage"
        :contestId="contestId"
        apiMethod="getGroupContestProblemList"
        @currentChangeProblem="currentChangeProblem"
        ref="addPublicProblem"
      ></AddPublicProblem>
    </el-dialog>
    <el-dialog
      :title="$t('m.Add_Contest_Problem')"
      width="350px"
      :visible.sync="groupPage"
      :close-on-click-modal="false"
    >
      <AddGroupProblem
        :contestId="contestId"
        @currentChangeProblem="currentChangeProblem"
        @handleGroupPage="handleGroupPage"
      ></AddGroupProblem>
    </el-dialog>
  </el-card>
</template>

<script>
import { mapGetters } from 'vuex';
import Pagination from '@/components/oj/common/Pagination';
import ContestList from '@/components/oj/group/ContestList';
import Contest from '@/components/oj/group/Contest';
import Problem from '@/components/oj/group/Problem';
import ProblemList from '@/components/oj/group/ProblemList';
import AddPublicProblem from '@/components/oj/group/AddPublicProblem.vue';
import AddGroupProblem from '@/components/oj/group/AddGroupProblem.vue';
import AnnouncementList from '@/components/oj/group/AnnouncementList';
import api from '@/common/api';
import ContestCard from '@/views/oj/contest/ContestCard.vue';
export default {
  name: 'GroupContestList',
  components: {
    Pagination,
    ContestCard,
    ContestList,
    Contest,
    Problem,
    ProblemList,
    AddPublicProblem,
    AddGroupProblem,
    AnnouncementList,
  },
  data() {
    return {
      total: 0,
      currentPage: 1,
      limit: 10,
      contestList: [],
      loading: false,
      adminPage: false,
      createPage: false,
      editPage: false,
      problemPage: false,
      publicPage: false,
      groupPage: false,
      editProblemPage: false,
      createProblemPage: false,
      announcementPage: false,
      contestId: null,
    };
  },
  mounted() {
    this.init();
  },
  methods: {
    init() {
      this.getGroupContestList();
    },
    onPageSizeChange(pageSize) {
      this.limit = pageSize;
      this.init();
    },
    currentChange(page) {
      this.currentPage = page;
      this.init();
    },
    currentChangeProblem() {
      this.$refs.contestProblemList.currentChange(1);
    },
    getGroupContestList() {
      this.loading = true;
      api
        .getGroupContestList(
          this.currentPage,
          this.limit,
          this.$route.params.groupID
        )
        .then(
          (res) => {
            this.contestList = res.data.data.records;
            this.total = res.data.data.total;
            this.loading = false;
          },
          (err) => {
            this.loading = false;
          }
        );
    },
    handleCreatePage() {
      this.createPage = !this.createPage;
    },
    handleEditPage() {
      this.editPage = !this.editPage;
      this.$refs.contestList.editPage = this.editPage;
    },
    handleAdminPage() {
      this.adminPage = !this.adminPage;
      this.createPage = false;
      this.editPage = false;
    },
    handleProblemPage(contestId) {
      this.contestId = contestId;
      this.problemPage = !this.problemPage;
    },
    handleAnnouncementPage(contestId) {
      this.contestId = contestId;
      this.announcementPage = !this.announcementPage;
    },
    handleGroupPage() {
      this.groupPage = !this.groupPage;
    },
    handleEditProblemPage() {
      this.editProblemPage = !this.editProblemPage;
      this.$refs.contestProblemList.editPage = this.editProblemPage;
    },
    handleCreateProblemPage() {
      this.createProblemPage = !this.createProblemPage;
      this.$refs.contestProblemList.currentChange(1);
    },
    handleCreateAnnouncementPage() {
      this.$refs.contestAnnouncementList.openAnnouncementDialog(null);
    },
  },
  computed: {
    ...mapGetters(['isAuthenticated', 'isSuperAdmin', 'isGroupAdmin']),
  },
};
</script>

<style scoped>
.title {
  font-size: 20px;
  vertical-align: middle;
  float: left;
}
.filter-row {
  margin-bottom: 5px;
  text-align: center;
}
@media screen and (max-width: 768px) {
  .filter-row span {
    margin-left: 5px;
    margin-right: 5px;
  }
}
@media screen and (min-width: 768px) {
  .filter-row span {
    margin-left: 10px;
    margin-right: 10px;
  }
}
.contest-cards { display:grid; gap:12px; padding:0; margin:12px 0; list-style:none; }
</style>
