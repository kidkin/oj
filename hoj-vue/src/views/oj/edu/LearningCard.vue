<template>
  <router-link class="learning-card" :to="to" :aria-label="title + '，' + progressText">
    <aside class="card-cover" :class="kind">
      <i :class="kind === 'class' ? 'el-icon-school' : 'el-icon-reading'" aria-hidden="true" />
      <span>{{ kind === 'class' ? '我的班级' : '共享课程' }}</span>
    </aside>
    <div class="card-content">
      <h3 :title="title">{{ title }}</h3>
      <div class="card-body">
        <p class="description">{{ description || (kind === 'class' ? '进入班级，继续你的学习。' : '进入课程，按顺序练习题目。') }}</p>
        <div class="completion">
          <div class="completion-label"><span>{{ progressText }}</span><strong v-if="total">{{ percentage }}%</strong></div>
          <el-progress :percentage="percentage" :show-text="false" :stroke-width="7" :color="percentage === 100 ? '#31ad76' : '#528fd2'" />
        </div>
      </div>
      <footer><span class="owner"><i class="el-icon-user-solid" aria-hidden="true" /> {{ owner || '原账号已删除' }}</span><span>{{ meta }}</span></footer>
    </div>
  </router-link>
</template>
<script>
export default {
  props: {to: {type: [String, Object], required: true}, title: String, description: String, owner: String, meta: String, kind: {type: String, default: 'course'}, completed: {type: Number, default: 0}, total: {type: Number, default: 0}},
  computed: {
    percentage () { return this.total ? Math.min(100, Math.round(this.completed / this.total * 100)) : 0 },
    progressText () { return this.total ? '已完成 ' + this.completed + ' / ' + this.total + ' 题' : '暂无题目' }
  }
}
</script>
<style scoped>
.learning-card { display: flex; min-width: 0; min-height: 216px; color: #303133; background: #fff; border: 1px solid #dbe5ed; border-radius: 12px; overflow: hidden; box-shadow: 0 3px 12px rgba(45, 84, 119, .07); transition: box-shadow .15s, transform .15s; }
.learning-card:hover { color: #303133; transform: translateY(-2px); box-shadow: 0 7px 20px rgba(45, 84, 119, .14); }
.learning-card:focus-visible { outline: 3px solid #409eff; outline-offset: 3px; }
.card-cover { flex: 0 0 100px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 18px; color: #4b8bc7; background: #f2f8fe; border-right: 1px solid #e4edf5; font-size: 12px; }
.card-cover > i { font-size: 50px; }.card-cover.course { color: #c58a32; background: #fffaf1; }
.card-content { display: flex; flex: 1; flex-direction: column; min-width: 0; }h3 { margin: 0; padding: 12px 14px; color: #a96b17; background: #fff0db; font-size: 17px; line-height: 24px; overflow-wrap: anywhere; }
.card-body { display: flex; flex: 1; flex-direction: column; justify-content: space-between; gap: 18px; padding: 14px; }.description { margin: 0; color: #6a7784; font-size: 13px; line-height: 1.65; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.completion-label { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #526679; font-size: 13px; }.completion-label strong { color: #3c7fbd; }
footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px 12px; padding: 9px 14px; border-top: 1px solid #edf1f5; font-size: 12px; color: #82909c; }.owner { color: #4b8bc7; overflow-wrap: anywhere; }
@media (max-width: 440px) { .card-cover { flex-basis: 76px; }.card-cover > i { font-size: 40px; }h3 { font-size: 16px; }.card-body { padding: 12px; } }
</style>
