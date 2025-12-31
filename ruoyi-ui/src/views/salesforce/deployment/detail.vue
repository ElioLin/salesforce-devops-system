<template>
    <div class="app-container">
        <el-card shadow="never" class="mb-20" v-loading="loading">
            <div slot="header" class="clearfix">
                <span class="card-title">{{ deployment.title || '部署包详情' }}</span>
                <el-tag size="medium" :type="statusType(deployment.status)" effect="dark" style="margin-left: 10px">{{
                    deployment.status
                    }}</el-tag>
                <div style="float: right;">
                    <el-button type="info" icon="el-icon-refresh" size="mini" @click="refreshData"
                        :disabled="isPolling">刷新状态</el-button>
                    <el-button type="primary" plain icon="el-icon-arrow-left" size="mini"
                        @click="handleBack">返回列表</el-button>
                </div>
            </div>

            <el-row :gutter="20" class="info-row">
                <el-col :span="6">
                    <span class="label">源环境:</span> <span class="val">{{ sourceOrgName }}</span>
                </el-col>
                <el-col :span="6">
                    <span class="label">目标环境:</span> <span class="val">{{ targetOrgName }}</span>
                </el-col>
                <el-col :span="12" style="text-align: right">
                    <el-button-group>
                        <el-button type="info" plain icon="el-icon-download" size="small" :disabled="isProcessing"
                            @click="handleDownloadPackage">下载</el-button>
                        <el-button type="primary" plain icon="el-icon-view" size="small" :disabled="isProcessing"
                            @click="handlePreviewPackage">预览</el-button>
                    </el-button-group>

                    <el-button-group style="margin-left: 10px;">
                        <el-button type="warning" icon="el-icon-video-play" size="small" :disabled="isProcessing"
                            :loading="validating" @click="handleDeploy(true)">仅验证</el-button>
                        <el-button type="success" icon="el-icon-upload" size="small" :disabled="isProcessing"
                            :loading="deploying" @click="handleDeploy(false)">完整部署</el-button>
                        <el-button v-if="deployment.status === 'Succeeded'" type="primary" icon="el-icon-lightning"
                            size="small" :disabled="isProcessing" @click="handleQuickDeploy">快速部署</el-button>
                    </el-button-group>
                </el-col>
            </el-row>

            <div class="config-section">
                <el-form label-width="80px" size="small" :inline="true" class="config-form">
                    <el-form-item label="测试级别">
                        <el-select v-model="deployment.testLevel" placeholder="请选择" style="width: 220px">
                            <el-option label="默认 (NoTestRun / Default)" value="NoTestRun" />
                            <el-option label="运行本地测试 (RunLocalTests)" value="RunLocalTests" />
                            <el-option label="指定测试类 (RunSpecifiedTests)" value="RunSpecifiedTests" />
                        </el-select>
                    </el-form-item>
                    <el-form-item label="指定类名" v-if="deployment.testLevel === 'RunSpecifiedTests'">
                        <el-input v-model="deployment.specifiedTests" placeholder="多个类名用逗号分隔" style="width: 300px" />
                    </el-form-item>
                    <el-form-item>
                        <el-button type="text" icon="el-icon-check" @click="handleSaveConfig">保存配置</el-button>
                    </el-form-item>
                </el-form>
            </div>

            <div v-if="isProcessing || progressStatus" class="progress-container">
                <div class="progress-block">
                    <div class="progress-header">
                        <span class="title">
                            <i class="el-icon-collection"></i> 元数据处理
                            <el-tag size="mini" effect="plain" class="ml-10" v-if="compStateText">{{ compStateText
                                }}</el-tag>
                        </span>
                        <span class="count" v-if="compTotal > 0">{{ compDone }} / {{ compTotal }}</span>
                    </div>
                    <el-progress :percentage="compPercent" :status="compStatus" :stroke-width="14" text-inside
                        :color="customColors">
                    </el-progress>
                </div>

                <div class="progress-block" v-if="shouldShowTestProgress" style="margin-top: 15px;">
                    <div class="progress-header">
                        <span class="title">
                            <i class="el-icon-cpu"></i> 单元测试
                            <span v-if="currentTestName" class="running-test">
                                <i class="el-icon-loading"></i> 正在执行: {{ currentTestName }}
                            </span>
                        </span>
                        <span class="count" v-if="testTotal > 0">{{ testDone }} / {{ testTotal }}</span>
                        <span class="count" v-else>等待开始...</span>
                    </div>
                    <el-progress :percentage="testPercent" :status="testStatus" :stroke-width="14" text-inside
                        :color="customColors">
                    </el-progress>
                    <div v-if="testFailures > 0" class="error-text">
                        <i class="el-icon-warning"></i> 发现 {{ testFailures }} 个测试失败
                    </div>
                </div>
            </div>

            <el-alert v-if="deployment.status === 'Failed' && deployment.errorMsg" title="报错信息" type="error" show-icon
                style="margin-top: 15px;" :closable="false">
                <template slot="default">
                    <div class="error-msg-box">{{ deployment.errorMsg }}</div>
                </template>
            </el-alert>
        </el-card>

        <el-card shadow="never">
            <div slot="header" class="clearfix list-header">
                <div class="left-panel">
                    <span class="card-title">包含的元数据</span>
                    <el-tag size="small" type="info" effect="plain" class="count-tag">
                        当前显示: <b class="text-primary">{{ filteredItemList.length }}</b> / 总共: {{ itemList.length }}
                    </el-tag>
                </div>

                <div class="right-panel">
                    <el-button type="text" icon="el-icon-refresh-left" :loading="isCheckingStatus"
                        @click="handleCheckStatus" :disabled="!deployment.targetOrgId">重新计算差异</el-button>

                    <el-divider direction="vertical"></el-divider>

                    <el-button type="text" icon="el-icon-remove-outline" @click="clearColumnFilters">重置筛选</el-button>

                    <el-button type="primary" size="mini" icon="el-icon-plus" :disabled="isProcessing"
                        @click="openMetadataBrowser" style="margin-left: 10px">
                        添加元数据
                    </el-button>
                </div>
            </div>

            <el-table v-loading="loadingItems" :data="filteredItemList" border stripe highlight-current-row
                style="width: 100%">
                <el-table-column prop="metadataType" width="220" sortable>
                    <template slot="header" slot-scope="scope">
                        <div class="custom-header">
                            <span>类型</span>
                            <el-select v-model="columnFilters.type" size="mini" placeholder="全部" clearable
                                @click.native.stop filterable>
                                <el-option v-for="type in existingTypeOptions" :key="type" :label="getDictLabel(type)"
                                    :value="type" />
                            </el-select>
                        </div>
                    </template>
                    <template slot-scope="scope">
                        {{ getDictLabel(scope.row.metadataType) }}
                    </template>
                </el-table-column>

                <el-table-column prop="memberName" sortable>
                    <template slot="header" slot-scope="scope">
                        <div class="custom-header">
                            <span>名称</span>
                            <el-input v-model="columnFilters.name" size="mini" placeholder="筛选名称..." clearable
                                @click.native.stop prefix-icon="el-icon-search" />
                        </div>
                    </template>
                </el-table-column>

                <el-table-column width="180" sortable>
                    <template slot="header" slot-scope="scope">
                        <div class="custom-header">
                            <span>所属对象</span>
                            <el-input v-model="columnFilters.parent" size="mini" placeholder="筛选..." clearable
                                @click.native.stop prefix-icon="el-icon-search" />
                        </div>
                    </template>
                    <template slot-scope="scope">{{ getParentName(scope.row.memberName) }}</template>
                </el-table-column>

                <el-table-column align="center" width="120" sortable prop="diffStatus">
                    <template slot="header" slot-scope="scope">
                        <div class="custom-header">
                            <span>差异</span>
                            <el-select v-model="columnFilters.status" size="mini" placeholder="全部" clearable
                                @click.native.stop>
                                <el-option v-for="status in existingDiffOptions" :key="status" :label="status"
                                    :value="status" />
                            </el-select>
                        </div>
                    </template>
                    <template slot-scope="scope">
                        <el-tooltip :content="scope.row.diffStatus || 'Unknown'" placement="top">
                            <i :class="getDiffIcon(scope.row.diffStatus)"
                                :style="{ color: getDiffColor(scope.row.diffStatus), fontSize: '18px', fontWeight: 'bold' }"></i>
                        </el-tooltip>
                        <span style="margin-left:5px">{{ scope.row.diffStatus }}</span>
                    </template>
                </el-table-column>

                <el-table-column label="管理" width="150" align="center" fixed="right">
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

        <el-dialog title="部署包内容全览" :visible.sync="previewDialog.open" width="85%" append-to-body top="5vh">
            <div v-loading="previewDialog.loading" style="height: 650px;">
                <el-row :gutter="20" style="height: 100%;">
                    <el-col :span="6" style="height: 100%; display: flex; flex-direction: column;">
                        <div class="file-list-header">
                            <span>文件清单 ({{ previewDialog.files.length }})</span>
                            <el-button type="text" size="mini" icon="el-icon-download"
                                @click="handleDownloadPackage">下载ZIP</el-button>
                        </div>
                        <div class="file-list-container">
                            <ul class="file-ul">
                                <li class="file-li" :class="{ active: previewDialog.currentFile === 'package.xml' }"
                                    @click="selectPreviewFile('package.xml')">
                                    <i class="el-icon-s-cooperation" style="color:#E6A23C;"></i> package.xml
                                </li>
                                <li v-for="(file, index) in previewDialog.files" :key="index" class="file-li"
                                    :class="{ active: previewDialog.currentFile === file }"
                                    @click="selectPreviewFile(file)">
                                    <i class="el-icon-document" style="color:#909399;"></i> {{ file }}
                                </li>
                            </ul>
                        </div>
                    </el-col>

                    <el-col :span="18" style="height: 100%;">
                        <div class="file-list-header">
                            <span>内容预览: {{ previewDialog.currentFile }}</span>
                        </div>
                        <monaco-editor v-if="previewDialog.open" :value="previewDialog.currentContent" :readOnly="true"
                            :language="getLanguage(previewDialog.currentFile)" height="600px" theme="vs-dark" />
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
    previewDeploymentPackage
} from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import MetadataBrowser from "@/views/salesforce/org/MetadataBrowser";
import MonacoEditor from '@/components/MonacoEditor';
import request from '@/utils/request';
import { download } from "@/utils/request";

export default {
    name: "DeploymentDetail",
    dicts: ['sys_salesforce_metadata_type'],
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

            progressStatus: null,
            compTotal: 0,
            compDone: 0,
            compPercent: 0,
            compStateText: '',
            testTotal: 0,
            testDone: 0,
            testPercent: 0,
            testFailures: 0,
            currentTestName: '',

            customColors: [
                { color: '#f56c6c', percentage: 20 },
                { color: '#e6a23c', percentage: 40 },
                { color: '#5cb87a', percentage: 60 },
                { color: '#1989fa', percentage: 80 },
                { color: '#67c23a', percentage: 100 }
            ],

            openCode: false,
            codeContent: "",
            oldCodeContent: "",
            isDiffMode: false,
            previewTitle: "",

            previewDialog: {
                open: false,
                loading: false,
                files: [],
                fileContents: {},
                packageXml: '',
                currentFile: 'package.xml',
                currentContent: ''
            },

            columnFilters: {
                type: '',
                name: '',
                parent: '',
                status: ''
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
        existingTypeOptions() {
            if (!this.itemList || this.itemList.length === 0) return [];
            const types = new Set(this.itemList.map(item => item.metadataType));
            return Array.from(types).sort();
        },
        existingDiffOptions() {
            if (!this.itemList || this.itemList.length === 0) return [];
            const statusSet = new Set(this.itemList.map(item => item.diffStatus).filter(s => s));
            return Array.from(statusSet).sort();
        },
        filteredItemList() {
            return this.itemList.filter(item => {
                if (this.columnFilters.type && item.metadataType !== this.columnFilters.type) return false;
                if (this.columnFilters.name && !item.memberName.toLowerCase().includes(this.columnFilters.name.toLowerCase())) return false;
                if (this.columnFilters.parent) {
                    const parent = this.getParentName(item.memberName);
                    if (!parent.toLowerCase().includes(this.columnFilters.parent.toLowerCase())) return false;
                }
                if (this.columnFilters.status && item.diffStatus !== this.columnFilters.status) return false;
                return true;
            });
        },
        shouldShowTestProgress() {
            return this.testTotal > 0 ||
                (this.deployment.testLevel && this.deployment.testLevel !== 'NoTestRun');
        },
        compStatus() {
            if (this.progressStatus === 'exception') return 'exception';
            if (this.compPercent === 100) return 'success';
            return null;
        },
        testStatus() {
            if (this.testFailures > 0) return 'exception';
            if (this.progressStatus === 'exception') return 'exception';
            if (this.testPercent === 100) return 'success';
            return null;
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
        getDictLabel(value) {
            if (!value) return '';
            const datas = this.dict.type.sys_salesforce_metadata_type;
            if (datas) {
                const found = datas.find(item => item.value === value);
                if (found) return found.label;
            }
            return value;
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
        getParentName(name) {
            if (name && name.includes('.')) return name.split('.')[0];
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
            const type = row.metadataType || row.type;
            const name = row.memberName || row.name || row.fullName;
            if (!type || !name) {
                this.$modal.msgError("缺少必要的元数据参数 (Type/Name)，无法比对");
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
                    type: type,
                    name: name
                }
            }).then(response => {
                loading.close();
                const diffData = response.data;
                this.codeContent = diffData.sourceContent;
                this.oldCodeContent = diffData.targetContent;
                this.isDiffMode = true;
                this.previewTitle = `比对: ${name} (${type}) [左:目标环境 vs 右:源环境]`;
                this.openCode = true;
            }).catch(() => {
                loading.close();
            });
        },

        handleDownloadPackage() {
            const fileName = `deployment_pkg_${this.deploymentId}.zip`;
            this.$modal.msgSuccess("正在生成并下载部署包，请稍候...");
            download('/salesforce/deployment/download/' + this.deploymentId, {}, fileName);
        },

        handlePreviewPackage() {
            this.previewDialog.open = true;
            this.previewDialog.loading = true;
            this.previewDialog.files = [];
            this.previewDialog.fileContents = {};
            this.previewDialog.packageXml = '';
            this.previewDialog.currentFile = 'package.xml';
            this.previewDialog.currentContent = '';

            previewDeploymentPackage(this.deploymentId).then(res => {
                const data = res.data;
                this.previewDialog.files = data.files || [];
                this.previewDialog.fileContents = data.fileContents || {};
                this.previewDialog.packageXml = data.packageXml || '';

                let pkgKey = Object.keys(this.previewDialog.fileContents).find(k => k.endsWith('package.xml'));
                if (pkgKey) {
                    this.selectPreviewFile(pkgKey);
                } else if (this.previewDialog.packageXml) {
                    this.previewDialog.currentContent = this.previewDialog.packageXml;
                } else if (this.previewDialog.files.length > 0) {
                    this.selectPreviewFile(this.previewDialog.files[0]);
                }

                this.previewDialog.loading = false;
            }).catch(() => {
                this.previewDialog.loading = false;
            });
        },

        selectPreviewFile(fileName) {
            this.previewDialog.currentFile = fileName;
            if (fileName === 'package.xml' && this.previewDialog.packageXml && !this.previewDialog.fileContents[fileName]) {
                this.previewDialog.currentContent = this.previewDialog.packageXml;
                return;
            }
            const content = this.previewDialog.fileContents[fileName];
            this.previewDialog.currentContent = content || '(无法预览或文件为空)';
        },

        getLanguage(fileName) {
            if (!fileName) return 'xml';
            if (fileName.endsWith('.cls') || fileName.endsWith('.trigger')) return 'java';
            if (fileName.endsWith('.js')) return 'javascript';
            if (fileName.endsWith('.css')) return 'css';
            if (fileName.endsWith('.json')) return 'json';
            return 'xml';
        },

        resetProgress() {
            this.progressStatus = null;
            this.compTotal = 0;
            this.compDone = 0;
            this.compPercent = 0;
            this.compStateText = "准备中...";
            this.testTotal = 0;
            this.testDone = 0;
            this.testPercent = 0;
            this.testFailures = 0;
            this.currentTestName = '';
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

                this.resetProgress();

                deployPackage(this.deploymentId, checkOnly).then(res => {
                    this.$modal.msgSuccess(`${actionName}请求已提交，正在后台处理...`);
                    this.compStateText = "正在从源环境提取代码 (Retrieve)...";
                    this.compPercent = 5;
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
                this.resetProgress();
                this.compStateText = "正在启动快速部署...";

                quickDeploy(this.deploymentId).then(res => {
                    this.$modal.msgSuccess("快速部署已启动！");
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

                    if (this.compPercent < 40) {
                        this.compPercent += 5;
                    }

                    if (data.status === 'Processing') {
                        this.compStateText = "提取与上传中 (Uploading)...";
                    }
                    else if (data.status === 'Deploying' || data.status === 'Validating') {
                        clearInterval(this.dbTimer);
                        this.dbTimer = null;
                        this.compStateText = "等待 Salesforce 处理...";
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
                        console.error("Parse SF Status Error", e);
                        if (result === 'Succeeded') this.handleDeploySuccess();
                        else if (result === 'Failed') this.handleDeployFailed("Unknown Error");
                    }
                });
            }, 3000);
        },
        updateProgress(statusObj) {
            this.compTotal = statusObj.numberComponentsTotal || 0;
            this.compDone = statusObj.numberComponentsDeployed || 0;

            if (this.compTotal > 0) {
                const cPercent = Math.floor((this.compDone / this.compTotal) * 100);
                this.compPercent = Math.max(this.compPercent, cPercent);
            }

            if (this.compDone < this.compTotal) {
                this.compStateText = "部署元数据中...";
            } else {
                this.compStateText = "元数据部署完成";
                this.compPercent = 100;
            }

            this.testTotal = statusObj.numberTestsTotal || 0;
            this.testDone = statusObj.numberTestsCompleted || 0;
            this.testFailures = statusObj.numberTestErrors || 0;

            if (statusObj.stateDetail) {
                this.currentTestName = statusObj.stateDetail;
            } else {
                this.currentTestName = '';
            }

            if (this.testTotal > 0) {
                const tPercent = Math.floor((this.testDone / this.testTotal) * 100);
                this.testPercent = tPercent;
            }
        },
        handleDeploySuccess() {
            this.stopAllPolling();
            this.compPercent = 100;
            if (this.testTotal > 0) this.testPercent = 100;
            this.progressStatus = 'success';
            this.compStateText = "完成";
            this.$modal.msgSuccess("操作成功！");
            this.resetButtonState();
            this.getDetail();
        },
        handleDeployFailed(msg) {
            this.stopAllPolling();
            this.progressStatus = 'exception';
            this.compStateText = "失败";
            this.$modal.alert(msg, "错误提示", { type: 'error' });
            this.resetButtonState();
            this.getDetail();
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
            this.resetProgress();
            if (this.deployment.status === 'Processing') {
                this.validating = true;
                this.compStateText = "恢复任务中...";
                this.startDbPolling();
            } else if ((this.deployment.status === 'Deploying' || this.deployment.status === 'Validating') && this.deployment.lastAsyncId) {
                this.validating = true;
                this.compStateText = "恢复监控中...";
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
                    this.refreshBrowserMap(event);
                });
            } else if (event.action === 'batch-add') {
                const itemsPayload = event.items.map(i => ({
                    metadataType: i.type,
                    memberName: i.name
                }));
                addDeploymentItems(this.deploymentId, itemsPayload).then(res => {
                    this.$modal.msgSuccess(`成功添加 ${itemsPayload.length} 条元数据`);
                    this.refreshBrowserMap(event, true);
                });
            } else if (event.action === 'remove') {
                removeDeploymentItems(event.id).then(() => {
                    this.$modal.msgSuccess("已移除");
                    this.getItems();
                });
            } else if (event.action === 'batch-remove') {
                const ids = event.ids.join(',');
                removeDeploymentItems(ids).then(() => {
                    this.$modal.msgSuccess("批量移除成功");
                    this.getItems();
                });
            }
        },

        refreshBrowserMap(event, isBatch = false) {
            listDeploymentItems(this.deploymentId).then(listRes => {
                this.itemList = listRes.data;
                if (this.$refs.metaBrowser) {
                    if (isBatch) {
                        event.items.forEach(evtItem => {
                            const match = this.itemList.find(
                                i => i.metadataType === evtItem.type && i.memberName === evtItem.name
                            );
                            if (match) {
                                this.$refs.metaBrowser.updateMapAfterAdd(evtItem.key, match.id);
                            }
                        });
                    } else {
                        const newItem = this.itemList.find(
                            i => i.metadataType === event.type && i.memberName === event.name
                        );
                        if (newItem) {
                            this.$refs.metaBrowser.updateMapAfterAdd(event.key, newItem.id);
                        }
                    }
                }
            });
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
        },
        // 清空列筛选
        clearColumnFilters() {
            this.columnFilters = {
                type: '',
                name: '',
                parent: '',
                status: ''
            };
            this.$modal.msgSuccess("筛选条件已重置");
        }
    }
};
</script>

<style scoped>
.mb-20 {
    margin-bottom: 20px;
}

.card-title {
    font-weight: bold;
    font-size: 16px;
}

.label {
    color: #909399;
    margin-right: 8px;
    font-weight: 500;
}

.val {
    color: #303133;
    font-weight: 600;
}

.text-danger {
    color: #F56C6C;
}

.text-primary {
    color: #409EFF;
}

/* 计数标签样式 */
.count-tag {
    margin-left: 15px;
    font-size: 13px;
    letter-spacing: 0.5px;
}

/* 列表头部布局 */
.list-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.left-panel,
.right-panel {
    display: flex;
    align-items: center;
}

/* 错误信息展示盒 */
.error-msg-box {
    white-space: pre-wrap;
    line-height: 1.6;
    font-family: Consolas, monospace;
    max-height: 200px;
    overflow-y: auto;
    font-size: 13px;
}

/* 配置区域样式 */
.config-section {
    margin-top: 20px;
    border-top: 1px dashed #e4e7ed;
    padding-top: 15px;
    background-color: #fbfbfc;
    border-radius: 4px;
    padding-left: 10px;
}

.config-form {
    margin-bottom: 0;
}

/* 进度条增强 */
.progress-container {
    margin-top: 20px;
    background-color: #f8fcfb;
    padding: 20px;
    border-radius: 6px;
    border: 1px solid #e1e6eb;
    box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
}

.progress-header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 8px;
}

.progress-header .title {
    font-weight: 600;
    font-size: 14px;
    color: #303133;
}

.progress-header .count {
    font-family: Consolas, monospace;
    font-weight: bold;
}

.ml-10 {
    margin-left: 10px;
}

.error-text {
    margin-top: 8px;
    font-size: 13px;
    color: #F56C6C;
    font-weight: 500;
}

/* 预览相关 */
.file-list-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-bottom: 10px;
    border-bottom: 1px solid #ebeef5;
    margin-bottom: 10px;
}

.file-list-container {
    flex: 1;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    overflow-y: auto;
    background: #fff;
    height: calc(100% - 45px);
}

.file-ul {
    list-style: none;
    padding: 0;
    margin: 0;
}

.file-li {
    padding: 10px 15px;
    cursor: pointer;
    font-size: 13px;
    color: #606266;
    border-bottom: 1px solid #f5f7fa;
    transition: all 0.2s;
}

.file-li:hover {
    background-color: #f5f7fa;
    color: #409EFF;
}

.file-li.active {
    background-color: #ecf5ff;
    color: #409EFF;
    border-left: 3px solid #409EFF;
    font-weight: 600;
}

/* 表头搜索框美化 */
.custom-header {
    padding: 4px 0;
}

.custom-header span {
    display: block;
    margin-bottom: 8px;
    color: #606266;
    font-weight: 600;
}
</style>