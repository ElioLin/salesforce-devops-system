<template>
    <div class="app-container">
        <el-card shadow="never" class="mb-20" v-loading="loading">
            <div slot="header" class="clearfix">
                <span style="font-weight: bold; font-size: 16px">{{ deployment.title || '部署包详情' }}</span>
                <el-tag size="small" :type="statusType(deployment.status)" style="margin-left: 10px">{{
                    deployment.status
                    }}</el-tag>
                <div style="float: right;">
                    <el-button type="info" icon="el-icon-refresh" size="mini" @click="refreshData"
                        :disabled="isPolling">刷新</el-button>
                    <el-button type="primary" icon="el-icon-arrow-left" size="mini" @click="handleBack">返回列表</el-button>
                </div>
            </div>

            <el-row :gutter="20">
                <el-col :span="6">
                    <div class="label">源环境:</div> {{ sourceOrgName }}
                </el-col>
                <el-col :span="6">
                    <div class="label">目标环境:</div> {{ targetOrgName }}
                </el-col>
                <el-col :span="12" style="text-align: right">
                    <el-button type="primary" plain icon="el-icon-view" :disabled="isProcessing" 
                        @click="handlePreviewPackage">
                        预览部署包
                    </el-button>

                    <el-button type="warning" icon="el-icon-video-play" :disabled="isProcessing" :loading="validating"
                        @click="handleDeploy(true)">
                        仅验证
                    </el-button>
                    <el-button type="success" icon="el-icon-upload" :disabled="isProcessing" :loading="deploying"
                        @click="handleDeploy(false)">
                        完整部署
                    </el-button>
                    <el-button v-if="deployment.status === 'Succeeded'" type="primary" icon="el-icon-lightning"
                        :disabled="isProcessing" @click="handleQuickDeploy">
                        快速部署
                    </el-button>
                </el-col>
            </el-row>

            <div style="margin-top: 20px; border-top: 1px solid #ebeef5; padding-top: 20px;">
                <el-form label-width="100px" size="small">
                  <el-row>
                    <el-col :span="8">
                      <el-form-item label="测试级别">
                        <el-select v-model="deployment.testLevel" placeholder="请选择测试级别" style="width: 100%">
                          <el-option label="默认 (NoTestRun / Default)" value="NoTestRun" />
                          <el-option label="运行本地测试 (RunLocalTests)" value="RunLocalTests" />
                          <el-option label="指定测试类 (RunSpecifiedTests)" value="RunSpecifiedTests" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12" v-if="deployment.testLevel === 'RunSpecifiedTests'">
                      <el-form-item label="指定测试类">
                        <el-input v-model="deployment.specifiedTests" placeholder="请输入测试类名，多个用逗号分隔" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="4" style="padding-left: 10px;">
                      <el-button type="primary" icon="el-icon-check" plain @click="handleSaveConfig">保存配置</el-button>
                    </el-col>
                  </el-row>
                </el-form>
            </div>

            <div v-if="isProcessing || progressStatus" style="margin-top: 10px;">
                <p style="font-size: 14px; font-weight: bold; transition: all 0.3s;" :style="{ color: statusColor }">
                    <i :class="statusIcon"></i>
                    {{ progressText }}
                </p>
                <el-progress v-if="progressStatus !== 'success'" :percentage="deployProgress" :status="progressStatus"
                    :stroke-width="18" text-inside></el-progress>
            </div>

            <el-alert v-if="deployment.status === 'Failed' && deployment.errorMsg" title="部署/验证失败原因" type="error"
                :description="deployment.errorMsg" show-icon style="margin-top: 15px;">
            </el-alert>
        </el-card>

        <el-card shadow="never">
            <div slot="header" class="clearfix">
                <span>包含的元数据 ({{ itemList.length }})</span>
                <div style="float: right;">
                    <el-button type="warning" plain icon="el-icon-refresh-left" size="mini" :loading="isCheckingStatus"
                        @click="handleCheckStatus" :disabled="!deployment.targetOrgId">重新计算差异</el-button>

                    <el-button style="margin-left: 10px;" type="text" icon="el-icon-plus" :disabled="isProcessing"
                        @click="openMetadataBrowser">
                        添加元数据
                    </el-button>
                </div>
            </div>

            <el-table v-loading="loadingItems" :data="itemList" border style="width: 100%">
                <el-table-column label="类型" prop="metadataType" width="200" sortable />
                <el-table-column label="名称" prop="memberName" sortable />
                <el-table-column label="所属对象" width="150">
                    <template slot-scope="scope">{{ getParentName(scope.row.memberName) }}</template>
                </el-table-column>
                <el-table-column label="差异状态" align="center" width="120">
                    <template slot-scope="scope">
                        <el-tooltip :content="scope.row.diffStatus || 'Unknown'" placement="top">
                            <i :class="getDiffIcon(scope.row.diffStatus)"
                                :style="{ color: getDiffColor(scope.row.diffStatus), fontSize: '18px', fontWeight: 'bold' }"></i>
                        </el-tooltip>
                        <span style="margin-left:5px">{{ scope.row.diffStatus }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="管理" width="180" align="center">
                    <template slot-scope="scope">
                        <el-button size="mini" type="text" icon="el-icon-connection" :disabled="!deployment.targetOrgId"
                            @click="handleDiff(scope.row)">比对</el-button>
                        <el-button size="mini" type="text" icon="el-icon-delete" class="text-danger"
                            :disabled="isProcessing" @click="handleRemoveItem(scope.row)">移除</el-button>
                    </template>
                </el-table-column>
            </el-table>
        </el-card>

        <metadata-browser ref="metaBrowser" @auto-action="handleBrowserAction" @view-code="handleBrowserViewCode"
            @diff-code="handleBrowserDiffCode" />

        <el-dialog :title="previewTitle" :visible.sync="openCode" width="80%" append-to-body>
            <monaco-editor v-if="openCode" :value="codeContent" :original="oldCodeContent" :diffEditor="isDiffMode"
                language="java" height="600px" theme="vs-dark" />
            <div slot="footer" class="dialog-footer">
                <el-button @click="openCode = false">关 闭</el-button>
            </div>
        </el-dialog>

        <el-dialog title="部署包内容预览 (Check Only)" :visible.sync="previewDialog.open" width="80%" append-to-body>
            <div v-loading="previewDialog.loading" style="min-height: 300px;">
                <el-row :gutter="20">
                    <el-col :span="10">
                        <div class="file-list-header">ZIP 包内文件清单 ({{previewDialog.files.length}}个)</div>
                        <div class="file-list-container">
                            <ul class="file-ul">
                                <li v-for="(file, index) in previewDialog.files" :key="index" class="file-li">
                                    <i class="el-icon-document" style="margin-right:5px; color:#909399;"></i>
                                    {{ file }}
                                </li>
                            </ul>
                        </div>
                    </el-col>
                    <el-col :span="14">
                        <div class="file-list-header">package.xml 内容</div>
                        <monaco-editor 
                            v-if="previewDialog.open"
                            :value="previewDialog.packageXml" 
                            :readOnly="true"
                            language="xml" 
                            height="600px" 
                            theme="vs-dark" 
                        />
                    </el-col>
                </el-row>
            </div>
            <div slot="footer" class="dialog-footer">
                <el-button @click="previewDialog.open = false">关 闭</el-button>
            </div>
        </el-dialog>

    </div>
</template>

<script>
import {
    getDeployment,
    listDeploymentItems,
    addDeploymentItems,
    removeDeploymentItems,
    deployPackage,
    checkDeployStatus,
    quickDeploy,
    checkDiffStatus,
    updateDeployment,
    previewDeploymentPackage // 【新增】
} from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import MetadataBrowser from "@/views/salesforce/org/MetadataBrowser";
import MonacoEditor from '@/components/MonacoEditor';
import request from '@/utils/request';

export default {
    name: "DeploymentDetail",
    components: { MetadataBrowser, MonacoEditor },
    data() {
        return {
            deploymentId: null,
            deployment: {
                testLevel: 'NoTestRun',
                specifiedTests: ''
            },
            itemList: [],
            orgMap: {},

            loading: false,
            loadingItems: false,
            validating: false,
            deploying: false,
            isCheckingStatus: false,

            dbTimer: null,
            sfTimer: null,
            statusTimer: null,

            deployProgress: 0,
            progressText: "",
            progressStatus: null,

            openCode: false,
            codeContent: "",
            oldCodeContent: "",
            isDiffMode: false,
            previewTitle: "",

            // 【新增】预览相关数据
            previewDialog: {
                open: false,
                loading: false,
                files: [],
                packageXml: ''
            }
        };
    },
    computed: {
        sourceOrgName() {
            if (!this.deployment || !this.deployment.sourceOrgId) return '-';
            return this.orgMap[this.deployment.sourceOrgId] || this.deployment.sourceOrgId;
        },
        targetOrgName() {
            if (!this.deployment || !this.deployment.targetOrgId) return '-';
            return this.orgMap[this.deployment.targetOrgId] || this.deployment.targetOrgId;
        },
        isProcessing() {
            const s = this.deployment.status;
            return s === 'Processing' || s === 'Deploying' || s === 'Validating' || this.validating || this.deploying;
        },
        isPolling() {
            return this.dbTimer !== null || this.sfTimer !== null;
        },
        statusColor() {
            if (this.progressStatus === 'success') return '#67C23A';
            if (this.progressStatus === 'exception') return '#F56C6C';
            return '#409EFF';
        },
        statusIcon() {
            if (this.progressStatus === 'success') return 'el-icon-circle-check';
            if (this.progressStatus === 'exception') return 'el-icon-circle-close';
            return 'el-icon-loading';
        }
    },
    created() {
        this.deploymentId = this.$route.query.id || this.$route.params.id;
        if (this.deploymentId) {
            this.initData();
        } else {
            this.$modal.msgError("缺少部署包ID");
        }
    },
    beforeDestroy() {
        this.stopAllPolling();
        if (this.statusTimer) clearInterval(this.statusTimer);
    },
    methods: {
        initData() {
            this.loading = true;
            const p1 = listOrg({ pageNum: 1, pageSize: 100 }).then(res => {
                res.rows.forEach(org => {
                    this.$set(this.orgMap, org.id, org.name);
                });
            });
            const p2 = this.getDetail();
            const p3 = this.getItems();

            Promise.all([p1, p2, p3]).finally(() => {
                this.loading = false;
                if (this.isProcessing) {
                    this.recoverPollingStatus();
                }
            });
        },

        refreshData() {
            this.loading = true;
            Promise.all([this.getDetail(), this.getItems()]).finally(() => {
                this.loading = false;
            });
        },

        getDetail() {
            return getDeployment(this.deploymentId).then(res => {
                this.deployment = res.data || {};
            });
        },

        getItems() {
            this.loadingItems = true;
            return listDeploymentItems(this.deploymentId).then(res => {
                this.itemList = res.data || [];
                this.loadingItems = false;
            });
        },

        /** 解析 Parent Name */
        getParentName(name) {
            if (name && name.includes('.')) {
                return name.split('.')[0];
            }
            return '-';
        },

        handleSaveConfig() {
            const data = {
                id: this.deploymentId,
                testLevel: this.deployment.testLevel,
                specifiedTests: this.deployment.specifiedTests
            };
            updateDeployment(data).then(res => {
                this.$modal.msgSuccess("配置已保存");
            });
        },

        /** 【新增】点击预览部署包 */
        handlePreviewPackage() {
            this.previewDialog.open = true;
            this.previewDialog.loading = true;
            this.previewDialog.files = [];
            this.previewDialog.packageXml = '';

            previewDeploymentPackage(this.deploymentId).then(res => {
                const data = res.data; // { files: [], packageXml: "..." }
                this.previewDialog.files = data.files || [];
                this.previewDialog.packageXml = data.packageXml || '无内容';
                this.previewDialog.loading = false;
            }).catch(() => {
                this.previewDialog.loading = false;
            });
        },

        handleCheckStatus() {
            if (!this.deployment.targetOrgId) {
                this.$modal.msgError("请先设置目标环境");
                return;
            }
            this.isCheckingStatus = true;
            checkDiffStatus(this.deploymentId).then(res => {
                this.$modal.msgSuccess("计算已在后台开始...");
                this.getItems();
                this.startStatusPolling();
            }).catch(() => {
                this.isCheckingStatus = false;
            });
        },

        startStatusPolling() {
            if (this.statusTimer) clearInterval(this.statusTimer);
            this.statusTimer = setInterval(() => {
                listDeploymentItems(this.deploymentId).then(res => {
                    this.itemList = res.data || [];
                    const hasComparing = this.itemList.some(item => item.diffStatus === 'Comparing');
                    if (!hasComparing) {
                        clearInterval(this.statusTimer);
                        this.statusTimer = null;
                        this.isCheckingStatus = false;
                        this.$modal.msgSuccess("状态计算完成");
                    }
                });
            }, 3000);
        },

        getDiffIcon(status) {
            if (status === 'New') return 'el-icon-circle-plus';
            if (status === 'Changed') return 'el-icon-warning';
            if (status === 'Same') return 'el-icon-success';
            if (status === 'Invalid') return 'el-icon-error';
            if (status === 'Comparing') return 'el-icon-loading';
            return 'el-icon-question';
        },
        getDiffColor(status) {
            if (status === 'New') return '#67C23A';
            if (status === 'Changed') return '#E6A23C';
            if (status === 'Same') return '#909399';
            if (status === 'Invalid') return '#F56C6C';
            return '#409EFF';
        },

        handleDiff(row) {
            if (!this.deployment.targetOrgId) {
                this.$modal.msgWarning("请先设置部署包的目标环境，才能进行比对！");
                return;
            }
            const loading = this.$loading({
                lock: true,
                text: '正在从源环境和目标环境同时拉取代码，请稍候...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            request({
                url: '/system/sf/meta/compare',
                method: 'get',
                params: {
                    sourceOrgId: this.deployment.sourceOrgId,
                    targetOrgId: this.deployment.targetOrgId,
                    type: row.metadataType,
                    name: row.memberName
                }
            }).then(response => {
                loading.close();
                const diffData = response.data;
                this.codeContent = diffData.sourceContent;
                this.oldCodeContent = diffData.targetContent;
                this.isDiffMode = true;
                this.previewTitle = `比对: ${row.memberName} (${row.metadataType}) [左:目标环境 vs 右:源环境]`;
                this.openCode = true;
            }).catch(() => {
                loading.close();
            });
        },

        handleDeploy(checkOnly) {
            const actionName = checkOnly ? "验证" : "部署";
            this.$confirm(`确认要执行【${actionName}】操作吗？`, "警告", {
                confirmButtonText: "确定",
                cancelButtonText: "取消",
                type: "warning"
            }).then(() => {
                if (checkOnly) this.validating = true;
                else this.deploying = true;
                deployPackage(this.deploymentId, checkOnly).then(res => {
                    this.$modal.msgSuccess(`${actionName}请求已提交，正在后台处理...`);
                    this.deployProgress = 5;
                    this.progressText = "正在准备元数据并从源环境提取...";
                    this.progressStatus = null;
                    this.getDetail();
                    this.startDbPolling(checkOnly);
                }).catch(() => {
                    this.validating = false;
                    this.deploying = false;
                });
            });
        },

        handleQuickDeploy() {
            this.$confirm('将使用上次验证成功的 ID 进行快速部署（免上传），确认吗？', "快速部署", {
                confirmButtonText: "立即部署",
                cancelButtonText: "取消",
                type: "success"
            }).then(() => {
                this.deploying = true;
                quickDeploy(this.deploymentId).then(res => {
                    this.$modal.msgSuccess("快速部署已启动！");
                    this.deployProgress = 0;
                    this.progressText = "正在启动快速部署...";
                    this.progressStatus = null;
                    this.getDetail();
                    this.startDbPolling(false);
                }).catch(() => {
                    this.deploying = false;
                });
            });
        },

        startDbPolling(checkOnly) {
            this.stopAllPolling();
            this.dbTimer = setInterval(() => {
                getDeployment(this.deploymentId).then(res => {
                    const data = res.data;
                    this.deployment = data;
                    if (this.deployProgress < 30) this.deployProgress += 2;
                    if (data.status === 'Processing') {
                        this.progressText = "正在从源环境提取代码 (Retrieve)...";
                    }
                    else if (data.status === 'Deploying' || data.status === 'Validating') {
                        clearInterval(this.dbTimer);
                        this.dbTimer = null;
                        this.deployProgress = 40;
                        this.progressText = "代码已推送至目标环境，等待 Salesforce 处理...";
                        if (data.lastAsyncId) {
                            this.startSfPolling(data.lastAsyncId);
                        } else {
                            this.$modal.msgError("状态异常：未获取到 Salesforce Process ID");
                            this.resetButtonState();
                        }
                    }
                    else if (data.status === 'Failed') {
                        this.handleDeployFailed(data.errorMsg);
                    }
                });
            }, 2000);
        },

        startSfPolling(processId) {
            this.sfTimer = setInterval(() => {
                checkDeployStatus(this.deployment.targetOrgId, processId).then(res => {
                    let result = res.msg;
                    try {
                        const statusObj = (typeof result === 'object') ? result : JSON.parse(result);
                        const status = statusObj.status;
                        this.updateProgress(statusObj);
                        if (status === 'Succeeded') {
                            this.handleDeploySuccess();
                        } else if (status === 'Failed') {
                            let errorDetail = statusObj.errorMessage || "Salesforce 部署验证失败，请查看详情";
                            this.handleDeployFailed(errorDetail);
                        }
                    } catch (e) {
                        if (result === 'Succeeded') this.handleDeploySuccess();
                        else if (result === 'Failed') this.handleDeployFailed("Unknown Error");
                    }
                });
            }, 3000);
        },

        handleDeploySuccess() {
            this.stopAllPolling();
            this.deployProgress = 100;
            this.progressStatus = 'success';
            this.progressText = "验证/部署 成功！";
            this.$modal.msgSuccess("操作成功！");
            this.resetButtonState();
            this.getDetail();
        },

        handleDeployFailed(msg) {
            this.stopAllPolling();
            this.progressStatus = 'exception';
            this.progressText = "操作失败";
            this.$modal.alert(msg, "错误提示", { type: 'error' });
            this.resetButtonState();
            this.getDetail();
        },

        updateProgress(statusObj) {
            if (statusObj.numberComponentsTotal > 0) {
                const completed = statusObj.numberComponentsDeployed + statusObj.numberTestsCompleted;
                const total = statusObj.numberComponentsTotal + statusObj.numberTestsTotal;
                const percent = Math.floor((completed / total) * 100);
                this.deployProgress = 40 + Math.floor(percent * 0.5);
                this.progressText = `Salesforce 处理中: ${completed}/${total}`;
            }
        },

        stopAllPolling() {
            if (this.dbTimer) { clearInterval(this.dbTimer); this.dbTimer = null; }
            if (this.sfTimer) { clearInterval(this.sfTimer); this.sfTimer = null; }
        },

        resetButtonState() {
            this.validating = false;
            this.deploying = false;
        },

        recoverPollingStatus() {
            if (this.deployment.status === 'Processing') {
                this.validating = true;
                this.startDbPolling();
            } else if ((this.deployment.status === 'Deploying' || this.deployment.status === 'Validating') && this.deployment.lastAsyncId) {
                this.validating = true;
                this.startSfPolling(this.deployment.lastAsyncId);
            }
        },

        openMetadataBrowser() {
            if (this.deployment && this.deployment.sourceOrgId) {
                this.$refs.metaBrowser.open(
                    this.deployment.sourceOrgId,
                    this.deployment.targetOrgId,
                    this.itemList
                );
            } else {
                this.$modal.msgError("部署包数据未加载完成或源环境为空");
            }
        },

        handleBrowserAction(event) {
            if (event.action === 'add') {
                const itemToAdd = [{ metadataType: event.type, memberName: event.name }];
                addDeploymentItems(this.deploymentId, itemToAdd).then(res => {
                    this.$modal.msgSuccess("已添加: " + event.name);
                    listDeploymentItems(this.deploymentId).then(listRes => {
                        this.itemList = listRes.data;
                        const newItem = this.itemList.find(
                            i => i.metadataType === event.type && i.memberName === event.name
                        );
                        if (newItem && this.$refs.metaBrowser) {
                            this.$refs.metaBrowser.updateMapAfterAdd(event.key, newItem.id);
                        }
                    });
                });
            } else if (event.action === 'remove') {
                removeDeploymentItems(event.id).then(() => {
                    this.$modal.msgSuccess("已移除");
                    this.getItems();
                });
            }
        },

        handleRemoveItem(row) {
            this.$confirm('确认移除该元数据吗？', "警告", { type: "warning" }).then(() => {
                removeDeploymentItems(row.id).then(() => {
                    this.$modal.msgSuccess("移除成功");
                    this.getItems();
                });
            });
        },

        handleBack() {
            this.$router.push('/salesforce/deployment');
        },

        handleBrowserViewCode(data) {
            this.previewCode(this.$refs.metaBrowser.currentOrgId, data.type, data.name);
        },
        handleBrowserDiffCode(data) {
            this.handleDiff(data);
        },
        previewCode(orgId, type, name) {
            const loading = this.$loading({
                lock: true,
                text: '加载代码中...',
                spinner: 'el-icon-loading',
                background: 'rgba(0, 0, 0, 0.7)'
            });
            request({
                url: '/system/sf/meta/retrieve',
                method: 'get',
                params: { orgId, type, name }
            }).then(response => {
                loading.close();
                this.codeContent = response.data;
                this.oldCodeContent = "";
                this.isDiffMode = false;
                this.previewTitle = `${type}: ${name}`;
                this.openCode = true;
            }).catch(() => loading.close());
        },

        statusType(status) {
            if (status === 'Succeeded') return 'success';
            if (status === 'Failed') return 'danger';
            if (status === 'Processing' || status === 'Deploying' || status === 'Validating') return 'warning';
            return 'info';
        }
    }
};
</script>

<style scoped>
.mb-20 {
    margin-bottom: 20px;
}

.label {
    font-weight: bold;
    color: #606266;
    display: inline-block;
    margin-right: 5px;
}

.text-danger {
    color: #F56C6C;
}

/* 预览弹窗样式 */
.file-list-header {
    font-weight: bold;
    margin-bottom: 10px;
    padding-bottom: 5px;
    border-bottom: 1px solid #eee;
}
.file-list-container {
    height: 600px;
    overflow-y: auto;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    padding: 5px;
    background-color: #f9fafc;
}
.file-ul {
    list-style: none;
    padding: 0;
    margin: 0;
}
.file-li {
    padding: 5px 10px;
    font-size: 13px;
    border-bottom: 1px dashed #eee;
    color: #333;
}
</style>