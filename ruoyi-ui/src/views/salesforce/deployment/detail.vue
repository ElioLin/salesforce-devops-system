<template>
    <div class="app-container">
        <el-card shadow="never" class="mb-20" v-loading="loading">
            <div slot="header" class="clearfix">
                <span class="card-title">{{ deployment.title || '部署包详情' }}</span>

                <el-tag size="medium" :type="statusType(deployment.status)" effect="dark" style="margin-left: 10px">
                    {{ calculatedStatusLabel }}
                </el-tag>

                <el-tag v-if="isSocketConnected" type="success" size="mini" effect="plain" style="margin-left: 10px">
                    <i class="el-icon-loading"></i> 实时连接正常
                </el-tag>
                <el-tag v-else-if="isProcessing" type="warning" size="mini" effect="plain" style="margin-left: 10px">
                    <i class="el-icon-loading"></i> 连接断开 (尝试重连...)
                </el-tag>

                <div style="float: right;">
                    <el-button v-if="isProcessing && deployment.status !== 'Canceling'" type="danger"
                        icon="el-icon-video-pause" style="margin-right: 15px;" :loading="canceling"
                        @click="handleCancelDeploy">
                        取消任务
                    </el-button>

                    <el-button type="text" icon="el-icon-link" style="margin-right: 15px;" @click="handleOpenSalesforce"
                        :disabled="!salesforceDeployUrl">
                        查看SF验证/部署情况
                    </el-button>

                    <el-button type="text" icon="el-icon-refresh-left" :loading="isCheckingStatus"
                        style="margin-right: 15px;" @click="handleGlobalRecalculate"
                        :disabled="!deployment.targetOrgId || isProcessing">
                        重新计算差异
                    </el-button>

                    <el-button type="info" icon="el-icon-refresh" size="mini" @click="refreshData"
                        :disabled="isProcessing">手动刷新</el-button>
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

                        <el-button v-if="deployment.status === 'Validated'" type="primary" icon="el-icon-lightning"
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
                        <el-select v-model="specifiedTestsArr" multiple filterable allow-create default-first-option
                            placeholder="输入类名并回车" style="width: 800px" no-data-text="输入类名按回车添加">
                        </el-select>
                    </el-form-item>
                    <el-form-item>
                        <el-button type="text" icon="el-icon-check" @click="handleSaveConfig">保存配置</el-button>
                    </el-form-item>
                </el-form>
            </div>

            <div v-if="isProcessing || compTotal > 0 || progressStatus" class="progress-container">
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
                    <div v-if="isQuickDeploy" class="quick-deploy-tip">
                        <i class="el-icon-lightning" style="font-size: 16px;"></i>
                        <span style="font-weight: 600; margin-left: 5px;">快速部署模式：直接使用上次验证结果，无需再次执行测试。</span>
                    </div>
                    <template v-else>
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
                    </template>
                </div>
            </div>

            <el-alert v-if="deployment.errorMsg" title="部署/验证详情"
                :type="deployment.status === 'Succeeded' || deployment.status === 'Validated' ? 'success' : 'error'"
                show-icon style="margin-top: 15px;" :closable="false">
                <template slot="default">
                    <div class="error-msg-box">{{ deployment.errorMsg }}</div>
                </template>
            </el-alert>
        </el-card>

        <el-card shadow="never" class="tabs-card">
            <el-tabs v-model="activeTab" type="card">
                <el-tab-pane name="selected">
                    <span slot="label">
                        <i class="el-icon-folder-checked"></i> 已添加元数据
                        <el-badge :value="itemList.length" class="item-badge" type="primary"
                            v-if="itemList.length > 0" />
                    </span>

                    <div class="list-header clearfix mb-10">
                        <div class="left-panel">
                            <el-tag size="small" type="info" effect="plain" class="count-tag">
                                当前筛选总条数: <b class="text-primary">{{ filteredItemList.length }}</b> / 总共: {{ itemList.length
                                }}
                            </el-tag>
                        </div>
                        <div class="right-panel text-right">
                            <el-button type="text" icon="el-icon-remove-outline"
                                @click="clearColumnFilters">重置筛选</el-button>
                        </div>
                    </div>

                    <el-table v-loading="loadingItems" :data="pagedItemList" border stripe highlight-current-row
                        style="width: 100%">
                        <el-table-column type="index" label="序号" width="55" align="center">
                            <template slot-scope="scope">
                                <span>{{ (pagination.pageNum - 1) * pagination.pageSize + scope.$index + 1 }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column prop="metadataType" label="类型" width="220" sortable>
                            <template slot="header" slot-scope="scope">
                                <div class="custom-header">
                                    <span>类型</span>
                                    <el-select v-model="columnFilters.type" size="mini" placeholder="全部" clearable
                                        @click.native.stop filterable>
                                        <el-option v-for="type in existingTypeOptions" :key="type"
                                            :label="getDictLabel(type)" :value="type" />
                                    </el-select>
                                </div>
                            </template>
                            <template slot-scope="scope">
                                {{ getDictLabel(scope.row.metadataType) }}
                            </template>
                        </el-table-column>

                        <el-table-column prop="memberName" label="名称" sortable min-width="260">
                            <template slot="header" slot-scope="scope">
                                <div class="custom-header">
                                    <span>名称</span>
                                    <div style="display: flex; width: 100%">
                                        <el-select v-model="columnFilters.nameOp" size="mini"
                                            style="width: 100px; margin-right: 5px;" @click.native.stop>
                                            <el-option label="包含" value="contains" />
                                            <el-option label="不包含" value="not_contains" />
                                            <el-option label="等于" value="equals" />
                                        </el-select>
                                        <el-input v-model="columnFilters.name" size="mini" placeholder="筛选名称..."
                                            clearable @click.native.stop />
                                    </div>
                                </div>
                            </template>
                            <template slot-scope="scope">
                                {{ getShortName(scope.row.memberName) }}
                            </template>
                        </el-table-column>

                        <el-table-column label="所属对象" width="240" sortable>
                            <template slot="header" slot-scope="scope">
                                <div class="custom-header">
                                    <span>所属对象</span>
                                    <div style="display: flex; width: 100%">
                                        <el-select v-model="columnFilters.parentOp" size="mini"
                                            style="width: 100px; margin-right: 5px;" @click.native.stop>
                                            <el-option label="包含" value="contains" />
                                            <el-option label="不包含" value="not_contains" />
                                            <el-option label="等于" value="equals" />
                                        </el-select>
                                        <el-input v-model="columnFilters.parent" size="mini" placeholder="筛选..."
                                            clearable @click.native.stop />
                                    </div>
                                </div>
                            </template>
                            <template slot-scope="scope">{{ getParentName(scope.row.memberName) }}</template>
                        </el-table-column>

                        <el-table-column prop="lastModifiedByName" label="修改人" width="140" show-overflow-tooltip
                            align="center" />

                        <el-table-column prop="lastModifiedDate" label="修改时间" width="160" sortable align="center">
                            <template slot-scope="scope">{{ parseTime(scope.row.lastModifiedDate) }}</template>
                        </el-table-column>

                        <el-table-column align="center" label="差异" width="120" sortable prop="diffStatus">
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
                                <el-button size="mini" type="text" icon="el-icon-connection"
                                    :disabled="!deployment.targetOrgId" @click="handleDiff(scope.row)">比对</el-button>
                                <el-button size="mini" type="text" icon="el-icon-delete" class="text-danger"
                                    :disabled="isProcessing" @click="handleRemoveItem(scope.row)">移除</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                    <div class="pagination-container" style="margin-top: 15px; text-align: right;">
                        <el-pagination background @size-change="handleSizeChange" @current-change="handleCurrentChange"
                            :current-page.sync="pagination.pageNum" :page-sizes="[10, 20, 50, 100, 200, 500, 1000]"
                            :page-size.sync="pagination.pageSize" layout="total, sizes, prev, pager, next, jumper"
                            :total="pagination.total">
                        </el-pagination>
                    </div>
                </el-tab-pane>

                <el-tab-pane name="add">
                    <span slot="label"><i class="el-icon-plus"></i> 添加元数据</span>

                    <metadata-browser ref="metaBrowser" :sourceOrgId="deployment.sourceOrgId"
                        :targetOrgId="deployment.targetOrgId" :initialItemList="itemList" :disabled="isProcessing"
                        @auto-action="handleBrowserAction" @view-code="handleBrowserViewCode"
                        @diff-code="handleBrowserDiffCode" />
                </el-tab-pane>
            </el-tabs>
        </el-card>

        <el-dialog :title="previewTitle" :visible.sync="openCode" width="90%" append-to-body top="2vh">
            <monaco-editor v-if="openCode" :value="codeContent" :original="oldCodeContent" :diffEditor="isDiffMode"
                language="java" height="750px" theme="vs-dark" :options="editorOptions" />
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
    previewDeploymentPackage,
    cancelDeployment
} from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import MetadataBrowser from "@/views/salesforce/org/MetadataBrowser";
import MonacoEditor from '@/components/MonacoEditor';
import request from '@/utils/request';
import { download } from "@/utils/request";
import { getToken } from "@/utils/auth";

export default {
    name: "DeploymentDetail",
    dicts: ['sys_salesforce_metadata_type', 'sys_salesforce_deploy_status'],
    components: { MetadataBrowser, MonacoEditor },
    data() {
        return {
            deploymentId: null,
            activeTab: 'selected',
            deployment: {
                testLevel: 'NoTestRun',
                specifiedTests: '',
                checkOnly: false,
                errorMsg: '' // 初始化字段
            },
            localCheckOnly: false,
            isQuickDeploy: false,
            specifiedTestsArr: [],

            itemList: [],
            orgMap: {},

            loading: false,
            loadingItems: false,
            validating: false,
            deploying: false,
            isCheckingStatus: false,

            websocket: null,
            isSocketConnected: false,
            socketRetryCount: 0,

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

            hasShownSuccess: false,

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
                nameOp: 'contains',
                parent: '',
                parentOp: 'contains',
                status: ''
            },

            editorOptions: {
                readOnly: true,
                originalEditable: false,
                automaticLayout: true,
                renderSideBySide: true,
                ignoreTrimWhitespace: false,
                hideUnchangedRegions: {
                    enabled: true,
                    revealLineCount: 10,
                    minimumLineCount: 20
                }
            },
            isExplicitDisconnect: false, // 【新增】标记是否为显式断开
            canceling: false,
            pagination: {
                pageNum: 1,
                pageSize: 20, // 默认每页显示20条，减少渲染压力
                total: 0
            }
        };
    },
    computed: {
        sourceOrgName() {
            if (!this.deployment || !this.deployment.sourceOrgId) return '-';
            const org = this.orgMap[this.deployment.sourceOrgId];
            return org ? org.name : this.deployment.sourceOrgId;
        },
        targetOrgName() {
            if (!this.deployment || !this.deployment.targetOrgId) return '-';
            const org = this.orgMap[this.deployment.targetOrgId];
            return org ? org.name : this.deployment.targetOrgId;
        },
        /**
         * 【新增】计算 Salesforce 部署监控页面的 URL
         */
        salesforceDeployUrl() {
            const targetId = this.deployment.targetOrgId;
            const asyncId = this.deployment.lastAsyncId;

            if (!targetId || !asyncId) return '';

            const org = this.orgMap[targetId];
            if (!org || !org.instanceUrl) return '';

            // 标准 Salesforce 部署监控链接
            return `${org.instanceUrl}/changemgmt/monitorDeploymentsDetails.apexp?asyncId=${asyncId}`;
        },
        isProcessing() {
            const s = this.deployment.status;
            const activeStatuses = [
                'Processing', 'Deploying', 'Validating',
                'Pending', 'InProgress', 'Queued',
                'Canceling' // 取消中 仍然算作处理中，禁止操作
            ];
            // 注意：这里没有 'Canceled'。
            // 当状态变为 'Canceled' 时，isProcessing 为 false，用户可以重新编辑和部署。
            return activeStatuses.includes(s) || this.validating || this.deploying;
        },
        calculatedStatusLabel() {
            const status = this.deployment.status;
            const isCheck = this.localCheckOnly;

            if (status === 'Succeeded') return isCheck ? '验证成功' : '部署成功';
            if (status === 'Failed') return isCheck ? '验证失败' : '部署失败';
            if (status === 'Canceled') return '已取消';
            if (status === 'Canceling') return '取消中...';

            if (status === 'Pending' || status === 'Queued') return '排队中...';
            if (status === 'InProgress') return isCheck ? '正在验证...' : '正在部署...';

            if (this.validating || status === 'Validating') return '正在验证...';
            if (this.deploying || status === 'Deploying') return '正在部署...';
            if (status === 'Processing') return '准备中...';

            return status || '未知';
        },
        isPolling() {
            return this.isSocketConnected;
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
        // 【修改】基于筛选结果，计算分页后的数据
        pagedItemList() {
            // 1. 获取经过筛选的总列表
            const allFiltered = this.filteredItemList;

            // 2. 更新总条数（用于分页组件显示）
            this.pagination.total = allFiltered.length;

            // 3. 计算切片索引
            const start = (this.pagination.pageNum - 1) * this.pagination.pageSize;
            const end = start + this.pagination.pageSize;

            // 4. 返回当前页数据
            return allFiltered.slice(start, end);
        },
        filteredItemList() {
            return this.itemList.filter(item => {
                if (this.columnFilters.type && item.metadataType !== this.columnFilters.type) return false;
                if (this.columnFilters.name) {
                    const val = this.getShortName(item.memberName).toLowerCase();
                    const filter = this.columnFilters.name.toLowerCase();
                    const op = this.columnFilters.nameOp;
                    if (op === 'equals') {
                        if (val !== filter) return false;
                    } else if (op === 'not_contains') {
                        if (val.includes(filter)) return false;
                    } else {
                        if (!val.includes(filter)) return false;
                    }
                }
                if (this.columnFilters.parent) {
                    const parent = this.getParentName(item.memberName).toLowerCase();
                    const filter = this.columnFilters.parent.toLowerCase();
                    const op = this.columnFilters.parentOp;
                    if (op === 'equals') {
                        if (parent !== filter) return false;
                    } else if (op === 'not_contains') {
                        if (parent.includes(filter)) return false;
                    } else {
                        if (!parent.includes(filter)) return false;
                    }
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
    watch: {
        'deployment.specifiedTests': {
            handler(val) {
                if (val) {
                    this.specifiedTestsArr = val.split(',').filter(item => item && item.trim());
                } else {
                    this.specifiedTestsArr = [];
                }
            },
            immediate: true
        },
        // 【新增】监听筛选条件变化，重置到第一页
        columnFilters: {
            handler() {
                this.pagination.pageNum = 1;
            },
            deep: true
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
        this.disconnectSocket();
        if (this.statusTimer) clearInterval(this.statusTimer);
    },
    methods: {
        statusType(status) {
            if (status === 'Succeeded') return 'success';
            if (status === 'Failed') return 'danger';
            if (status === 'Canceled') return 'info'; // 已取消用灰色
            if (status === 'Canceling') return 'warning'; // 取消中用黄色
            if (['Processing', 'Deploying', 'Validating', 'Pending', 'InProgress', 'Queued'].includes(status)) return 'warning';
            return 'info';
        },
        // 【新增】分页大小改变
        handleSizeChange(val) {
            this.pagination.pageSize = val;
            this.pagination.pageNum = 1; // 改变大小时重置到第一页
        },

        // 【新增】页码改变
        handleCurrentChange(val) {
            this.pagination.pageNum = val;
            // 翻页后自动滚动到表格顶部（可选体验优化）
            document.querySelector('.el-table__body-wrapper').scrollTop = 0; 
        },
        initData() {
            this.loading = true;
            const p1 = listOrg({ pageNum: 1, pageSize: 100 }).then(res => {
                res.rows.forEach(org => {
                    this.$set(this.orgMap, org.id, org);
                });
            });
            const p2 = this.getDetail();
            const p3 = this.getItems();

            Promise.all([p1, p2, p3]).finally(() => {
                this.loading = false;
                // 如果当前状态是进行中，自动连接 WebSocket
                if (this.isProcessing) {
                    this.initWebSocket();
                }
            });
        },
        refreshData() {
            // 【修复】手动刷新前，必须断开旧连接
            this.disconnectSocket();

            this.loading = true;
            Promise.all([this.getDetail(), this.getItems()]).finally(() => {
                this.loading = false;
                if (this.isProcessing && !this.isSocketConnected) {
                    this.initWebSocket();
                }
            });
        },
        /**
         * 【新增】打开 Salesforce 部署监控页面
         */
        handleOpenSalesforce() {
            if (this.salesforceDeployUrl) {
                window.open(this.salesforceDeployUrl, '_blank');
            } else {
                this.$modal.msgWarning("无法获取目标环境链接或未执行过部署");
            }
        },
        getShortName(name) {
            if (name && name.includes('.')) {
                return name.substring(name.indexOf('.') + 1);
            }
            return name;
        },
        initWebSocket() {
            // 【修复】如果已经连接或正在连接，不再创建新的
            if (this.websocket && (this.websocket.readyState === WebSocket.OPEN || this.websocket.readyState === WebSocket.CONNECTING)) {
                return;
            }

            const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
            const host = window.location.host;
            const token = getToken();
            const baseUrl = process.env.VUE_APP_BASE_API;

            const url = `${protocol}://${host}${baseUrl}/websocket/${this.deploymentId}?token=${token}`;

            this.websocket = new WebSocket(url);
            this.websocket.onopen = this.websocketOnOpen;
            this.websocket.onmessage = this.websocketOnMessage;
            this.websocket.onerror = this.websocketOnError;
            this.websocket.onclose = this.websocketOnClose;
        },

        websocketOnOpen() {
            this.isSocketConnected = true;
            this.socketRetryCount = 0;
        },

        websocketOnMessage(event) {
            try {
                const res = JSON.parse(event.data);

                // 更新 ID
                if (res.id) {
                    this.$set(this.deployment, 'lastAsyncId', res.id);
                }

                // 实时更新状态
                if (res.status) {
                    let newStatus = res.status;
                    if (newStatus === 'Succeeded' && res.checkOnly) {
                        newStatus = 'Validated';
                    }
                    this.deployment.status = newStatus;

                    // 如果是取消中或已取消，停止按钮 loading
                    if (newStatus === 'Canceling' || newStatus === 'Canceled') {
                        this.canceling = false;
                        // 如果是最终取消态，强制停止处理标识
                        if (newStatus === 'Canceled') {
                            this.validating = false;
                            this.deploying = false;
                            this.isCheckingStatus = false;
                        }
                    }
                }

                // 更新错误信息
                if (res.errorMsg || res.errorMessage) {
                    this.$set(this.deployment, 'errorMsg', res.errorMsg || res.errorMessage);
                }

                if (res.hasOwnProperty('checkOnly')) {
                    this.$set(this.deployment, 'checkOnly', res.checkOnly);
                    this.localCheckOnly = res.checkOnly;
                }

                this.updateProgress(res);

                // 【核心修复】检测到任务结束
                if (res.done === true) {
                    // 1. 强制停止前端的 loading 状态
                    this.resetButtonState();
                    this.isProcessing = false;

                    // 2. 只有第一次收到 done 时才弹窗，防止重复
                    if (!this.hasShownSuccess) {
                        this.hasShownSuccess = true;
                        if (['Succeeded', 'Validated'].includes(this.deployment.status)) {
                            this.$modal.msgSuccess(this.localCheckOnly ? "验证成功！" : "部署成功！");
                            this.compStateText = this.localCheckOnly ? "验证完成" : "部署完成";
                        } else {
                            this.$modal.msgError((this.localCheckOnly ? "验证" : "部署") + "失败");
                            this.compStateText = "失败";
                        }
                    }

                    // 3. 彻底断开 Socket
                    this.disconnectSocket();

                    // 4. 延迟刷新全量数据 (等待后端 DB 事务提交)
                    setTimeout(() => {
                        this.getDetail();
                    }, 1000);
                    return;
                }
            } catch (e) {
                console.error("WS Message Error", e);
            }
        },

        websocketOnError(e) {
            this.isSocketConnected = false;
            this.compStateText = "连接异常，等待重试...";
        },

        websocketOnClose(e) {
            this.isSocketConnected = false;
            this.websocket = null;
            if (!this.isExplicitDisconnect && this.isProcessing && this.socketRetryCount < 3) {
                this.socketRetryCount++;
                setTimeout(() => {
                    this.initWebSocket();
                }, 3000);
            }
            // 复位标记，以便下次连接使用
            this.isExplicitDisconnect = false;
        },

        disconnectSocket() {
            if (this.websocket) {
                this.isExplicitDisconnect = true;
                this.websocket.close();
                this.websocket = null;
            }
            this.isSocketConnected = false;
        },

        updateProgress(statusObj) {
            this.compTotal = statusObj.numberComponentsTotal || 0;
            this.compDone = statusObj.numberComponentsDeployed || 0;

            if (this.compTotal > 0) {
                const cPercent = Math.floor((this.compDone / this.compTotal) * 100);
                this.compPercent = Math.max(this.compPercent, cPercent);
            }

            const actionText = this.localCheckOnly ? "验证" : "部署";

            if (statusObj.stateDetail) {
                this.compStateText = statusObj.stateDetail;
            } else if (this.compDone < this.compTotal) {
                this.compStateText = `${actionText}元数据中...`;
            } else if (this.compPercent === 100) {
                this.compStateText = `元数据${actionText}完成`;
            }

            this.testTotal = statusObj.numberTestsTotal || 0;
            this.testDone = statusObj.numberTestsCompleted || 0;
            this.testFailures = statusObj.numberTestErrors || 0;

            if (statusObj.currentTest) {
                this.currentTestName = statusObj.currentTest;
            } else {
                this.currentTestName = '';
            }

            if (this.testTotal > 0) {
                const tPercent = Math.floor((this.testDone / this.testTotal) * 100);
                this.testPercent = tPercent;
            }
        },

        handleDownloadPackage() {
            const fileName = `deployment_pkg_${this.deploymentId}.zip`;
            this.$modal.msgSuccess("正在生成并下载部署包，可能需要几分钟，请耐心等待...");

            request({
                url: '/salesforce/deployment/download/' + this.deploymentId,
                method: 'post',
                responseType: 'blob',
                timeout: 600000
            }).then(async (res) => {
                const isBlob = res.type !== 'application/json';
                if (isBlob) {
                    const blob = new Blob([res]);
                    if (window.navigator.msSaveOrOpenBlob) {
                        navigator.msSaveBlob(blob, fileName);
                    } else {
                        const link = document.createElement('a');
                        const href = window.URL.createObjectURL(blob);
                        link.href = href;
                        link.download = fileName;
                        document.body.appendChild(link);
                        link.click();
                        document.body.removeChild(link);
                        window.URL.revokeObjectURL(href);
                    }
                    this.$modal.msgSuccess("下载已完成！");
                } else {
                    const text = await res.text();
                    const json = JSON.parse(text);
                    this.$modal.msgError(json.msg || "下载失败");
                }
            }).catch(error => {
                console.error("Download error:", error);
                let msg = "下载失败，请联系管理员";
                if (error.message && error.message.includes('timeout')) {
                    msg = "生成部署包超时，请稍后重试或减小包体积";
                }
                this.$modal.msgError(msg);
            });
        },

        /**
         * 【新增】处理取消部署
         */
        handleCancelDeploy() {
            // 二次确认，防止误触
            this.$confirm('确定要终止当前的 验证/部署 任务吗？<br/><span style="color:#F56C6C;font-size:12px">注意：Salesforce 可能需要几秒钟来处理取消请求，且部分已提交的更改可能无法回滚。</span>', '警告', {
                confirmButtonText: '确定取消',
                cancelButtonText: '我再想想',
                type: 'warning',
                dangerouslyUseHTMLString: true,
                confirmButtonClass: 'el-button--danger'
            }).then(() => {
                this.canceling = true;
                cancelDeployment(this.deploymentId).then(res => {
                    this.$modal.msgSuccess("取消请求已发送，正在等待 Salesforce 响应...");
                    // 注意：这里不需要手动设置 status = 'Canceling'
                    // 因为后端调用成功后会推送 WebSocket 消息，自动更新状态
                }).catch(() => {
                    this.canceling = false;
                });
            }).catch(() => {
                // 用户点击取消，不做操作
            });
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
        handleBrowserAction(event) {
            if (event.action === 'add') {
                const itemToAdd = [{ metadataType: event.type, memberName: event.name }];
                addDeploymentItems(this.deploymentId, itemToAdd).then(res => {
                    this.refreshBrowserMap(event);
                });
            } else if (event.action === 'batch-add') {
                const itemsPayload = event.items.map(i => ({
                    metadataType: i.type,
                    memberName: i.name
                }));
                addDeploymentItems(this.deploymentId, itemsPayload).then(res => {
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
        getDictLabel(value) {
            if (!value) return '';
            const datas = this.dict.type.sys_salesforce_metadata_type;
            if (datas) {
                const found = datas.find(item => item.value === value);
                if (found) return found.label;
            }
            return value;
        },
        clearColumnFilters() {
            this.columnFilters = {
                type: '',
                name: '',
                parent: '',
                status: ''
            };
            this.$modal.msgSuccess("筛选条件已重置");
        },
        handleDeploy(checkOnly) {
            const actionName = checkOnly ? "验证" : "部署";
            this.$confirm(`确认要执行【${actionName}】操作吗？`, "警告", {
                confirmButtonText: "确定",
                cancelButtonText: "取消",
                type: "warning"
            }).then(() => {
                this.localCheckOnly = checkOnly;
                this.isQuickDeploy = false;
                this.$set(this.deployment, 'checkOnly', checkOnly);
                this.$set(this.deployment, 'errorMsg', '');

                if (checkOnly) this.validating = true;
                else this.deploying = true;

                this.resetProgress();
                this.hasShownSuccess = false;

                deployPackage(this.deploymentId, checkOnly).then(res => {
                    this.compStateText = "正在连接服务器...";
                    this.initWebSocket();
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
                this.localCheckOnly = false;
                this.isQuickDeploy = true;
                this.$set(this.deployment, 'checkOnly', false);
                this.$set(this.deployment, 'errorMsg', '');

                this.resetProgress();
                this.hasShownSuccess = false;
                this.compStateText = "正在启动快速部署...";

                quickDeploy(this.deploymentId).then(res => {
                    this.initWebSocket();
                }).catch(() => {
                    this.deploying = false;
                    this.isQuickDeploy = false;
                });
            });
        },
        handleGlobalRecalculate() {
            if (this.activeTab === 'selected') {
                this.handleCheckStatus();
            }
            else if (this.activeTab === 'add') {
                if (this.$refs.metaBrowser) {
                    this.$refs.metaBrowser.handleQuery();
                    this.$modal.msgSuccess("正在刷新元数据列表差异状态...");
                }
            }
        },
        handleSaveConfig() {
            const specTestsStr = this.specifiedTestsArr.join(',');

            const data = {
                id: this.deploymentId,
                testLevel: this.deployment.testLevel,
                specifiedTests: specTestsStr
            };
            updateDeployment(data).then(res => {
                this.$modal.msgSuccess("配置已保存");
                this.deployment.specifiedTests = specTestsStr;
            });
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
        resetButtonState() {
            this.validating = false;
            this.deploying = false;
        },
        getDetail() {
            return getDeployment(this.deploymentId).then(res => {
                const newData = res.data || {};
                if (newData.status === 'Validated') {
                    this.localCheckOnly = true;
                    newData.checkOnly = true;
                }
                this.deployment = newData;
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
                params: { orgId, type, name },
                timeout: 600000 // 10分钟
            }).then(response => {
                loading.close();
                this.codeContent = response.data;
                this.oldCodeContent = "";
                this.isDiffMode = false;
                this.previewTitle = `${type}: ${name}`;
                this.openCode = true;
            }).catch(() => loading.close());
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
                },
                timeout: 600000 // 10分钟
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
        clearColumnFilters() {
            this.columnFilters = {
                type: '',
                name: '',
                nameOp: 'contains',
                parent: '',
                parentOp: 'contains',
                status: ''
            };
            this.$modal.msgSuccess("筛选条件已重置");
        }
    }
};
</script>

<style scoped>
/* 保持原有样式，此处省略以节省篇幅，请直接保留你文件中的 CSS */
.mb-20 {
    margin-bottom: 20px;
}

.sticky-card {
    position: sticky;
    top: 0;
    z-index: 999;
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

.count-tag {
    margin-left: 15px;
    font-size: 13px;
    letter-spacing: 0.5px;
}

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

.error-msg-box {
    white-space: pre-wrap;
    line-height: 1.6;
    font-family: Consolas, monospace;
    max-height: 200px;
    overflow-y: auto;
    font-size: 13px;
}

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

.custom-header {
    padding: 4px 0;
}

.custom-header span {
    display: block;
    margin-bottom: 8px;
    color: #606266;
    font-weight: 600;
}

.tabs-card {
    margin-bottom: 0;
}

.item-badge {
    margin-top: -3px;
    margin-left: 5px;
}

.quick-deploy-tip {
    color: #67C23A;
    padding: 10px 0;
    text-align: center;
    background-color: #f0f9eb;
    border-radius: 4px;
    border: 1px solid #e1f3d8;
}

/* 【关键调整】让 el-tabs__header 吸顶 */
/* ::v-deep .el-tabs__header {
    position: -webkit-sticky;
    position: sticky;
    top: 84px;
    z-index: 10;
    background-color: #fff;
    margin-bottom: 0;
    padding-top: 10px;
    box-shadow: 0 2px 4px 0 rgba(0, 0, 0, 0.05);
} */
</style>