<template>
  <section class="paper-admin">
    <h3>试卷与计时</h3>
    <p v-if="locked" class="muted">已有学生开考。可查看答卷或为指定学生安排重考。</p>
    <fieldset :disabled="locked || !ready">
      <el-radio-group v-model="personal" :disabled="locked || !ready">
        <el-radio :label="false">统一截止时间</el-radio>
        <el-radio :label="true">每人点击开始后独立计时</el-radio>
      </el-radio-group>
      <p v-if="personal">个人时长（分钟）
        <el-input-number v-model="minutes" :min="1" :max="10080" :disabled="locked"/>
        最晚开考时间为比赛结束时间减去个人时长。
      </p>
      <div class="paper-tools" v-if="!locked">
        <el-button size="small" @click="add('choice')">添加单选题</el-button>
        <el-button size="small" @click="add('blank')">添加填空题</el-button>
        <el-button size="small" @click="add('info')">添加考生资料项</el-button>
        <label class="file-button">导入 JSON <input type="file" accept=".json,application/json" @change="upload"></label>
        <el-button type="text" @click="sample">下载 JSON 模板</el-button>
      </div>
      <details v-for="(item,index) in questions" :key="item.id" :open="item.id===opened" class="paper-item">
        <summary>{{ index+1 }}. {{ types[item.type] }} · {{ item.label || '未填写题干' }}</summary>
        <el-input v-model="item.label" type="textarea" :rows="2" maxlength="4000" placeholder="题干或资料项名称" :disabled="locked"/>
        <div v-if="item.type==='choice'" class="edit-options">
          <p>选中圆点可设为正确答案</p>
          <el-radio-group v-model="item.correct" :disabled="locked">
            <div v-for="(option,k) in item.options" :key="k" class="edit-option">
              <el-radio :label="k">{{ letter(k) }}</el-radio>
              <el-input v-model="item.options[k]" :aria-label="'选项 ' + letter(k)" :disabled="locked"/>
              <el-button v-if="!locked" type="text" :disabled="item.options.length<=2" @click="removeOption(item,k)">删除</el-button>
            </div>
          </el-radio-group>
          <el-button v-if="!locked" size="mini" :disabled="item.options.length>=26" @click="item.options.push('')">添加选项</el-button>
        </div>
        <div v-if="item.type==='blank'" class="edit-options">
          <label>可接受的答案（每行一个）
            <el-input v-model="item.accepted" type="textarea" :rows="2" :disabled="locked"/>
          </label>
        </div>
        <div class="edit-actions">
          <span v-if="item.type!=='info'">分值 <el-input-number v-model="item.points" size="small" :min="0" :max="1000" :disabled="locked"/></span>
          <template v-if="!locked">
            <el-button size="mini" :disabled="index===0" @click="move(index,-1)">上移</el-button>
            <el-button size="mini" :disabled="index===questions.length-1" @click="move(index,1)">下移</el-button>
            <el-button size="mini" type="text" @click="questions.splice(index,1)">删除题项</el-button>
          </template>
        </div>
      </details>
      <el-button v-if="!locked" type="primary" :disabled="!ready || saving" :loading="saving" @click="save">保存试卷与计时</el-button>
    </fieldset>
    <el-button @click="results">查看答卷与成绩</el-button>
    <el-dialog title="答卷与成绩" :visible.sync="show" append-to-body width="min(960px, 96vw)">
      <el-input v-model="search" placeholder="输入账号、姓名或联系方式查询"/>
      <el-table :data="filtered" row-key="uid">
        <el-table-column type="expand">
          <template slot-scope="{row}">
            <p v-for="(value,label) in row.details" :key="label">{{label}}　{{value}}</p>
            <details v-for="attempt in row.history" :key="attempt.id" class="history-attempt">
              <summary>{{ stamp(attempt.resetAt) }} 重考前答卷 · {{ attempt.writtenScore+attempt.programScore }} 分</summary>
              <p v-for="(value,label) in attempt.details" :key="label">{{label}}　{{value}}</p>
            </details>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="账号" min-width="120"/>
        <el-table-column label="状态" min-width="110"><template slot-scope="{row}">{{ status(row) }}</template></el-table-column>
        <el-table-column prop="writtenScore" label="客观题"/>
        <el-table-column prop="programScore" label="编程题"/>
        <el-table-column label="总分"><template slot-scope="{row}">{{ row.started ? row.writtenScore+row.programScore : '—' }}</template></el-table-column>
        <el-table-column label="操作" width="115">
          <template slot-scope="{row}">
            <el-button v-if="row.started" type="text" :disabled="resetting===row.uid" @click="restart(row)">允许重考</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>
<script>
import edu from '@/common/edu-api'

const types = {choice: '单选题', blank: '填空题', info: '考生资料'}
export default {
  props: {cid: {required: true}},
  data() {
    return {types, personal: false, minutes: 120, questions: [], ready: false, locked: false, saving: false,
      opened: '', nextId: 0, show: false, rows: [], search: '', resetting: ''}
  },
  computed: {
    filtered() {
      const key = this.search.trim().toLowerCase()
      return this.rows.filter(row => !key ||
        JSON.stringify([row.username, row.details, (row.history || []).map(attempt => attempt.details)]).toLowerCase().includes(key))
    }
  },
  watch: {
    cid: {immediate: true, async handler(id) {
      this.ready = false
      this.questions = []
      try {
        const settings = await edu.paper(id, '/settings', 'get')
        if (id !== this.cid) return
        this.personal = settings.minutes > 0
        this.minutes = settings.minutes || 120
        this.questions = this.editable(settings.questions)
        this.locked = settings.locked
        this.ready = true
      } catch (e) { /* Request layer displays the server error. */ }
    }}
  },
  methods: {
    letter(index) { return String.fromCharCode(65 + index) },
    editable(items) {
      return items.map(item => ({...item, options: item.options || ['', '', '', ''],
        correct: item.keys && item.type === 'choice' ? item.keys[0].charCodeAt(0) - 65 : null,
        accepted: (item.keys || []).join('\n')}))
    },
    add(type) {
      if (this.questions.length >= 250) return this.$message.warning('最多可添加 250 个题项')
      let id
      do { id = 'question_' + ++this.nextId } while (this.questions.some(item => item.id === id))
      this.questions.push(...this.editable([{id, type, label: '', points: 10}]))
      this.opened = id
    },
    move(index, delta) {
      const item = this.questions.splice(index, 1)[0]
      this.questions.splice(index + delta, 0, item)
      this.opened = item.id
    },
    removeOption(item, index) {
      item.options.splice(index, 1)
      if (item.correct === index) item.correct = null
      else if (item.correct > index) item.correct--
    },
    payload() {
      return this.questions.map(({id, type, label, points, options, correct, accepted}) => {
        if (type === 'info') return {id, type, label}
        if (type === 'choice') return {id, type, label, points, options, keys: correct === null ? [] : [this.letter(correct)]}
        return {id, type, label, points, keys: accepted.split('\n').map(value => value.trim()).filter(Boolean)}
      })
    },
    async upload(event) {
      const file = event.target.files[0]
      if (!file) return
      try {
        if (file.size > 2000000) throw Error()
        const items = JSON.parse((await file.text()).replace(/^\uFEFF/, ''))
        if (!Array.isArray(items) || items.length > 250 || items.some(item => !item || !types[item.type] ||
            (item.keys && (!Array.isArray(item.keys) || item.keys.some(value => typeof value !== 'string'))))) throw Error()
        const questions = this.editable(items)
        if (this.questions.length) await this.$confirm('本次导入会替换当前编辑中的题项。', '导入试卷')
        this.questions = questions
        this.$message.success('已导入到编辑区，保存后生效')
      } catch (error) {
        if (error instanceof Error) this.$message.error('请上传符合模板的 JSON 文件')
      } finally { event.target.value = '' }
    },
    sample() {
      const items = [{id:'q1',type:'choice',label:'1 + 1 等于多少？',options:['1','2','3'],keys:['B'],points:10},
        {id:'q2',type:'blank',label:'2 + 2 等于多少？',keys:['4','四'],points:10}, {id:'school',type:'info',label:'学校'}]
      const url = URL.createObjectURL(new Blob([JSON.stringify(items, null, 2)], {type:'application/json'}))
      const link = document.createElement('a')
      link.href = url
      link.download = '比赛试卷模板.json'
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    },
    async save() {
      const questions = this.payload()
      const missing = questions.find(item => !item.label.trim() ||
        (item.type !== 'info' && (!item.keys.length || !Number.isInteger(item.points))) ||
        (item.type === 'choice' && item.options.some(option => !option.trim())))
      if (missing) {
        this.opened = missing.id
        return this.$message.warning('请补全第 ' + (questions.indexOf(missing) + 1) + ' 项的题干、选项、答案与分值')
      }
      this.saving = true
      try {
        await edu.paper(this.cid, '/settings', 'put', {minutes: this.personal ? this.minutes : 0, questions})
        this.$message.success('试卷已保存')
      } catch (e) { /* Request layer displays the server error. */ }
      finally { this.saving = false }
    },
    async results() {
      try {
        this.rows = await edu.paper(this.cid, '/results', 'get')
        this.show = true
      } catch (e) { /* Request layer displays the server error. */ }
    },
    status(row) {
      if (!row.started) return '等待重考'
      if (row.submitted) return '已交卷'
      return Number(row.deadline) <= Date.now() ? '已到时收卷' : '答题中'
    },
    stamp(value) { return new Date(Number(value)).toLocaleString('zh-CN', {hour12:false}) },
    async restart(row) {
      try {
        await this.$confirm('允许 ' + row.username + ' 重新开考？原答卷会归档，重考从空白试卷开始。', '允许重考', {type:'warning'})
      } catch (e) { return }
      this.resetting = row.uid
      try {
        await edu.paper(this.cid, '/results/' + encodeURIComponent(row.uid) + '/restart?started=' + row.started, 'post')
        await this.results()
        this.$message.success('已允许重考')
      } catch (e) { /* Request layer displays the server error. */ }
      finally { this.resetting = '' }
    }
  }
}
</script>
<style scoped>
.paper-admin{border-top:1px solid #dae4ed;margin-top:24px;padding:18px 0}
.paper-admin fieldset{border:0;margin:0 0 12px;padding:0;min-width:0}
.paper-tools,.edit-actions,.edit-option{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:12px 0}
.paper-tools .el-button,.edit-actions .el-button{margin:0}
.paper-item{background:#f8fafc;border:1px solid #dce7ee;border-radius:12px;padding:12px;margin:10px 0}
.paper-item summary,.history-attempt summary{cursor:pointer;margin-bottom:8px}
.edit-option .el-input{width:min(460px,55vw)}
.edit-option .el-radio{margin-right:0}
.file-button{display:flex;align-items:center;gap:6px}
.muted{color:#52677c}
.history-attempt{background:#f5f7fa;margin-top:8px;padding:10px;border-radius:8px}
</style>
