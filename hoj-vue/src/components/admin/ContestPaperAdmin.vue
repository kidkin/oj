<template><section class="paper-admin"><h3>试卷与计时</h3>
  <el-radio-group v-model="personal"><el-radio :label="false">统一截止时间</el-radio><el-radio :label="true">每人点击开始后独立计时</el-radio></el-radio-group>
  <p v-if="personal">个人时长（分钟） <el-input-number v-model="minutes" :min="1" :max="10080"/>。最晚开考时间为比赛结束时间减去个人时长。</p>
  <p><label>上传选择题与填空题 <input type="file" accept=".json,application/json" @change="upload"></label> <el-button size="mini" @click="sample">下载题型模板</el-button></p>
  <p>已配置 {{ questions.length }} 个题项</p>
  <el-button type="primary" :disabled="!ready" @click="save">保存试卷与计时</el-button>
  <el-button @click="results">查看答卷与成绩</el-button>
  <el-dialog title="答卷与成绩" :visible.sync="show" append-to-body><el-input v-model="search" placeholder="输入账号、姓名或联系方式查询"/>
    <el-table :data="filtered"><el-table-column prop="username" label="账号"/><el-table-column prop="writtenScore" label="客观题"/><el-table-column prop="programScore" label="编程题"/><el-table-column label="总分"><template slot-scope="s">{{s.row.writtenScore+s.row.programScore}}</template></el-table-column>
    <el-table-column type="expand"><template slot-scope="s"><p v-for="(v,k) in s.row.details" :key="k">{{k}}　{{v}}</p></template></el-table-column></el-table></el-dialog>
</section></template>
<script>
import edu from '@/common/edu-api'
export default {
 props:{cid:{required:true}},
 data(){return {personal:false,minutes:120,questions:[],ready:false,show:false,rows:[],search:''}},
 computed:{filtered(){const key=this.search.trim().toLowerCase();return this.rows.filter(r=>!key||JSON.stringify([r.username,r.details]).toLowerCase().includes(key))}},
 watch:{cid:{immediate:true,async handler(id){this.ready=false;try{const s=await edu.paper(id,'/settings','get');this.personal=s.minutes>0;this.minutes=s.minutes||120;this.questions=s.questions;this.ready=true}catch(e){}}}},
 methods:{
 async upload(e){const f=e.target.files[0];if(!f)return;try{if(f.size>2000000)throw Error();const items=JSON.parse((await f.text()).replace(/^\uFEFF/,''));if(!Array.isArray(items))throw Error();this.questions=items;this.$message.success('试卷已读入，保存后生效')}catch(err){this.$message.error('请上传符合模板的 JSON 文件')}e.target.value=''},
 sample(){const items=[{id:'q1',type:'choice',label:'1 + 1 等于多少？',options:['1','2','3'],keys:['B'],points:5},{id:'q2a',type:'blank',label:'第 2 题：第一空',keys:['4','四'],points:5},{id:'q2b',type:'blank',label:'第 2 题：第二空',keys:['6','六'],points:5},{id:'school',type:'info',label:'学校'}];const url=URL.createObjectURL(new Blob([JSON.stringify(items,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='比赛试卷模板.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)},
 async save(){try{await edu.paper(this.cid,'/settings','put',{minutes:this.personal?this.minutes:0,questions:this.questions});this.$message.success('已保存')}catch(e){}},
 async results(){try{this.rows=await edu.paper(this.cid,'/results','get');this.show=true}catch(e){}}
 }}
</script><style scoped>.paper-admin{border-top:1px solid #dae4ed;margin-top:24px;padding:18px 0}.paper-admin p{margin:14px 0}</style>
