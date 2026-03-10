<template>
    <div class="app-container" v-loading="loading" element-loading-text="引擎高速运转中...">
      <el-row :gutter="20">
        
        <el-col :span="7">
          <el-card shadow="hover" class="box-card">
            <div slot="header" class="clearfix">
              <span style="font-weight: bold;"><i class="el-icon-setting"></i> 引擎环境变量</span>
              <el-button style="float: right; padding: 3px 0" type="text" @click="saveLocalConfig">保存为默认配置</el-button>
            </div>
            
            <el-form ref="configForm" :model="configForm" :rules="rules" label-position="top" size="small">
              <el-form-item label="源环境 (国际版)" prop="sourceOrgId">
              <el-select v-model="configForm.sourceOrgId" placeholder="请选择源环境" class="w-100" clearable filterable>
                <el-option v-for="org in orgList" :key="org.id" 
                           :label="org.name + (org.username ? ' (' + org.username + ')' : '')" 
                           :value="org.id" />
              </el-select>
            </el-form-item>

            <el-form-item label="目标环境 (SFoA 阿里云)" prop="targetOrgId">
              <el-select v-model="configForm.targetOrgId" placeholder="请选择目标环境" class="w-100" clearable filterable>
                <el-option v-for="org in orgList" :key="org.id" 
                           :label="org.name + (org.username ? ' (' + org.username + ')' : '')" 
                           :value="org.id"
                           :disabled="org.id === configForm.sourceOrgId" />
              </el-select>
            </el-form-item>
  
              <el-form-item label="SFoA 站点域名 (含 https://)" prop="sfoaDomain">
                <el-input v-model="configForm.sfoaDomain" placeholder="例如: https://runnergroup.my.sfcrmproducts.cn" />
              </el-form-item>
  
              <el-form-item label="签名密钥 (Secret Key)" prop="secretKey">
                <el-input v-model="configForm.secretKey" show-password placeholder="请输入 HMAC-SHA256 签名密钥" />
              </el-form-item>
  
              <el-row :gutter="10">
                <el-col :span="12">
                  <el-form-item label="新版API路径">
                    <el-input v-model="configForm.apiPath" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="默认Action">
                    <el-select v-model="configForm.defaultAction" class="w-100">
                      <el-option label="预览 (view)" value="view" />
                      <el-option label="下载 (download)" value="download" />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
  
              <el-form-item label="旧ID在SFoA中的字段名" prop="legacyField">
                <el-input v-model="configForm.legacyField" />
              </el-form-item>
            </el-form>
          </el-card>
        </el-col>
  
        <el-col :span="17">
          <el-card shadow="hover" style="margin-bottom: 20px;">
            <el-tabs v-model="activeTab">
              
              <el-tab-pane label="🚀 极速手动模式" name="manual">
                <el-input
                  type="textarea"
                  :rows="6"
                  placeholder="在此粘贴旧单据ID、ContentDocumentId、或者旧版附件链接。支持多行，系统会自动智能嗅探类型..."
                  v-model="manualInputText">
                </el-input>
                <div class="mt-15 text-right">
                  <el-button type="primary" icon="el-icon-lightning" @click="startEngine('manual')">智能生成</el-button>
                </div>
              </el-tab-pane>
  
              <el-tab-pane label="📦 Excel 批量工厂" name="excel">
                <el-upload
                  class="upload-demo"
                  drag
                  action=""
                  :auto-upload="false"
                  :on-change="handleExcelChange"
                  :show-file-list="false"
                  accept=".xlsx, .xls">
                  <i class="el-icon-upload"></i>
                  <div class="el-upload__text">将包含 URL 的 Excel 拖到此处，或 <em>点击上传</em></div>
                  <div class="el-upload__tip" slot="tip">系统将在浏览器本地秒级解析提取第一列的数据，绝不产生额外网络开销</div>
                </el-upload>
                <div v-if="parsedExcelList.length > 0" class="mt-15" style="display: flex; justify-content: space-between; align-items: center;">
                  <span class="text-success"><i class="el-icon-success"></i> 成功从 Excel 解析出 {{ parsedExcelList.length }} 条待处理数据</span>
                  <el-button type="primary" icon="el-icon-video-play" @click="startEngine('excel')">开始批量转换</el-button>
                </div>
              </el-tab-pane>
            </el-tabs>
          </el-card>
  
          <el-card v-if="currentJobId" shadow="never" class="console-card">
            <div slot="header" class="clearfix">
              <span style="font-weight: bold;">📊 实时引擎控制台 (Task ID: {{ currentJobId }})</span>
              <el-button style="float: right; padding: 3px 0" type="text" icon="el-icon-download" @click="handleExport">导出结果清单</el-button>
            </div>
            
            <BuildConsole ref="buildConsole" :visible="true" title="Link Mapping Engine Terminal" />
            
            <div class="mt-20">
              <el-table :data="recordList" border size="small" height="350">
                <el-table-column type="index" width="50" align="center" />
                <el-table-column label="原始输入 (识别类型)" show-overflow-tooltip>
                  <template slot-scope="scope">
                    <span style="color: #909399; font-size: 12px">[{{ scope.row.urlType }}]</span><br/>
                    {{ scope.row.originalUrl }}
                  </template>
                </el-table-column>
                <el-table-column label="新版 SFoA 链接" show-overflow-tooltip>
                  <template slot-scope="scope">
                    <el-link v-if="scope.row.newUrl" type="primary" :underline="false" @click="copyText(scope.row.newUrl)">
                      {{ scope.row.newUrl }} <i class="el-icon-document-copy"></i>
                    </el-link>
                    <span v-else class="text-muted">-</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="120" align="center">
                  <template slot-scope="scope">
                    <el-tag v-if="scope.row.status === 'Success'" type="success" size="mini">成功</el-tag>
                    <el-tag v-else-if="scope.row.status === 'Pending'" type="info" size="mini">处理中</el-tag>
                    <el-tooltip v-else :content="scope.row.errorMsg" placement="top">
                      <el-tag type="danger" size="mini">失败</el-tag>
                    </el-tooltip>
                  </template>
                </el-table-column>
              </el-table>
              <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="fetchRecordList" />
            </div>
          </el-card>
  
        </el-col>
      </el-row>
    </div>
  </template>
  
  <script>
  import * as XLSX from 'xlsx';
  import { createLinkJob, executeLinkJob, listLinkRecord, listOrg } from "@/api/salesforce/link";
  import { getToken } from '@/utils/auth'
  
  import BuildConsole from "../../salesforce/deployment/components/BuildConsole.vue";
  
  export default {
    name: "LinkEngine",
    components: { BuildConsole },
    data() {
      return {
        loading: false,
        orgList: [],
        activeTab: 'manual',
        
        // 引擎配置表单
        configForm: {
          sourceOrgId: undefined,
          targetOrgId: undefined,
          sfoaDomain: '',
          secretKey: '',
          apiPath: '/services/apexrest/openapi/file/',
          defaultAction: 'view',
          legacyField: 'Legacy_ID__c'
        },
        rules: {
          sourceOrgId: [{ required: true, message: '请选择源环境', trigger: 'change' }],
          targetOrgId: [{ required: true, message: '请选择目标环境', trigger: 'change' }],
          sfoaDomain: [{ required: true, message: '请配置 SFoA 站点域名', trigger: 'blur' }],
          secretKey: [{ required: true, message: '请配置签名密钥', trigger: 'blur' }]
        },
  
        // 输入数据
        manualInputText: '',
        parsedExcelList: [],
  
        // 任务与实时表格状态
        currentJobId: null,
        ws: null,
        recordList: [],
        total: 0,
        queryParams: {
          pageNum: 1,
          pageSize: 10,
          jobId: null
        },
        refreshTimer: null
      };
    },
    created() {
      this.getOrgs();
      this.loadLocalConfig();
    },
    beforeDestroy() {
      this.closeWebSocket();
      if(this.refreshTimer) clearInterval(this.refreshTimer);
    },
    methods: {
      /** 1. 加载所有 Org 供选择 */
      getOrgs() {
        listOrg().then(res => {
          this.orgList = res.rows;
        });
      },
  
      /** 2. 本地缓存配置 (用户体验优化) */
      saveLocalConfig() {
        localStorage.setItem('sfLinkEngineConfig', JSON.stringify(this.configForm));
        this.$modal.msgSuccess("配置已保存，下次打开自动带入");
      },
      loadLocalConfig() {
        const saved = localStorage.getItem('sfLinkEngineConfig');
        if (saved) {
          this.configForm = Object.assign(this.configForm, JSON.parse(saved));
        }
      },
  
      /** 3. 纯前端解析 Excel (零服务器压力) */
      handleExcelChange(file) {
        const reader = new FileReader();
        reader.onload = (e) => {
          const data = new Uint8Array(e.target.result);
          const workbook = XLSX.read(data, { type: 'array' });
          const firstSheetName = workbook.SheetNames[0];
          const worksheet = workbook.Sheets[firstSheetName];
          
          // 转换为二维数组
          const json = XLSX.utils.sheet_to_json(worksheet, { header: 1 });
          
          // 提取第一列非空数据，跳过第一行(表头)
          let extractedUrls = [];
          for (let i = 1; i < json.length; i++) {
            if (json[i] && json[i][0]) {
              extractedUrls.push(String(json[i][0]).trim());
            }
          }
          this.parsedExcelList = extractedUrls;
        };
        reader.readAsArrayBuffer(file.raw);
      },
  
      /** 4. 启动引擎 (核心主流程) */
      startEngine(mode) {
        this.$refs["configForm"].validate(valid => {
          if (!valid) return;
          
          let inputs = [];
          if (mode === 'manual') {
            if (!this.manualInputText.trim()) return this.$modal.msgWarning("请输入内容");
            // 按换行符分割并去空
            inputs = this.manualInputText.split('\n').map(s => s.trim()).filter(s => s);
          } else {
            if (this.parsedExcelList.length === 0) return this.$modal.msgWarning("请先上传 Excel");
            inputs = this.parsedExcelList;
          }
  
          this.loading = true;
          
          // Step 1: 调用后端保存主任务
          createLinkJob(this.configForm).then(res => {
            this.currentJobId = res.data.id;
            this.queryParams.jobId = this.currentJobId;
            
            // 初始化前端控制台
            if(this.$refs.buildConsole) this.$refs.buildConsole.clearLogs();
            
            // 建立 WebSocket 连接
            this.initWebSocket(this.currentJobId);
  
            // Step 2: 把前端组装好的海量数组扔给异步执行接口
            executeLinkJob(this.currentJobId, inputs).then(() => {
               this.loading = false;
               this.$modal.msgSuccess("引擎已成功启动！");
               // 开启轮询刷新表格
               this.startPollingTable();
            }).catch(() => { this.loading = false; });
          }).catch(() => { this.loading = false; });
        });
      },
  
      /** 5. WebSocket 实时日志接管 */
      initWebSocket(jobId) {
        this.closeWebSocket();
        const wsUrl = `${process.env.VUE_APP_BASE_API.replace('http', 'ws')}/websocket/${jobId}`;
        this.ws = new WebSocket(wsUrl);
        
        this.ws.onopen = () => {
           // 我们需要鉴权，在初次连接后发送 Token 过去，与你系统的 DeployWebSocketServer 鉴权机制匹配
           this.ws.send(getToken()); 
        };
  
        this.ws.onmessage = (event) => {
          try {
            const res = JSON.parse(event.data);
            
            if (res.stateDetail && this.$refs.buildConsole) {
               let level = 'info';
               if (res.stateDetail.includes('>>>')) level = 'cmd';
               else if (res.stateDetail.includes('异常') || res.stateDetail.includes('崩溃')) level = 'error';
               else if (res.stateDetail.includes('成功') || res.stateDetail.includes('完成')) level = 'success';
               
               this.$refs.buildConsole.logs.push({
                   time: new Date().toLocaleTimeString(),
                   level: level,
                   prefix: `[${level.toUpperCase()}]`,
                   message: res.stateDetail
               });
               // 自动滚动 (复用组件逻辑或手动滚动)
               this.$nextTick(() => {
                   const body = this.$refs.buildConsole.$refs.consoleBody;
                   if(body) body.scrollTop = body.scrollHeight;
               });
            }
  
            if (res.done) {
               this.$modal.msgSuccess("全部处理完毕！");
               if(this.refreshTimer) clearInterval(this.refreshTimer);
               this.fetchRecordList(); // 最后强刷一次表格
               this.closeWebSocket();
            }
          } catch (e) { console.error("WS消息解析失败", e); }
        };
      },
      closeWebSocket() {
        if (this.ws) {
          this.ws.close();
          this.ws = null;
        }
      },
  
      /** 6. 数据表格渲染与轮询 */
      startPollingTable() {
        if(this.refreshTimer) clearInterval(this.refreshTimer);
        this.fetchRecordList();
        // 每 3 秒偷偷拉取一次最新表格数据
        this.refreshTimer = setInterval(() => {
           this.fetchRecordList();
        }, 3000);
      },
      fetchRecordList() {
        if(!this.queryParams.jobId) return;
        listLinkRecord(this.queryParams).then(res => {
          this.recordList = res.rows;
          this.total = res.total;
        });
      },
  
      /** 7. 导出给 OA */
      handleExport() {
        this.download('/system/sf/link/record/export', {
          jobId: this.currentJobId
        }, `Salesforce链接转换结果_${new Date().getTime()}.xlsx`)
      },
  
      /** 辅助方法：一键复制 */
      copyText(text) {
        navigator.clipboard.writeText(text).then(() => {
          this.$message.success('链接已复制到剪贴板');
        });
      }
    }
  };
  </script>
  
  <style scoped>
  .w-100 { width: 100%; }
  .mt-15 { margin-top: 15px; }
  .mt-20 { margin-top: 20px; }
  .text-right { text-align: right; }
  .text-muted { color: #909399; font-style: italic; }
  
  .console-card {
    margin-top: 20px;
    border-top: 3px solid #67C23A; /* 给底部控制台加点极客绿边缘 */
  }
  </style>