<template>
  <el-card class="box-card">
    <el-collapse v-model="activeName" accordion @change="refreshProfileAccess">
      <el-collapse-item name="DisplayName" title="公开显示名">
        <el-form @submit.native.prevent="saveName" class="display-name-form">
          <el-form-item label="显示名"><el-input v-model.trim="displayName" maxlength="24" show-word-limit /></el-form-item>
          <div v-if="nameState && !nameState.direct">
            <p>当前显示名：{{ nameState.current }}</p>
            <p>改名每次 {{ nameState.cost }} 积分，审核通过时扣除。可用积分 {{ nameState.balance }}。</p>
            <p v-if="nameState.latest">最近申请：{{ nameState.latest.requestedName }} · {{ {PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回'}[nameState.latest.state] }}</p>
          </div>
          <el-button type="primary" native-type="submit" :loading="saving" :disabled="!nameLoaded || !displayName || (!nameState.direct && (nameState.balance < nameState.cost || (nameState.latest && nameState.latest.state === 'PENDING') || displayName === nameState.current))">{{ nameState && nameState.direct ? '保存显示名' : '申请改名' }}</el-button>
        </el-form>
      </el-collapse-item>
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
import edu from '@/common/edu-api';
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
      activeName: 'Account', displayName: '', nameState: null, nameLoaded: false, saving: false,
      profileAccess: null,
      checkingProfile: false,
    };
  },
  methods: {
    async saveName() {
      this.saving = true;
      try { await edu.saveDisplayName(this.displayName); if (this.nameState.direct) window.dispatchEvent(new Event('hoj-display-name')); this.$message.success(this.nameState.direct ? '显示名已保存' : '申请已提交，等待教师审核'); await this.refreshProfileAccess('DisplayName'); }
      catch (_) {} finally { this.saving = false; }
    },
    async refreshProfileAccess(name) {
      if (name === 'DisplayName') {
        this.nameLoaded = false;
        try { this.nameState = await edu.nameStatus(); this.displayName = this.nameState.current; this.nameLoaded = true; } catch (_) {}
        return;
      }
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
.display-name-form { max-width: 360px; padding: 12px 4px; }
.display-name-form p { color: #526275; margin-bottom: 12px; }
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
