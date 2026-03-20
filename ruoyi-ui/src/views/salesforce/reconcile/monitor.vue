<template>
  <div class="app-container monitor-container">
    <el-card shadow="never" class="header-card">
      <div slot="header" class="clearfix" style="display: flex; align-items: center; justify-content: space-between;">
        <span class="page-title">
          <i class="el-icon-monitor" style="margin-right: 8px;"></i>任务全景控制台
        </span>
        <div style="display: flex; gap: 10px;">
          <el-button v-if="overallStatus !== 'RUNNING' && overallStatus !== 'LOADING'" type="primary" size="small"
            icon="el-icon-plus" @click="handleAddObjects">配置比对对象</el-button>

          <el-button v-if="objList.length > 0 && overallStatus !== 'RUNNING' && overallStatus !== 'LOADING'"
            type="success" size="small" icon="el-icon-video-play" @click="handleStartAllJob">启动全量验证</el-button>

          <el-button plain size="small" icon="el-icon-back" @click="handleGoBack">返回任务列表</el-button>
        </div>
      </div>
      <div class="job-meta">
        <el-descriptions :column="3" border size="medium">
          <el-descriptions-item label="任务名称">
            <span style="font-weight: bold">{{ jobName || '-' }}</span>
            <el-tag size="mini" type="info" style="margin-left: 5px">#{{ jobId }}</el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="源环境">
            <el-tag type="info" size="mini"><i class="el-icon-cloudy"></i> {{ getOrgName(jobMeta.sourceOrgId)
            }}</el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="目标环境">
            <el-tag type="success" size="mini"><i class="el-icon-cloudy-and-sunny"></i> {{
              getOrgName(jobMeta.targetOrgId) }}</el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="数据截断时间">
            <el-tag v-if="jobMeta.dataEndTime" type="warning" size="small" effect="plain">
              <i class="el-icon-time"></i> {{ jobMeta.dataEndTime }}
            </el-tag>
            <span v-else style="color: #909399; font-size: 12px; font-style: italic;">
              全量拉取 (无限制)
            </span>
          </el-descriptions-item>

          <el-descriptions-item label="任务状态">
            <el-tag :type="statusTagType" effect="dark">{{ overallStatus }}</el-tag>
          </el-descriptions-item>

          <el-descriptions-item label="总进度 / 对象">
            <div style="display: flex; align-items: center; gap: 10px;">
              <el-progress style="width: 120px;" :percentage="calcTotalProgress"
                :status="overallStatus === 'FINISHED' ? 'success' : ''" :stroke-width="10"></el-progress>
              <span style="font-weight: bold; font-size: 12px; color: #909399">共 {{ objList.length }} 模块</span>
            </div>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <div slot="header" class="clearfix">
        <span style="font-weight: bold;">对象执行明细</span>
        <el-button style="float: right; padding: 3px 0" type="text" icon="el-icon-refresh"
          @click="fetchData">刷新数据</el-button>
      </div>

      <el-table :data="objList" v-loading="loading" stripe border highlight-current-row row-key="objectName"
        style="width: 100%">
        <el-table-column prop="objectLabel" label="比对对象" min-width="200" show-overflow-tooltip>
          <template slot-scope="scope">
            <div class="obj-name-text">{{ scope.row.objectLabel }}</div>
            <div style="font-size: 12px; color: #909399;">{{ scope.row.objectName }}</div>
          </template>
        </el-table-column>

        <el-table-column label="执行状态" width="140" align="center">
          <template slot-scope="scope">
            <el-tag :type="getStatusTag(scope.row.status)" size="small" effect="dark">
              <i :class="getStatusIcon(scope.row.status)"></i> {{ scope.row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="实时进度" min-width="300">
          <template slot-scope="scope">
            <el-progress :percentage="scope.row.progress || 0" :status="getProcessStatus(scope.row.status)"
              :stroke-width="18" :text-inside="true"></el-progress>
            <div class="progress-msg" v-if="scope.row.status === 'RUNNING'">
              <i class="el-icon-loading"></i> {{ scope.row.currentMsg || '正在处理中...' }}
            </div>
            <div class="error-msg" v-else-if="scope.row.status === 'FAILED'">
              <i class="el-icon-warning"></i> {{ scope.row.errorMsg }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="结果统计 (Source / Target => Diff)" width="320" align="center">
          <template slot-scope="scope">
            <div v-if="scope.row.status === 'FINISHED' || scope.row.status === 'PARTIAL_SUCCESS'" class="stats-bar">
              <span class="stat-num source">{{ scope.row.totalSource }}</span>
              <span class="divider">/</span>
              <span class="stat-num target">{{ scope.row.totalTarget }}</span>
              <span class="arrow">➞</span>
              <el-badge :value="scope.row.diffCount" :max="9999" :type="scope.row.diffCount > 0 ? 'danger' : 'success'"
                class="diff-badge">
                <span class="stat-num diff">差异</span>
              </el-badge>
            </div>
            <span v-else style="color: #C0C4CC">-</span>
          </template>
        </el-table-column>

        <el-table-column label="结果操作" width="220" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button size="mini" type="text" icon="el-icon-setting"
              :style="{ color: (scope.row.status === 'RUNNING' || scope.row.status === 'WAITING') ? '#C0C4CC' : '#909399' }"
              :disabled="scope.row.status === 'RUNNING' || scope.row.status === 'WAITING'"
              @click="handleConfig(scope.row)">映射配置</el-button>

            <el-button size="mini" type="text" icon="el-icon-document-delete" style="color: #F56C6C"
              v-if="scope.row.status === 'FAILED' || scope.row.status === 'ABORTED'"
              @click="handleViewError(scope.row)">错误详情</el-button>

            <el-button size="mini" type="text" icon="el-icon-view" :disabled="scope.row.status !== 'FINISHED'"
              @click="handlePreview(scope.row)">预览</el-button>

            <el-button size="mini" type="text" icon="el-icon-download" :disabled="scope.row.status !== 'FINISHED'"
              @click="handleDownload(scope.row)">下载</el-button>

            <el-button size="mini" type="text" icon="el-icon-refresh-right" style="color: #E6A23C"
              v-if="scope.row.status === 'FINISHED' || scope.row.status === 'FAILED' || scope.row.status === 'ABORTED'"
              @click="handleRetry(scope.row)">重试</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <preview-result ref="previewRef" />


    <el-dialog title="执行异常明细" :visible.sync="errorDialogVisible" width="650px" append-to-body
      :close-on-click-modal="false">
      <div style="padding: 10px 0;">
        <el-alert title="数据比对引擎在执行该对象时遇到严重错误而中断，详细日志如下：" type="error" show-icon :closable="false"
          style="margin-bottom: 15px;"></el-alert>
        <div class="error-log-container">
          {{ currentErrorMsg }}
        </div>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button @click="errorDialogVisible = false" size="small">关 闭</el-button>
        <el-button type="primary" size="small" icon="el-icon-document-copy" @click="copyErrorMsg">一键复制日志</el-button>
      </div>
    </el-dialog>

    <el-drawer :title="`正在配置: ${currentEditObj.objectLabel}`" :visible.sync="configDrawerVisible" direction="rtl"
      size="60%" :destroy-on-close="true">
      <div v-loading="drawerLoading" style="padding: 0 20px; height: calc(100vh - 130px);">
        <field-mapping-panel v-if="currentEditConfig && !drawerLoading" :config="currentEditConfig"
          :source-org-id="jobMeta.sourceOrgId" :object-label="currentEditObj.objectLabel" />
      </div>
      <div style="padding: 15px 20px; border-top: 1px solid #ebeef5; text-align: right; background: #fff;">
        <el-button @click="configDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveSingleConfig">保存并关闭</el-button>
      </div>
    </el-drawer>

    <wizard-config ref="wizardConfig" @ok="handleWizardComplete" />
  </div>
</template>

<script>
// API 与工具
import { getJobMonitor, downloadObjUrl, retryObjLog } from "@/api/salesforce/dataRunObjLog";
import { getToken } from "@/utils/auth";
import request from '@/utils/request';
import { getJob } from "@/api/salesforce/dataJob";
import { listConfigs, batchSaveConfigs } from "@/api/salesforce/dataObjConfig";
import { listOrg } from "@/api/salesforce/org";
import { runJob } from "@/api/salesforce/reconcile";

// 子组件
import PreviewResult from './preview';
import FieldMappingPanel from './components/FieldMappingPanel';
import WizardConfig from "./wizard";

export default {
  name: "JobMonitor",
  components: { PreviewResult, FieldMappingPanel, WizardConfig },
  data() {
    return {
      jobId: null,
      jobName: '',
      loading: false,
      objList: [],
      overallStatus: 'LOADING',
      socket: null,
      timer: null,
      lockReconnect: false,
      socketRetryCount: 0,
      configDrawerVisible: false,
      drawerLoading: false,
      savingConfig: false,
      currentEditObj: {},
      currentEditConfig: null,
      fullConfigList: [],
      jobMeta: {},
      orgList: [], // 缓存环境字典
      errorDialogVisible: false,
      currentErrorMsg: '',
    };
  },
  computed: {
    // 计算总进度：基于所有对象的进度均值
    calcTotalProgress() {
      const totalCount = this.objList.length;
      if (totalCount === 0) return 0;

      const sumProgress = this.objList.reduce((sum, item) => {
        let p = 0;
        if (item.status === 'FINISHED' || item.status === 'FAILED') {
          p = 100;
        } else if (item.status === 'RUNNING') {
          p = item.progress || 0;
        }
        return sum + p;
      }, 0);

      return Math.floor(sumProgress / totalCount);
    },
    statusTagType() {
      if (this.overallStatus === 'RUNNING') return '';
      if (this.overallStatus === 'FINISHED') return 'success';
      if (this.overallStatus === 'FAILED') return 'danger';
      return 'info';
    },
    isFinished() {
      return this.overallStatus === 'FINISHED' || this.overallStatus === 'FAILED';
    }
  },
  created() {
    this.jobId = this.$route.query.jobId || this.$route.params.jobId;
    if (!this.jobId) {
      this.$message.error("缺少任务ID参数");
      return;
    }

    // 获取 Org 字典用于翻译 ID
    listOrg().then(res => {
      this.orgList = res.rows || res.data || [];
    });

    // 拉取 Job 元数据信息 (包含环境信息)
    getJob(this.jobId).then(res => {
      this.jobMeta = res.data || {};
    });

    this.fetchData();
    // 5秒轮询兜底
    // this.timer = setInterval(this.fetchData, 5000);
  },
  beforeDestroy() {
    this.disconnectSocket();
    this.stopPolling();
    // if (this.timer) clearInterval(this.timer);
  },
  methods: {
    // --- 核心优化：全景控制台新增方法 ---
    startPolling() {
      this.stopPolling(); // 开启前先防抖清除，防止多开
      this.timer = setInterval(() => {
        this.fetchData(true); // true 表示静默刷新，不触发加载动画
      }, 5000);
    },
    stopPolling() {
      if (this.timer) {
        clearInterval(this.timer);
        this.timer = null;
      }
    },
    // 翻译 Org 字典
    getOrgName(id) {
      if (!id) return '加载中...';
      const org = this.orgList.find(item => item.id === id);
      return org ? org.name : `ID:${id}`;
    },

    //打开错误详情弹窗
    handleViewError(row) {
      this.currentErrorMsg = row.errorMsg || '未能获取到详细的错误堆栈信息，系统可能发生了底层崩溃，请联系管理员查看服务器后台日志。';
      this.errorDialogVisible = true;
    },

    //一键复制错误日志
    copyErrorMsg() {
      // 现代浏览器支持的 Clipboard API
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(this.currentErrorMsg).then(() => {
          this.$message.success("错误日志已复制到剪贴板");
        }).catch(() => {
          this.$message.error("复制失败，请手动选择文字进行复制");
        });
      } else {
        // 兼容模式 (传统的 execCommand)
        const textArea = document.createElement("textarea");
        textArea.value = this.currentErrorMsg;
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        try {
          document.execCommand('copy');
          this.$message.success("错误日志已复制到剪贴板");
        } catch (err) {
          this.$message.error("复制失败，请手动选择文字进行复制");
        }
        document.body.removeChild(textArea);
      }
    },

    // 打开 Wizard，直接从选对象步骤(1)开始
    handleAddObjects() {
      this.$refs.wizardConfig.init(this.jobId, 1);
    },

    // Wizard 配置完毕后的回调
    handleWizardComplete() {
      this.fetchData();
    },

    // 启动当前任务下所有对象的全量验证
    handleStartAllJob() {
      this.$confirm('确认启动当前配置的所有对象的全量验证任务?', '提示', { type: 'warning' }).then(() => {
        // 将整体状态乐观设为 RUNNING
        this.overallStatus = 'RUNNING';
        this.objList.forEach(item => {
          if (item.status !== 'FINISHED' && item.status !== 'RUNNING') {
            item.status = 'WAITING';
            item.currentMsg = '进入执行队列...';
          }
        });
        runJob(this.jobId).then(() => {
          this.$message.success("全量验证指令下发成功！");
          this.initWebSocket();
          this.fetchData();
        });
      });
    },

    // 状态更新逻辑优化，支持空任务状态
    updateOverallStatus() {
      if (this.objList.length === 0) {
        this.overallStatus = 'IDLE'; // 空白任务归为 IDLE，可点击配置或添加
        return;
      }

      const hasRunning = this.objList.some(i => i.status === 'RUNNING');
      const hasWaiting = this.objList.some(i => i.status === 'WAITING');
      const allFinished = this.objList.every(i => i.status === 'FINISHED' || i.status === 'FAILED');

      if (hasRunning || hasWaiting) {
        this.overallStatus = 'RUNNING';
      } else if (allFinished) {
        this.overallStatus = 'FINISHED';
        // if (this.timer) clearInterval(this.timer);
      } else {
        // 当列表中存在刚刚新增的对象 (状态为 IDLE) 时，它既不是 RUNNING 也不是全部完成
        // 我们需要显式地将大盘状态归为 IDLE，以此重新激活顶部的操作按钮
        this.overallStatus = 'IDLE';
      }
    },

    // --- 以下为原有业务逻辑 ---

    fetchData(isSilent = false) {
      if (!isSilent) this.loading = true; // 仅在非静默时展示表格 loading

      getJobMonitor(this.jobId).then(res => {
        const dataWrapper = res.data || {};
        if (dataWrapper.jobName) this.jobName = dataWrapper.jobName;
        const newData = Array.isArray(dataWrapper) ? dataWrapper : (dataWrapper.list || []);

        if (this.objList.length === 0) {
          this.objList = newData;
        } else {
          // 对比合并数据，防止进度回跳 (这部分逻辑保持原有完全不变)
          newData.forEach(newItem => {
            const index = this.objList.findIndex(i => i.objectName === newItem.objectName);
            if (index !== -1) {
              const oldItem = this.objList[index];
              let shouldUpdate = false;

              if (newItem.status !== oldItem.status) shouldUpdate = true;
              else if (newItem.progress > oldItem.progress) shouldUpdate = true;
              else if (newItem.diffCount !== oldItem.diffCount) shouldUpdate = true;

              if (shouldUpdate) {
                const mergedItem = {
                  ...oldItem,
                  ...newItem,
                  progress: Math.max(oldItem.progress || 0, newItem.progress || 0)
                };
                this.$set(this.objList, index, mergedItem);
              }
            } else {
              this.objList.push(newItem);
            }
          });

          // 若有被后端删除的对象，同步剔除
          this.objList = this.objList.filter(oldItem =>
            newData.some(newItem => newItem.objectName === oldItem.objectName)
          );
        }

        this.updateOverallStatus();

        // ==========================================
        // 【核心优化 6】：智能轮询与 WS 启停判断
        // ==========================================
        const activeStatuses = ['RUNNING', 'WAITING', 'INITIALIZING'];
        const isJobRunning = activeStatuses.includes(this.overallStatus);
        const hasActiveObjects = this.objList.some(obj => activeStatuses.includes(obj.status));

        if (isJobRunning || hasActiveObjects) {
          // 当有任务在执行时，确保 HTTP 轮询和 WS 都开启
          if (!this.timer) {
            this.startPolling();
          }
          this.initWebSocket();
        } else {
          // 没有任何任务执行时 (如 IDLE, FINISHED)，彻底关闭 HTTP 轮询！
          this.stopPolling();
          // 未执行时，关闭冗余的 WS 连接 (前端点"启动"时会在 handleStartAllJob 中重新连上)
          if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.disconnectSocket();
          }
        }
        // ==========================================

        if (!isSilent) this.loading = false;
      }).catch(err => {
        console.error("获取监控数据失败", err);
        if (!isSilent) this.loading = false;
        this.stopPolling(); // 发生网络异常时停止轮询，防止服务器被死循环请求压垮
      });
    },

    // WebSocket 连接逻辑
    initWebSocket() {
      if (!this.jobId) return;
      if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
        return;
      }

      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const host = window.location.host;
      const baseUrl = process.env.VUE_APP_BASE_API;
      const token = getToken();

      const url = `${protocol}://${host}${baseUrl}/websocket/reconcile_${this.jobId}?token=${token}`;
      console.log("正在连接 WebSocket:", url);

      this.socket = new WebSocket(url);
      this.socket.onopen = this.websocketOnOpen;
      this.socket.onmessage = this.websocketOnMessage;
      this.socket.onerror = this.websocketOnError;
      this.socket.onclose = this.websocketOnClose;
    },

    websocketOnOpen() {
      console.log("WebSocket 连接成功");
      this.socketRetryCount = 0;
    },

    websocketOnMessage(event) {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'OBJ_PROGRESS') {
          const index = this.objList.findIndex(i => String(i.id) === String(msg.objLogId));

          if (index !== -1) {
            const target = { ...this.objList[index] };
            target.status = msg.status;
            target.progress = msg.percent;
            target.currentMsg = msg.message;

            this.$set(this.objList, index, target);

            if (msg.status === 'FINISHED' || msg.status === 'FAILED') {
              if (this.refreshTimer) clearTimeout(this.refreshTimer);
              this.refreshTimer = setTimeout(() => this.fetchData(), 1000);
            }
          }
        }
      } catch (e) {
        console.error("WS消息解析错误", e);
      }
    },

    websocketOnError(e) {
      console.error("WebSocket 连接异常");
    },

    websocketOnClose(e) {
      console.log("WebSocket 连接断开", e);
      this.socket = null;
      if (e.code !== 1000 && e.code !== 1008 && this.socketRetryCount < 3) {
        this.socketRetryCount++;
        setTimeout(() => {
          this.initWebSocket();
        }, 3000);
      }
    },

    disconnectSocket() {
      if (this.socket) {
        this.socket.close();
        this.socket = null;
      }
    },

    // 辅助状态样式方法
    getStatusTag(status) {
      const map = { 'WAITING': 'info', 'RUNNING': 'primary', 'FINISHED': 'success', 'FAILED': 'danger' };
      return map[status] || 'info';
    },
    getStatusIcon(status) {
      const map = { 'WAITING': 'el-icon-time', 'RUNNING': 'el-icon-loading', 'FINISHED': 'el-icon-check', 'FAILED': 'el-icon-close' };
      return map[status] || '';
    },
    getProcessStatus(status) {
      if (status === 'FINISHED') return 'success';
      if (status === 'FAILED') return 'exception';
      return '';
    },

    handlePreview(row) {
      if (row.diffCount === 0) {
        this.$message.info("恭喜，该对象没有发现任何差异数据！");
        return;
      }
      this.$refs.previewRef.init(row.id, row.objectName);
    },

    // 下载结果
    handleDownload(row) {
      const fileName = `reconcile_result_${row.objectName}.csv`;
      const loading = this.$loading({
        lock: true,
        text: '正在下载结果文件...',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      });

      request({
        url: '/salesforce/dataRunObjLog/downloadObj/' + row.id,
        method: 'get',
        responseType: 'blob',
        timeout: 60000
      }).then(async (res) => {
        loading.close();

        if (res.type === 'application/json') {
          const text = await res.text();
          const json = JSON.parse(text);
          this.$modal.msgError(json.msg || "下载失败，文件可能不存在");
          return;
        }

        const blob = new Blob([res]);
        if ('download' in document.createElement('a')) {
          const elink = document.createElement('a');
          elink.download = fileName;
          elink.style.display = 'none';
          elink.href = URL.createObjectURL(blob);
          document.body.appendChild(elink);
          elink.click();
          URL.revokeObjectURL(elink.href);
          document.body.removeChild(elink);
        } else {
          navigator.msSaveBlob(blob, fileName);
        }
        this.$message.success("下载已开始");
      }).catch(err => {
        loading.close();
        console.error("下载出错", err);
        this.$message.error("下载失败，请联系管理员");
      });
    },

    handleGoBack() {
      this.$router.push('/salesforce/reconcile');
    },

    handleRetry(row) {
      this.$confirm(`确认要重新执行对象【${row.objectLabel || row.objectName}】的比对任务吗?`, "警告", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning"
      }).then(() => {
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
          this.initWebSocket();
        }

        const index = this.objList.indexOf(row);
        if (index !== -1) {
          const newItem = { ...row, status: 'WAITING', progress: 0, currentMsg: '请求发送中...' };
          this.$set(this.objList, index, newItem);
        }

        retryObjLog(row.id).then(response => {
          this.$message.success("已加入执行队列");
        }).catch(() => {
          this.fetchData();
        });
      }).catch(() => { });
    },

    // 打开对象映射配置
    async handleConfig(row) {
      this.currentEditObj = row;
      this.configDrawerVisible = true;
      this.drawerLoading = true;

      try {
        if (!this.jobMeta.sourceOrgId) {
          const jobRes = await getJob(this.jobId);
          this.jobMeta = jobRes.data;
        }

        const confRes = await listConfigs(this.jobId);
        this.fullConfigList = confRes.data || [];

        this.currentEditConfig = this.fullConfigList.find(c => c.objectName === row.objectName);

        if (!this.currentEditConfig) {
          this.$message.warning("未找到该对象的原始配置数据。");
        }
      } catch (err) {
        console.error(err);
        this.$message.error("加载配置失败");
      } finally {
        this.drawerLoading = false;
      }
    },

    // 保存单个映射配置
    async saveSingleConfig() {
      this.savingConfig = true;
      try {
        await batchSaveConfigs(this.jobId, this.fullConfigList);
        this.$message.success("映射策略保存成功！您可以直接点击“重试”重新验证数据。");
        this.configDrawerVisible = false;
      } catch (err) {
        this.$message.error("保存失败");
      } finally {
        this.savingConfig = false;
      }
    }
  }
};
</script>

<style scoped lang="scss">
.monitor-container {
  background-color: #f5f7fa;
  min-height: calc(100vh - 84px);
  padding: 20px;
}

.header-card {
  margin-bottom: 15px;

  .page-title {
    font-size: 18px;
    font-weight: 600;
    color: #303133;
  }
}

.table-card {
  min-height: 500px;
}

.obj-name-text {
  font-weight: bold;
  color: #606266;
  font-size: 14px;
}

.progress-msg {
  font-size: 12px;
  color: #409EFF;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-msg {
  font-size: 12px;
  color: #F56C6C;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%; // 确保能触发截断
}

.error-log-container {
  background-color: #1e1e1e; // 暗黑背景
  color: #f56c6c; // 报错专属红字
  padding: 15px;
  border-radius: 6px;
  font-family: 'Consolas', 'Courier New', monospace; // 程序员最爱的等宽字体
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap; // 保留换行符并自动折行
  word-wrap: break-word;
  max-height: 400px; // 限制最大高度
  overflow-y: auto; // 内容过多时出滚动条
  border: 1px solid #dcdfe6;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.1);
}

// 定义滚动条的美化（仅在 webkit 浏览器生效，提升暗色容器的高级感）
.error-log-container::-webkit-scrollbar {
  width: 8px;
}

.error-log-container::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.error-log-container::-webkit-scrollbar-track {
  background: #1e1e1e;
}

.stats-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Consolas', monospace;
  padding: 8px 0;

  .stat-num {
    font-weight: bold;
    padding: 0 4px;
  }

  .source {
    color: #606266;
  }

  .target {
    color: #606266;
  }

  .divider {
    color: #DCDFE6;
    margin: 0 5px;
  }

  .arrow {
    color: #909399;
    margin: 0 8px;
  }

  .diff-badge {
    display: inline-flex;
    align-items: center;
    margin-left: 5px;

    ::v-deep .el-badge__content {
      position: static;
      transform: none;
      margin-left: 6px;
      border: none;
      height: 20px;
      line-height: 20px;
      border-radius: 10px;
      padding: 0 8px;
    }
  }
}
</style>