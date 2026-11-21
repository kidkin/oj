<template>
  <el-switch v-if="allowed" v-model="roster" active-text="原姓名" inactive-text="显示名" @change="change" />
</template>
<script>
export default {
  data () { return {roster: sessionStorage.getItem('hoj-name-view') === 'roster'} },
  computed: {allowed () { return (this.$store.getters.userInfo.roleList || []).some(r => ['root','teacher','admin'].includes(r)) }},
  methods: {change (value) { sessionStorage.setItem('hoj-name-view', value ? 'roster' : 'public'); this.$emit('change') }}
}
</script>
