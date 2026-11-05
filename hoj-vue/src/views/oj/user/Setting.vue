<template>
  <el-card class="box-card">
    <el-collapse v-model="activeName" accordion @change="refreshProfileAccess">
      <el-collapse-item name="Account">
        <template slot="title">
          <i class="fa fa-gear"> {{ $t('m.Account_Setting') }}</i>
        </template>
        <component :is="Account"></component>
      </el-collapse-item>
      <el-collapse-item name="UserInfo">
        <template slot="title">
          <i class="fa fa-gear"> {{ $t('m.UserInfo_Setting') }}</i>
        </template>
        <div v-loading="checkingProfile" class="profile-settings">
          <component v-if="profileAccess && profileAccess.canEditProfile" :is="UserInfo"></component>
          <el-alert v-else-if="!checkingProfile"
            :title="profileAccess ? '正式账号达到绿色等级（1 分）后可修改资料' : '暂时无法确认资料设置权限，请重新打开此栏'"
            type="info" :closable="false" show-icon />
        </div>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>
<script>
import api from '@/common/api';
const Account = () => import('@/components/oj/setting/Account');
const UserInfo = () => import('@/components/oj/setting/UserInfo');
export default {
  components: {
    Account,
    UserInfo,
  },
  data() {
    return {
      Account: 'Account',
      UserInfo: 'UserInfo',
      activeName: 'Account',
      profileAccess: null,
      checkingProfile: false,
    };
  },
  methods: {
    async refreshProfileAccess(name) {
      if (name !== 'UserInfo' || this.checkingProfile) return;
      this.checkingProfile = true;
      this.profileAccess = null;
      try {
        const response = await api.getProfileSettingsAccess();
        this.profileAccess = response.data.data;
      } catch (_) {
        this.profileAccess = null;
      } finally {
        this.checkingProfile = false;
      }
    },
  },
};
</script>
<style scoped>
.profile-settings { min-height: 40px; }
@media screen and (min-width: 1200px) {
  .box-card {
    margin-left: 10%;
    margin-right: 10%;
  }
}
/deep/ .el-collapse-item__header {
  border-radius: 4px;
  font-size: 18px;
  color: #409eff;
}
</style>
