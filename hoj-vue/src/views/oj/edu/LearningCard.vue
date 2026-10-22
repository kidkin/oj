<template>
  <component :is="disabled ? 'article' : 'router-link'" class="learning-card" :class="{'learning-row': layout === 'row', locked: disabled}" :to="disabled ? undefined : to" :aria-label="title + '，' + (disabled ? disabledHint : progressText)" :aria-disabled="disabled ? 'true' : undefined">
    <aside class="card-cover" :class="kind">
      <img v-if="icon && !imageError" :src="icon" alt="" @error="imageError = true">
      <i v-else :class="kind === 'class' ? 'el-icon-school' : 'el-icon-reading'" aria-hidden="true" />
      <span>{{ kind === 'class' ? '班级' : '课程' }}</span>
    </aside>
    <div class="card-content">
      <h3 :title="title">{{ title }}<span v-if="badge" class="card-badge">{{ badge }}</span></h3>
      <div class="card-body">
        <p class="description">{{ description || (kind === 'class' ? '进入班级，继续你的学习。' : '按课程编排，逐步完成练习。') }}</p>
        <p v-if="disabled" class="locked-hint"><i class="el-icon-lock" aria-hidden="true" /> {{ disabledHint }}</p>
        <div v-else-if="showProgress" class="completion">
          <div class="completion-label"><span>{{ progressText }}</span><strong v-if="total">{{ percentage }}%</strong></div>
          <el-progress :percentage="percentage" :show-text="false" :stroke-width="7" :color="percentage === 100 ? '#31ad76' : '#528fd2'" />
        </div>
      </div>
      <footer><span class="owner"><i class="el-icon-user-solid" aria-hidden="true" /> {{ owner || '原账号已删除' }}</span><span>{{ meta }}</span></footer>
    </div>
  </component>
</template>
<script>
export default {
  props: {
    to: {type: [String, Object], default: '/class'}, title: String, description: String, owner: String, meta: String, icon: String, badge: String,
    kind: {type: String, default: 'course'}, layout: {type: String, default: 'card'}, completed: {type: Number, default: 0}, total: {type: Number, default: 0},
    disabled: Boolean, disabledHint: {type: String, default: '请从所属班级进入课程'}, showProgress: {type: Boolean, default: true}
  },
  data: () => ({imageError: false}),
  watch: {icon () { this.imageError = false }},
  computed: {
    percentage () { return this.total ? Math.min(100, Math.round(this.completed / this.total * 100)) : 0 },
    progressText () { return this.total ? '本人已完成 ' + this.completed + ' / ' + this.total + ' 题' : '暂无题目' }
  }
}
</script>
<style scoped>
.learning-card { display: flex; min-width: 0; min-height: 214px; color: #303133; background: #fff; border: 1px solid #dbe5ed; border-radius: 12px; overflow: hidden; box-shadow: 0 3px 12px rgba(45, 84, 119, .07); transition: box-shadow .15s, transform .15s; text-decoration: none; }
.learning-card:not(.locked):hover { color: #303133; transform: translateY(-2px); box-shadow: 0 7px 20px rgba(45, 84, 119, .14); }
.learning-card:focus-visible { outline: 3px solid #409eff; outline-offset: 3px; }.locked { cursor: not-allowed; }
.card-cover { flex: 0 0 100px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; color: #4b8bc7; background: #f2f8fe; border-right: 1px solid #e4edf5; font-size: 12px; }
.card-cover > i { font-size: 48px; }.card-cover.course { color: #c58a32; background: #fffaf1; }.card-cover img { width: 76px; height: 76px; object-fit: contain; }
.card-content { display: flex; flex: 1; flex-direction: column; min-width: 0; }h3 { margin: 0; padding: 11px 14px; color: #a96b17; background: #fff0db; font-size: 17px; line-height: 24px; overflow-wrap: anywhere; }
.card-badge { display: inline-block; margin-left: 8px; font-size: 11px; font-weight: normal; vertical-align: middle; padding: 0 5px; border: 1px solid #edcf9e; border-radius: 4px; line-height: 19px; }
.card-body { display: flex; flex: 1; flex-direction: column; justify-content: space-between; gap: 16px; padding: 14px; }.description { margin: 0; color: #6a7784; font-size: 13px; line-height: 1.65; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.completion-label { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #526679; font-size: 13px; }.completion-label strong { color: #3c7fbd; }.locked-hint { margin: 0; color: #8b7c65; font-size: 12px; line-height: 1.7; }
footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px 12px; padding: 8px 14px; border-top: 1px solid #edf1f5; font-size: 12px; color: #82909c; }.owner { color: #4b8bc7; overflow-wrap: anywhere; }
.learning-row { width: 100%; min-height: 138px; }.learning-row .card-cover { flex-basis: 110px; }.learning-row .card-cover img { width: 64px; height: 64px; }.learning-row .card-cover > i { font-size: 38px; }.learning-row .card-cover { gap: 8px; }.learning-row .card-body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(200px, 320px); align-items: center; gap: 20px; padding: 10px 16px; }.learning-row h3 { padding: 8px 16px; }.learning-row footer { padding: 6px 16px; }
@media (max-width: 700px) { .learning-row .card-body { display: flex; gap: 12px; align-items: stretch; }.learning-row .card-cover { flex-basis: 80px; }.learning-row .card-cover img { width: 56px; height: 56px; } }
@media (max-width: 440px) { .card-cover { flex-basis: 76px; }.card-cover > i { font-size: 38px; }.card-cover img { width: 56px; height: 56px; }h3 { font-size: 16px; }.card-body { padding: 12px; } }
</style>
