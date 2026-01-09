<template>
  <div class="app-container monitor-container">
    <el-card shadow="never" class="header-card">
      <div slot="header" class="clearfix">
        <span class="page-title">
          <i class="el-icon-monitor" style="margin-right: 8px;"></i>任务执行控制台
        </span>
        <div style="float: right;">
          <el-button plain size="small" icon="el-icon-back" @click="handleGoBack">返回列表</el-button>
        </div>
      </div>

      <div class="job-meta">
        <el-descriptions :column="4" border size="medium">
          <el-descriptions-item label="任务名称">
            <span style="font-weight: bold">{{ jobName || '-' }}</span>
            <el-tag size="mini" type="info" style="margin-left: 5px">#{{ jobId }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag :type="statusTagType" effect="dark">{{ overallStatus }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总进度">
            <div style="width: 200px">
              <el-progress :percentage="calcTotalProgress" :status="overallStatus === 'FINISHED' ? 'success' : ''"
                :stroke-width="10"></el-progress>
            </div>
          </el-descriptions-item>
          <el-descriptions-item label="对象数量">
            <span style="font-weight: bold">{{ objList.length }}</span> 个对象
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

      <el-table :data="objList" v-loading="loading" stripe border highlight-current-row row-key="id"
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
  </div>
</template>

<script>
import { getJobMonitor, downloadObjUrl, retryObjLog } from "@/api/salesforce/dataRunObjLog";
import PreviewResult from './preview';
import { getToken } from "@/utils/auth"; // 必须引入 Token
import request from '@/utils/request';

export default {
  name: "JobMonitor",
  components: { PreviewResult },
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
      socketRetryCount: 0, // 增加重连计数防止死循环
    };
  },
  computed: {
    // ... (保持不变)
    // 【优化】计算总进度：基于所有对象的进度均值
    calcTotalProgress() {
      const totalCount = this.objList.length;
      if (totalCount === 0) return 0;

      // 累加所有对象的 progress 字段
      // 如果对象是 FINISHED 或 FAILED，视为 100%
      // 如果是 WAITING，视为 0%
      // 如果是 RUNNING，取实际 progress
      const sumProgress = this.objList.reduce((sum, item) => {
        let p = 0;
        if (item.status === 'FINISHED' || item.status === 'FAILED') {
          p = 100;
        } else if (item.status === 'RUNNING') {
          p = item.progress || 0;
        }
        return sum + p;
      }, 0);

      // 计算平均值
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

    this.fetchData();
    // 5秒轮询兜底 (防止WS断连后界面不更新)
    this.timer = setInterval(this.fetchData, 5000);
  },
  beforeDestroy() {
    this.disconnectSocket();
    if (this.timer) clearInterval(this.timer);
  },
  methods: {
    // ... (fetchData 等业务方法保持不变) ...
    fetchData() {
      getJobMonitor(this.jobId).then(res => {
        const dataWrapper = res.data || {};
        if (dataWrapper.jobName) this.jobName = dataWrapper.jobName;
        const newData = Array.isArray(dataWrapper) ? dataWrapper : (dataWrapper.list || []);

        if (this.objList.length === 0) {
          this.objList = newData;
        } else {
          newData.forEach(newItem => {
            // 使用 String 转换确保 ID 类型匹配
            const index = this.objList.findIndex(i => String(i.id) === String(newItem.id));
            if (index !== -1) {
              const oldItem = this.objList[index];

              // --- 核心修复：防止进度回跳 (Jitter Fix) ---
              // 只有当新数据的进度 >= 旧进度，或者状态发生了变更（如从 RUNNING 变为了 FINISHED）时，才更新
              // 这样可以防止轮询到的“旧数据”覆盖掉 WebSocket 推送的“新数据”
              let shouldUpdate = false;

              // 1. 状态变了，必须更新
              if (newItem.status !== oldItem.status) {
                shouldUpdate = true;
              }
              // 2. 状态没变，但进度前进了，更新
              else if (newItem.progress > oldItem.progress) {
                shouldUpdate = true;
              }
              // 3. 差异数变了，更新
              else if (newItem.diffCount !== oldItem.diffCount) {
                shouldUpdate = true;
              }

              // 如果需要更新，或者强制覆盖其他字段（如 label）
              if (shouldUpdate) {
                // 注意：这里我们保留 oldItem 中可能存在的 currentMsg (实时消息)，
                // 因为接口通常不返回实时的 currentMsg，只返回 errorMsg
                const mergedItem = {
                  ...oldItem,
                  ...newItem,
                  // 保护 progress 不被回滚：取最大值
                  progress: Math.max(oldItem.progress || 0, newItem.progress || 0)
                };
                this.$set(this.objList, index, mergedItem);
              }
            } else {
              this.objList.push(newItem);
            }
          });
        }

        this.updateOverallStatus();

        // 智能连接 WebSocket
        if (this.overallStatus === 'RUNNING' || this.overallStatus === 'WAITING') {
          this.initWebSocket();
        } else {
          if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.disconnectSocket();
          }
        }

      }).catch(err => {
        console.error("获取监控数据失败", err);
      });
    },
    updateOverallStatus() {
      if (this.objList.length === 0) {
        this.overallStatus = 'WAITING';
        return;
      }
      const hasRunning = this.objList.some(i => i.status === 'RUNNING');
      const hasWaiting = this.objList.some(i => i.status === 'WAITING');
      const allFinished = this.objList.every(i => i.status === 'FINISHED' || i.status === 'FAILED');

      if (hasRunning || hasWaiting) {
        this.overallStatus = 'RUNNING';
      } else if (allFinished) {
        this.overallStatus = 'FINISHED';
        if (this.timer) clearInterval(this.timer);
      }
    },

    // --- 核心优化：WebSocket 连接逻辑 (参考 detail.vue) ---
    initWebSocket() {
      if (!this.jobId) return;
      // 如果已经连接或正在连接，不再创建新的
      if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
        return;
      }

      // 1. 自动判断协议 (ws/wss)
      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      // 2. 获取当前域名 (如 localhost:80, 192.168.1.5:8080)
      const host = window.location.host;
      // 3. 获取 API 基础路径 (如 /dev-api 或 /prod-api)
      const baseUrl = process.env.VUE_APP_BASE_API;
      // 4. 获取 Token
      const token = getToken();

      // 5. 拼接完整 URL
      // 注意：这里必须拼接 "reconcile_" 前缀，这是后端区分不同业务的关键
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
          // console.log("收到进度:", msg); 
          // ID 类型安全匹配
          const index = this.objList.findIndex(i => String(i.id) === String(msg.objLogId));

          if (index !== -1) {
            const target = { ...this.objList[index] };
            target.status = msg.status;
            target.progress = msg.percent;
            target.currentMsg = msg.message;

            // 强制刷新视图 (Vue 2 关键)
            this.$set(this.objList, index, target);

            // 状态变更后，触发一次全量刷新以更新统计数字
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
      // 错误处理交由 onClose 统一触发重连
    },

    websocketOnClose(e) {
      console.log("WebSocket 连接断开", e);
      this.socket = null;
      // 避免死循环重连：仅在非正常关闭且重试次数少于3次时重连
      if (e.code !== 1000 && e.code !== 1008 && this.socketRetryCount < 3) {
        this.socketRetryCount++;
        setTimeout(() => {
          console.log("尝试重连 WebSocket...");
          this.initWebSocket();
        }, 3000);
      }
    },

    disconnectSocket() {
      if (this.socket) {
        this.socket.close(); // 正常关闭 code=1000
        this.socket = null;
      }
    },

    // ... (辅助方法保持不变) ...
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
    // 下载比对结果 (修复 401 认证失败问题)
    handleDownload(row) {
      const fileName = `reconcile_result_${row.objectName}.csv`;

      // 显示加载遮罩
      const loading = this.$loading({
        lock: true,
        text: '正在下载结果文件...',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      });

      request({
        url: '/salesforce/dataRunObjLog/downloadObj/' + row.id,
        method: 'get',
        responseType: 'blob', // 【关键】必须指定响应类型为 blob
        timeout: 60000 // 防止大文件下载超时
      }).then(async (res) => {
        loading.close();

        // 检查是否返回了 JSON 格式的错误信息 (例如文件不存在)
        if (res.type === 'application/json') {
          const text = await res.text();
          const json = JSON.parse(text);
          this.$modal.msgError(json.msg || "下载失败，文件可能不存在");
          return;
        }

        // 处理文件流下载
        const blob = new Blob([res]);
        if ('download' in document.createElement('a')) {
          // 非 IE 下载
          const elink = document.createElement('a');
          elink.download = fileName;
          elink.style.display = 'none';
          elink.href = URL.createObjectURL(blob);
          document.body.appendChild(elink);
          elink.click();
          URL.revokeObjectURL(elink.href); // 释放 URL 对象
          document.body.removeChild(elink);
        } else {
          // IE10+ 下载
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
        // 如果断开了，尝试立即重连
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
          console.log("重试触发，主动建立连接...");
          this.initWebSocket();
        }

        // 乐观更新
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
    /* 1. 设置为 Flex 容器，让内部的文字和 Badge 数字水平排列 */
    display: inline-flex;
    align-items: center;
    /* 垂直居中 */
    margin-left: 5px;
    /* 整体与左侧箭头的距离 */

    /* 2. 深度选择器修改 Element UI 内部样式 */
    ::v-deep .el-badge__content {
      /* 关键：取消默认的绝对定位，变为流式布局 */
      position: static;
      transform: none;
      /* 移除默认的缩放位移 */

      /* 样式微调 */
      margin-left: 6px;
      /* 文字“差异”与数字之间的间距 */
      border: none;
      /* 移除默认白边，视觉更干净 */

      /* 3. 大数字适配：防止数字过大导致变形 */
      height: 20px;
      line-height: 20px;
      border-radius: 10px;
      padding: 0 8px;
      /* 左右留足空间，数字再大也能撑开 */

      /* 4. 可选：如果你希望数字稍微偏上一点点，不像现在这么正中，可以加下面这行 */
      /* transform: translateY(-1px); */
    }
  }
}
</style>