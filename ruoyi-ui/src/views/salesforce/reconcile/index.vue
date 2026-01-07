<template>
    <div class="app-container">
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
            <el-form-item label="任务名称" prop="jobName">
                <el-input v-model="queryParams.jobName" placeholder="搜索任务..." @keyup.enter.native="getList" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" icon="el-icon-search" @click="getList">搜索</el-button>
                <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
            </el-form-item>
        </el-form>

        <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
                <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新建任务</el-button>
            </el-col>
            <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
        </el-row>

        <el-table v-loading="loading" :data="jobList" border>
            <el-table-column label="任务名称" prop="jobName" show-overflow-tooltip min-width="150" />

            <el-table-column label="源环境" align="center" width="150">
                <template slot-scope="scope">{{ formatOrgName(scope.row.sourceOrgId) }}</template>
            </el-table-column>

            <el-table-column label="目标环境" align="center" width="150">
                <template slot-scope="scope">{{ formatOrgName(scope.row.targetOrgId) }}</template>
            </el-table-column>

            <el-table-column label="状态" align="center" width="220">
                <template slot-scope="scope">
                    <div v-if="scope.row.status === 'RUNNING'">
                        <el-progress :percentage="scope.row.progress || 0"
                            :status="scope.row.progressStatus"></el-progress>
                        <div class="progress-text">
                            <i class="el-icon-loading"></i> {{ scope.row.progressMsg || '正在连接/初始化...' }}
                        </div>
                    </div>
                    <el-tag v-else :type="scope.row.status === 'IDLE' ? 'info' : 'success'">{{ scope.row.status
                    }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="上次执行时间" align="center" prop="updateTime" width="160">
                <template slot-scope="scope">
                    <span>{{ parseTime(scope.row.updateTime) }}</span>
                </template>
            </el-table-column>

            <el-table-column label="操作" align="center" width="220" fixed="right">
                <template slot-scope="scope">
                    <template v-if="scope.row.status === 'RUNNING'">
                        <el-button size="mini" type="text" icon="el-icon-video-pause" class="text-warning"
                            @click="handleStop(scope.row)">强制停止</el-button>
                        <el-button size="mini" type="text" icon="el-icon-tickets"
                            @click="handleLogs(scope.row)">实时日志</el-button>
                    </template>

                    <template v-else>
                        <el-button size="mini" type="text" icon="el-icon-video-play" :loading="scope.row.starting"
                            @click="handleRun(scope.row)">
                            {{ scope.row.starting ? '启动中' : '启动' }}
                        </el-button>

                        <el-button size="mini" type="text" icon="el-icon-tickets"
                            @click="handleLogs(scope.row)">日志</el-button>
                        <el-button size="mini" type="text" icon="el-icon-edit"
                            @click="handleEdit(scope.row)">配置</el-button>
                        <el-button size="mini" type="text" icon="el-icon-delete" class="text-danger"
                            @click="handleDelete(scope.row)">删除</el-button>
                    </template>
                </template>
            </el-table-column>
        </el-table>

        <reconcile-wizard ref="wizard" :orgOptions="orgOptions" @success="getList" />

        <el-dialog title="执行历史" :visible.sync="logOpen" width="1000px" append-to-body>
            <el-table :data="logList" border height="400">
                <el-table-column label="开始时间" prop="startTime" width="160" />
                <el-table-column label="源数量" prop="totalSourceRows" width="100" align="center" />
                <el-table-column label="目标数量" prop="totalTargetRows" width="100" align="center" />
                <el-table-column label="差异数" prop="diffRowCount" width="100" align="center">
                    <template slot-scope="scope">
                        <span class="text-danger font-bold" v-if="scope.row.diffRowCount > 0">{{ scope.row.diffRowCount
                        }}</span>
                        <span class="text-success" v-else>0</span>
                    </template>
                </el-table-column>
                <el-table-column label="状态" prop="status" width="100" align="center">
                    <template slot-scope="scope">
                        <el-tag
                            :type="scope.row.status === 'SUCCESS' ? 'success' : (scope.row.status === 'PROCESSING' ? 'warning' : 'danger')">
                            {{ scope.row.status }}
                        </el-tag>
                    </template>
                </el-table-column>
                <el-table-column label="操作" align="center" width="180">
                    <template slot-scope="scope">
                        <el-button v-if="scope.row.resultFilePath" type="text" icon="el-icon-download"
                            @click="handleDownload(scope.row)">下载报告</el-button>
                        <el-button v-if="hasPreviewData(scope.row)" type="text" icon="el-icon-view"
                            @click="handlePreview(scope.row)">差异预览</el-button>
                        <el-tooltip v-if="scope.row.status === 'FAILED' && scope.row.errorMsg"
                            :content="scope.row.errorMsg" placement="top">
                            <span class="text-danger" style="margin-left:5px;cursor:pointer"><i
                                    class="el-icon-warning"></i> 报错</span>
                        </el-tooltip>
                    </template>
                </el-table-column>
            </el-table>
        </el-dialog>

        <el-dialog title="差异数据透视 (前50条)" :visible.sync="previewOpen" width="1100px" append-to-body>
            <el-table :data="previewData" border height="500" stripe>
                <el-table-column prop="key" label="关联键 (ID)" width="180" fixed />
                <el-table-column prop="type" label="差异类型" width="120">
                    <template slot-scope="scope">
                        <el-tag :type="getDiffTypeTag(scope.row.type)">{{ scope.row.type }}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="field" label="字段名" width="150" show-overflow-tooltip />
                <el-table-column label="源环境值" min-width="200" show-overflow-tooltip>
                    <template slot-scope="scope"><span class="bg-src">{{ scope.row.srcVal }}</span></template>
                </el-table-column>
                <el-table-column label="目标环境值" min-width="200" show-overflow-tooltip>
                    <template slot-scope="scope"><span class="bg-tgt">{{ scope.row.tgtVal }}</span></template>
                </el-table-column>
            </el-table>
            <div slot="footer">
                <el-button @click="previewOpen = false">关 闭</el-button>
            </div>
        </el-dialog>

    </div>
</template>

<script>
import { listJob, delJob, runJob, listLogs, updateJob } from "@/api/salesforce/reconcile";
import { listOrg } from "@/api/salesforce/org";
import ReconcileWizard from "./wizard";
import { getToken } from "@/utils/auth";

export default {
    components: { ReconcileWizard },
    data() {
        return {
            // 【修复1】必须初始化为 true，否则搜索栏不显示
            showSearch: true,

            loading: false,
            jobList: [],
            orgOptions: [],
            queryParams: {
                pageNum: 1,
                pageSize: 10,
                jobName: undefined
            },
            // Logs
            logOpen: false,
            logList: [],
            // Preview
            previewOpen: false,
            previewData: [],
            // WebSocket
            websockets: {},
        };
    },
    created() {
        this.getOrgList();
        this.getList();
    },
    beforeDestroy() {
        Object.values(this.websockets).forEach(ws => ws.close());
    },
    methods: {
        getList() {
            this.loading = true;
            listJob(this.queryParams).then(res => {
                this.jobList = res.rows.map(row => ({
                    ...row,
                    progress: 0,
                    progressMsg: '',
                    progressStatus: null
                }));
                this.loading = false;
                // 检查运行中任务并建立连接
                this.checkRunningJobs();
            });
        },
        resetQuery() {
            this.queryParams = { pageNum: 1, pageSize: 10, jobName: undefined };
            this.getList();
        },

        // ... (getOrgList, formatOrgName, handleAdd, handleEdit, handleDelete 等保持不变) ...
        // 请直接复用你之前的代码，这里为了节省篇幅简写
        getOrgList() { listOrg({ pageNum: 1, pageSize: 100 }).then(res => this.orgOptions = res.rows); },
        formatOrgName(orgId) { const org = this.orgOptions.find(item => item.id === orgId); return org ? org.name : orgId; },
        handleAdd() { this.$refs.wizard.openWizard(); },
        handleEdit(row) { this.$refs.wizard.openWizard(row.id); },
        handleDelete(row) {
            this.$modal.confirm('确认删除？').then(() => {
                delJob(row.id).then(() => { this.getList(); this.$modal.msgSuccess("删除成功"); });
            });
        },

        /** 启动任务 */
        handleRun(row) {
            this.$modal.confirm(`确认启动任务 "${row.jobName}"？`).then(() => {
                this.$set(row, 'starting', true); // 按钮 loading

                runJob(row.id).then(response => {
                    // 1. 接口返回成功，说明后台异步线程已启动
                    this.$modal.msgSuccess("任务已提交后台执行");

                    // 2. 立即更新界面状态，防止用户重复点击
                    row.status = 'RUNNING';
                    row.progress = 0;
                    row.progressMsg = '正在初始化...';

                    // 3. 建立 WebSocket 监听后续进度
                    this.connectWebSocket(row);
                }).catch(error => {
                    // 接口报错（如正在运行中）
                    console.error(error);
                }).finally(() => {
                    this.$set(row, 'starting', false);
                });
            }).catch(() => { });
        },

        /** 强制停止 */
        handleStop(row) {
            this.$confirm('确定要终止该任务吗？后台进程将被中断。', '警告', {
                confirmButtonText: '确定终止',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                // 将状态置为 IDLE，后台 Service 检测到 IDLE 会自动中断循环
                updateJob({ id: row.id, status: 'IDLE' }).then(() => {
                    this.$modal.msgSuccess("停止指令已发送");
                    // 主动断开 WS
                    if (this.websockets[row.id]) {
                        this.websockets[row.id].close();
                        delete this.websockets[row.id];
                    }
                    // 刷新列表
                    this.getList();
                });
            });
        },

        // --- WebSocket 核心逻辑 ---
        checkRunningJobs() {
            this.jobList.forEach(job => {
                if (job.status === 'RUNNING' && !this.websockets[job.id]) {
                    this.connectWebSocket(job);
                }
            });
        },
        connectWebSocket(job) {
            // 防止重复连接
            if (this.websockets[job.id]) return;

            const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
            const host = window.location.host;
            const wsUrl = `${protocol}://${host}${process.env.VUE_APP_BASE_API}/websocket/deploy/reconcile_${job.id}?token=${getToken()}`;

            const ws = new WebSocket(wsUrl);

            ws.onopen = () => {
                console.log(`Job ${job.id} 连接成功`);
            };

            ws.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);

                    // ==========================================
                    // 【核心修复】: 不要更新 job 参数，而是去当前列表中找
                    // ==========================================
                    // 1. 在当前的 jobList 中查找 ID 匹配的行
                    // 注意：使用 == 而不是 ===，防止一个是数字一个是字符串
                    const currentJob = this.jobList.find(item => item.id == data.jobId);

                    if (currentJob) {
                        // 更新页面上正在显示的那个对象
                        currentJob.progress = data.percent;
                        currentJob.progressMsg = data.msg;

                        // 打印日志方便确认
                        console.log(`更新进度: ID=${data.jobId}, 进度=${data.percent}%`);

                        // 结束状态处理
                        if (data.stage === 'ALL_DONE' || data.percent === 100) {
                            this.$modal.msgSuccess("比对完成");
                            this.closeSocket(data.jobId); // 这里的 job.id 改为 data.jobId 更稳健
                            this.getList();
                        } else if (data.stage === 'ERROR' || data.stage === 'STOPPED') {
                            this.$modal.msgError(data.msg);
                            currentJob.progressStatus = 'exception';
                            this.closeSocket(data.jobId);
                            // 延迟刷新，让用户看清错误信息
                            setTimeout(() => this.getList(), 2000);
                        }
                    } else {
                        // 这种情况就是：消息来了，但列表里没有这个任务（比如翻页了），不用管
                        console.log("后台任务运行中，但不在当前列表页:", data.jobId);
                    }

                } catch (e) {
                    console.error("WS解析错误", e);
                }
            };

            ws.onerror = () => {
                console.log("WS连接断开");
            };

            ws.onclose = () => {
                delete this.websockets[job.id];
            };

            this.websockets[job.id] = ws;
        },

        // 别忘了补充 closeSocket 方法
        closeSocket(jobId) {
            if (this.websockets[jobId]) {
                this.websockets[jobId].close();
                delete this.websockets[jobId];
            }
        },
        // --- 日志与预览 ---
        handleLogs(row) {
            this.logOpen = true;
            listLogs({ jobId: row.id }).then(res => this.logList = res.rows);
        },
        handleDownload(row) {
            const url = process.env.VUE_APP_BASE_API + "/salesforce/reconcile/download/" + row.id;
            window.open(url);
        },
        hasPreviewData(row) {
            // 简单判断 ErrorMsg 是否包含 JSON 数据 (以 [ 开头)
            return row.errorMsg && row.errorMsg.trim().startsWith('[');
        },
        handlePreview(row) {
            try {
                this.previewData = JSON.parse(row.errorMsg);
                this.previewOpen = true;
            } catch (e) {
                this.$modal.msgError("解析预览数据失败，可能非标准JSON格式");
            }
        },
        getDiffTypeTag(type) {
            if (type && type.includes('MISSING')) return 'danger';
            if (type === 'VALUE_DIFF') return 'warning';
            return '';
        }
    }
};
</script>

<style scoped>
.progress-text {
    font-size: 12px;
    color: #909399;
    margin-top: 5px;
}

.font-bold {
    font-weight: bold;
}

.text-warning {
    color: #E6A23C;
}

.bg-src {
    background-color: #f0f9eb;
    padding: 2px 5px;
    border-radius: 3px;
    color: #67c23a;
}

.bg-tgt {
    background-color: #fef0f0;
    padding: 2px 5px;
    border-radius: 3px;
    color: #f56c6c;
}

.mb8 {
    margin-bottom: 8px;
}
</style>