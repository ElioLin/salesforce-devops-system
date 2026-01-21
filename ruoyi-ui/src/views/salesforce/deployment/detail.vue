<template>
    <div class="app-container">
        <el-card shadow="never" class="mb-20" v-loading="loading">
            <div slot="header" class="clearfix">
                <span class="card-title">{{ deployment.title || '部署包详情' }}</span>

                <el-tag size="medium" :type="statusType(deployment.status)" effect="dark" style="margin-left: 10px">
                    {{ getDictLabelByValue(deployment.status) }}
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

                    <el-button type="text" :icon="showConsole ? 'el-icon-arrow-up' : 'el-icon-monitor'"
                        style="margin-right: 15px;" @click="toggleConsole">
                        {{ showConsole ? '收起日志' : '部署日志' }}
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
                <el-col :span="8">
                    <span class="label">源环境:</span> <span class="val">{{ sourceOrgName }}</span>
                </el-col>
                <el-col :span="8">
                    <span class="label">目标环境:</span> <span class="val">{{ targetOrgName }}</span>
                </el-col>
                <el-col :span="8" style="text-align: right">
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
                <div class="config-header">
                    <span class="config-title"><i class="el-icon-s-operation"></i> 部署配置策略</span>
                    <span class="save-status" :class="saveState.status">
                        <i :class="saveState.icon"></i> {{ saveState.text }}
                    </span>
                </div>

                <el-form label-width="90px" size="small" :inline="true" class="config-form">
                    <el-form-item label="测试级别">
                        <el-select v-model="deployment.testLevel" placeholder="请选择测试级别" style="width: 240px">
                            <el-option label="默认 (NoTestRun / Default)" value="NoTestRun">
                                <span style="float: left">默认 (Default)</span>
                                <span style="float: right; color: #8492a6; font-size: 12px">生产环境必跑</span>
                            </el-option>
                            <el-option label="运行本地测试 (RunLocalTests)" value="RunLocalTests" />
                            <el-option label="指定测试类 (RunSpecifiedTests)" value="RunSpecifiedTests" />
                        </el-select>
                    </el-form-item>

                    <template v-if="deployment.testLevel === 'RunSpecifiedTests'">
                        <el-form-item label="指定类名" class="spec-test-item">
                            <el-select ref="testSelect" v-model="specifiedTestsArr" multiple filterable allow-create
                                default-first-option placeholder="支持直接粘贴 (逗号/空格/换行分隔)，自动识别" style="width: 600px"
                                no-data-text="请输入类名" @paste.native.capture.prevent="handleSmartPaste">
                            </el-select>
                            <el-button type="text" size="mini" icon="el-icon-delete" style="margin-left: 5px;"
                                v-if="specifiedTestsArr.length > 0" @click="specifiedTestsArr = []">清空</el-button>
                        </el-form-item>
                    </template>
                </el-form>
            </div>
            <build-console ref="buildConsole" :visible="showConsole"
                :title="'DEVOPS TERMINAL - ' + (deployment.lastAsyncId || 'READY')" />

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
            <el-tabs v-model="activeTab" type="card" @tab-click="handleTabClick">
                <el-tab-pane name="selected">
                    <span slot="label">
                        <i class="el-icon-folder-checked"></i> 已添加元数据
                        <el-badge :value="itemList.length" class="item-badge" type="primary"
                            v-if="itemList.length > 0" />
                    </span>

                    <div class="list-header clearfix mb-10">
                        <div class="left-panel">
                            <el-tag size="small" type="info" effect="plain" class="count-tag">
                                当前筛选总条数: <b class="text-primary">{{ filteredItemList.length }}</b> / 总共: {{
                                    itemList.length
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

                <el-tab-pane name="history">
                    <span slot="label"><i class="el-icon-time"></i> 部署历史 & 审计</span>

                    <div class="mb-10 text-right">
                        <el-button icon="el-icon-refresh" size="mini" @click="getHistoryList">刷新日志</el-button>
                    </div>

                    <el-table v-loading="historyLoading" :data="historyList" border stripe style="width: 100%">
                        <el-table-column prop="startTime" label="执行时间" width="160" align="center">
                            <template slot-scope="scope">
                                {{ parseTime(scope.row.startTime) }}
                            </template>
                        </el-table-column>
                        <el-table-column prop="startTime" label="结束时间" width="160" align="center">
                            <template slot-scope="scope">
                                {{ parseTime(scope.row.endTime) }}
                            </template>
                        </el-table-column>
                        <el-table-column prop="type" label="操作类型" width="100" align="center">
                            <template slot-scope="scope">
                                <el-tag v-if="scope.row.type === 'Validate'" type="warning" effect="plain">仅验证</el-tag>
                                <el-tag v-else-if="scope.row.type === 'Deploy'" type="primary"
                                    effect="plain">完整部署</el-tag>
                                <el-tag v-else-if="scope.row.type === 'Quick'" type="success"
                                    effect="plain">快速部署</el-tag>
                                <el-tag v-else-if="scope.row.type === 'Rollback'" type="danger"
                                    effect="dark">回滚操作</el-tag>
                                <span v-else>{{ scope.row.type }}</span>
                            </template>
                        </el-table-column>

                        <el-table-column prop="status" label="最终状态" width="100" align="center">
                            <!-- <template slot-scope="scope">
                                <el-tag :type="statusType(scope.row.status)" size="small">{{ scope.row.status
                                    }}</el-tag>
                            </template> -->
                            <template slot-scope="scope">
                                <dict-tag :options="dict.type.sys_salesforce_deploy_status" :value="scope.row.status" />
                            </template>
                        </el-table-column>

                        <el-table-column prop="createBy" label="执行人" width="120" align="center" />

                        <el-table-column prop="errorMsg" label="结果/备注" show-overflow-tooltip min-width="200" />

                        <el-table-column label="操作" width="220" align="center" fixed="right">
                            <template slot-scope="scope">
                                <el-button size="mini" type="text" icon="el-icon-document"
                                    @click="handleViewHistoryDetail(scope.row)">明细</el-button>

                                <el-button size="mini" type="text" icon="el-icon-download" v-if="scope.row.backupPath"
                                    @click="handleDownloadBackup(scope.row)">下载备份</el-button>

                                <el-button size="mini" type="text" icon="el-icon-refresh-left" class="text-danger"
                                    v-if="canRollback(scope.row)" :disabled="isProcessing"
                                    @click="handleRollback(scope.row)">回滚</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                </el-tab-pane>
            </el-tabs>
        </el-card>

        <diff-viewer-dialog ref="diffDialog" />

        <el-dialog title="部署包内容全览" :visible.sync="previewDialog.open" width="85%" append-to-body top="5vh">
            <div v-loading="previewDialog.loading" style="height: 650px;">
                <el-row :gutter="20" style="height: 100%;">
                    <el-col :span="6" style="height: 100%; display: flex; flex-direction: column;">
                        <div class="file-list-header">
                            <span>文件清单 ({{ previewDialog.files.length }})</span>
                            <el-button type="text" size="mini" icon="el-icon-download"
                                @click="handleDownloadPackage">下载ZIP</el-button>
                        </div>

                        <div style="margin-bottom: 10px; padding: 0 2px;">
                            <el-input v-model="previewSearchQuery" placeholder="搜索文件..." prefix-icon="el-icon-search"
                                size="small" clearable>
                            </el-input>
                        </div>

                        <div class="file-list-container">
                            <ul class="file-ul">
                                <li class="file-li" :class="{ active: previewDialog.currentFile === 'package.xml' }"
                                    @click="selectPreviewFile('package.xml')"
                                    v-if="'package.xml'.includes(previewSearchQuery.toLowerCase()) || !previewSearchQuery">
                                    <i class="el-icon-s-cooperation" style="color:#E6A23C;"></i> package.xml
                                </li>

                                <li v-for="(file, index) in filteredPreviewFiles" :key="index" class="file-li"
                                    :class="{ active: previewDialog.currentFile === file }"
                                    @click="selectPreviewFile(file)">
                                    <i class="el-icon-document" style="color:#909399;"></i> {{ file }}
                                </li>

                                <li v-if="filteredPreviewFiles.length === 0 && previewSearchQuery"
                                    style="text-align:center; color:#909399; padding: 20px; font-size:12px">
                                    无匹配文件
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

        <el-dialog title="部署变更明细" :visible.sync="historyDetailDialog.open" width="70%" append-to-body>
            <el-table :data="historyDetailDialog.list" border stripe height="500">
                <el-table-column prop="metadataType" label="元数据类型" width="180" />
                <el-table-column prop="memberName" label="名称" />
                <el-table-column prop="action" label="变更动作" width="120" align="center">
                    <template slot-scope="scope">
                        <el-tag v-if="scope.row.action === 'CREATE'" type="success">新增 (Create)</el-tag>
                        <el-tag v-else-if="scope.row.action === 'UPDATE'" type="warning">修改 (Update)</el-tag>
                        <el-tag v-else type="info">无变更</el-tag>
                    </template>
                </el-table-column>
            </el-table>
            <div slot="footer" class="dialog-footer">
                <el-button @click="historyDetailDialog.open = false">关 闭</el-button>
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
    cancelDeployment,
    listDeploymentHistory,
    getDeploymentHistoryDetails,
    rollbackDeployment,
} from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import MetadataBrowser from "@/views/salesforce/org/MetadataBrowser";
import MonacoEditor from '@/components/MonacoEditor';
import request from '@/utils/request';
import { download } from "@/utils/request";
import { getToken } from "@/utils/auth";
import BuildConsole from "./components/BuildConsole";
import DiffViewerDialog from "./components/DiffViewerDialog";

export default {
    name: "DeploymentDetail",
    dicts: ['sys_salesforce_metadata_type', 'sys_salesforce_deploy_status'],
    components: { MetadataBrowser, MonacoEditor, BuildConsole, DiffViewerDialog },
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

            isExplicitDisconnect: false, // 【新增】标记是否为显式断开
            canceling: false,
            pagination: {
                pageNum: 1,
                pageSize: 20, // 默认每页显示20条，减少渲染压力
                total: 0
            },
            historyList: [],
            historyLoading: false,
            historyDetailDialog: {
                open: false,
                list: []
            },
            previewSearchQuery: '',
            configLoaded: false,
            showConsole: false, // 默认展开，用户体验更好，部署时能看到动静
            saveState: {
                status: 'saved', // saved, saving, error
                text: '配置已同步',
                icon: 'el-icon-check'
            },
            saveTimer: null, // 防抖定时器
        };
    },
    computed: {
        sourceOrgName() {
            if (!this.deployment || !this.deployment.sourceOrgId) return '-';
            const org = this.orgMap[this.deployment.sourceOrgId];
            return org ? org.name + ' (' + org.username + ')' : this.deployment.sourceOrgId;
        },
        targetOrgName() {
            if (!this.deployment || !this.deployment.targetOrgId) return '-';
            const org = this.orgMap[this.deployment.targetOrgId];
            return org ? org.name + ' (' + org.username + ')' : this.deployment.targetOrgId;
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
        },
        //过滤计算属性
        filteredPreviewFiles() {
            if (!this.previewSearchQuery) {
                // 排除 package.xml，模板中已手动处理
                return this.previewDialog.files.filter(f => f !== 'package.xml');
            }
            const query = this.previewSearchQuery.toLowerCase();
            return this.previewDialog.files.filter(file =>
                file !== 'package.xml' && file.toLowerCase().includes(query)
            );
        }
    },
    watch: {
        // 【新增】监听筛选条件变化，重置到第一页
        columnFilters: {
            handler() {
                this.pagination.pageNum = 1;
            },
            deep: true
        },
        // 【优化】监听配置变化，触发自动保存
        'deployment.testLevel'(val) {
            // 【核心优化】如果配置还没加载完成，或者是初始化赋值，不触发保存
            if (!this.configLoaded) return;

            this.triggerAutoSave();
        },

        'specifiedTestsArr'(val) {
            // 【核心优化】初始化时不触发
            if (!this.configLoaded) return;

            if (this.deployment.testLevel === 'RunSpecifiedTests') {
                this.triggerAutoSave();
            }
        },

        // 【注意】保留原有的 deployment.specifiedTests 监听用于初始化，但要注意不要死循环
        // 建议修改原有的 watch 逻辑如下：
        'deployment.specifiedTests': {
            handler(val) {
                if (val) {
                    this.specifiedTestsArr = val.split(',').filter(item => item && item.trim());
                } else {
                    this.specifiedTestsArr = [];
                }
            },
            immediate: true
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
        getDictLabelByValue(value) {
            if (!value) return '未知状态';
            const datas = this.dict.type.sys_salesforce_deploy_status;
            if (datas) {
                const found = datas.find(item => item.value === value);
                if (found) return found.label;
            }
            return value; // 没找到则显示原始英文
        },
        /**
         * 【优化】获取状态标签颜色
         * 逻辑：优先读取字典配置 -> 其次根据状态值硬编码兜底
         */
        getStatusTagType(value) {
            if (!value) return 'info';

            // 1. 尝试从字典中获取配置的样式 (listClass)
            const datas = this.dict.type.sys_salesforce_deploy_status;
            if (datas) {
                const found = datas.find(item => item.value === value);
                if (found && found.listClass) {
                    // 若依字典的 listClass 映射到 ElementUI Tag type
                    if (found.listClass === 'default') return 'info';
                    if (found.listClass === 'primary') return ''; // 默认蓝色
                    return found.listClass; // success, warning, danger, info
                }
            }

            // 2. 【新增兜底策略】如果字典里没配颜色，或者字典加载延迟，使用硬编码的行业标准色
            // 确保 "部署成功" 永远是绿色，不会变成灰色
            switch (value) {
                case 'Succeeded':
                case 'Validated':
                    return 'success'; // 绿色
                case 'Failed':
                case 'Canceled':
                    return 'danger';  // 红色
                case 'Deploying':
                case 'Validating':
                case 'Processing':
                case 'InProgress':
                    return 'primary'; // 蓝色 (处理中)
                case 'Pending':
                case 'Queued':
                    return 'warning'; // 黄色 (排队)
                default:
                    return 'info';    // 灰色 (未知)
            }
        },
        statusType(status) {
            return this.getStatusTagType(status);
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
            // 【建议新增】连接成功后，立即主动查询一次最新状态，同步进度条，防止等待被动推送的空窗期
            if (this.deployment.targetOrgId && this.deployment.lastAsyncId) {
                checkDeployStatus(this.deployment.targetOrgId, this.deployment.lastAsyncId).then(res => {
                    // 复用消息处理逻辑，手动模拟一个事件
                    this.websocketOnMessage({ data: JSON.stringify(res.data) });
                });
            }
        },

        // 【新增】切换控制台显示
        toggleConsole() {
            this.showConsole = !this.showConsole;
        },

        // 【新增】追加日志核心方法
        appendLog(message, level = 'info') {
            if (this.$refs.buildConsole) {
                this.$refs.buildConsole.appendLog(message, level);
            }
        },

        websocketOnMessage(event) {
            try {
                const res = JSON.parse(event.data);

                // --- 1. 日志对接 (修复版) ---

                // 【修复】通过 ref 访问子组件的日志数据进行去重判断
                let lastLog = '';
                if (this.$refs.buildConsole && this.$refs.buildConsole.logs.length > 0) {
                    const logs = this.$refs.buildConsole.logs;
                    lastLog = logs[logs.length - 1].message;
                }

                // 捕获状态详情 (stateDetail) -> Info 日志
                if (res.stateDetail && res.stateDetail !== lastLog) {
                    this.appendLog(res.stateDetail, 'info');
                }

                // 捕获错误信息 (errorMessage) -> Error 日志
                if ((res.errorMsg || res.errorMessage) && !res.done) {
                    this.appendLog(res.errorMsg || res.errorMessage, 'error');
                }

                // 捕获任务ID变化
                if (res.id && res.id !== this.deployment.lastAsyncId) {
                    this.appendLog(`Async Process ID Assigned: ${res.id}`, 'cmd');
                }
                // --- 日志对接结束 ---

                if (res.id) {
                    this.$set(this.deployment, 'lastAsyncId', res.id);
                }

                if (res.status) {
                    let newStatus = res.status;
                    if (newStatus === 'Succeeded' && res.checkOnly) {
                        newStatus = 'Validated';
                    }
                    // 状态变化时记录日志
                    if (this.deployment.status !== newStatus) {
                        this.appendLog(`Status Changed: ${this.deployment.status} -> ${newStatus}`, 'warn');
                    }
                    this.deployment.status = newStatus;

                    if (newStatus === 'Canceling' || newStatus === 'Canceled') {
                        this.canceling = false;
                        if (newStatus === 'Canceled') {
                            this.validating = false;
                            this.deploying = false;
                            this.isCheckingStatus = false;
                            this.appendLog("Task Canceled by User.", 'error');
                        }
                    }
                }

                if (res.errorMsg || res.errorMessage) {
                    this.$set(this.deployment, 'errorMsg', res.errorMsg || res.errorMessage);
                }

                if (res.hasOwnProperty('checkOnly')) {
                    this.$set(this.deployment, 'checkOnly', res.checkOnly);
                    this.localCheckOnly = res.checkOnly;
                }

                // 【关键】确保这行代码能被执行到，进度条才会动
                this.updateProgress(res);

                if (res.done === true) {
                    this.resetButtonState();
                    this.isProcessing = false;

                    if (!this.hasShownSuccess) {
                        this.hasShownSuccess = true;
                        if (['Succeeded', 'Validated'].includes(this.deployment.status)) {
                            this.$modal.msgSuccess(this.localCheckOnly ? "验证成功！" : "部署成功！");
                            this.compStateText = this.localCheckOnly ? "验证完成" : "部署完成";
                            this.appendLog("Process Finished Successfully.", 'success');
                        } else {
                            let errMsg = res.errorMessage || res.errorMsg || "未知错误";
                            this.appendLog("Process Failed: " + errMsg, 'error');
                            if (res.details && res.details.componentFailures) {
                                res.details.componentFailures.forEach(fail => {
                                    this.appendLog(`[${fail.fileName}] ${fail.problem}`, 'error');
                                });
                            }
                            this.$modal.msgError((this.localCheckOnly ? "验证" : "部署") + "失败");
                            this.compStateText = "失败";
                        }
                    }

                    this.disconnectSocket();
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
            this.previewSearchQuery = '';
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
                const itemToAdd = [{ metadataType: event.type, memberName: event.name, diffStatus: event.diffStatus || 'Unknown' }];
                addDeploymentItems(this.deploymentId, itemToAdd).then(res => {
                    this.refreshBrowserMap(event);
                    // 【优化】移除 this.startStatusPolling(); 
                    // 因为现在状态是直接带进去的，不需要后台异步计算，也不需要前端轮询
                    // this.$modal.msgSuccess("已添加");
                });
            } else if (event.action === 'batch-add') {
                const itemsPayload = event.items.map(i => ({
                    metadataType: i.type,
                    memberName: i.name,
                    diffStatus: i.diffStatus || 'Unknown'
                }));
                addDeploymentItems(this.deploymentId, itemsPayload).then(res => {
                    this.refreshBrowserMap(event, true);
                    // 【修复】批量添加后也开启轮询
                    // this.startStatusPolling();
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

                this.showConsole = true;
                this.appendLog(`Starting ${checkOnly ? 'Validation' : 'Deployment'} sequence...`, 'cmd'); // <--- 新增

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

                // 【新增】展开控制台
                this.showConsole = true;
                this.appendLog("Starting Quick Deploy sequence...", 'cmd');

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
                this.configLoaded = false;
                const newData = res.data || {};
                if (newData.status === 'Validated') {
                    this.localCheckOnly = true;
                    newData.checkOnly = true;
                }
                this.deployment = newData;

                // 【新增】如果当前状态是“进行中”，自动展开控制台，方便用户查看进度
                // 注意：这里复用了 computed 中的 isProcessing 逻辑判断
                const activeStatuses = [
                    'Processing', 'Deploying', 'Validating',
                    'Pending', 'InProgress', 'Queued', 'Canceling'
                ];
                if (activeStatuses.includes(this.deployment.status)) {
                    this.showConsole = true;
                }
                this.$nextTick(() => {
                    this.configLoaded = true;
                });
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
            removeDeploymentItems(row.id).then(() => {
                this.getItems();
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
                timeout: 600000
            }).then(response => {
                loading.close();
                const title = `${type}: ${name}`;
                const lang = this.getLanguage(name);
                this.$refs.diffDialog.open(response.data, "", title, lang, false);
            }).catch(() => loading.close());
        },
        handleDiff(row) {
            // 1. 优先使用传入参数中的 targetOrgId (来自元数据浏览器的选择)，如果没有则使用部署包默认的
            const targetOrgId = row.targetOrgId || this.deployment.targetOrgId;
            const sourceOrgId = row.sourceOrgId || this.deployment.sourceOrgId;

            if (!targetOrgId) {
                this.$modal.msgWarning("请先设置部署包的目标环境，或在下方列表中选择比对基准环境！");
                return;
            }

            // 兼容传入的是 list row 还是 emit data
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
                    sourceOrgId: sourceOrgId,
                    targetOrgId: targetOrgId,
                    type: type,
                    name: name
                },
                timeout: 600000
            }).then(response => {
                loading.close();
                const diffData = response.data;

                // 【修复】定义局部变量 title 和 lang
                const title = `比对: ${name} (${type}) [左:目标环境 vs 右:源环境]`;
                const lang = this.getLanguage(name);

                // 调用子组件打开
                if (this.$refs.diffDialog) {
                    this.$refs.diffDialog.open(
                        diffData.sourceContent,
                        diffData.targetContent,
                        title,
                        lang,
                        true
                    );
                } else {
                    this.$modal.msgError("比对组件加载失败，请刷新页面重试");
                }
            }).catch((e) => {
                loading.close();
                console.error("比对失败:", e); // 【修复】打印具体错误日志，防止静默失败
                this.$modal.msgError("拉取比对数据失败，请查看控制台日志");
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
        },
        /**
         * 【新增】获取部署历史
         */
        getHistoryList() {
            if (!this.deploymentId) return;
            this.historyLoading = true;
            listDeploymentHistory(this.deploymentId).then(res => {
                this.historyList = res.data || [];
                this.historyLoading = false;
            }).catch(() => {
                this.historyLoading = false;
            });
        },

        /**
         * 【新增】监听 Tab 切换，点到历史 Tab 时自动加载
         * 需要在 <el-tabs> 上加 @tab-click="handleTabClick"
         */
        // 注意：请去 template 里的 el-tabs 标签加上 @tab-click="handleTabClick"
        handleTabClick(tab) {
            if (tab.name === 'history') {
                this.getHistoryList();
            }
        },

        /**
         * 【新增】判断是否可以回滚
         * 规则：必须是 Deploy/Quick/Rollback 类型，且状态是 Succeeded，且有备份路径
         */
        canRollback(row) {
            const validTypes = ['Deploy', 'Quick', 'Rollback'];
            return validTypes.includes(row.type) &&
                row.status === 'Succeeded' &&
                row.backupPath;
        },

        /**
         * 【新增】查看历史明细
         */
        handleViewHistoryDetail(row) {
            this.historyDetailDialog.open = true;
            this.historyDetailDialog.list = [];
            getDeploymentHistoryDetails(row.id).then(res => {
                this.historyDetailDialog.list = res.data || [];
            });
        },

        /**
         * 【新增】下载备份文件
         */
        handleDownloadBackup(row) {
            const fileName = `backup_${this.deploymentId}_${row.id}.zip`;
            this.$modal.msgSuccess("正在请求下载备份文件...");

            // 使用通用下载 request，注意 URL 需要后端对应 Controller 支持
            // 假设后端接口为 /salesforce/deployment/history/download/{historyId}
            request({
                url: '/salesforce/deployment/history/download/' + row.id,
                method: 'post',
                responseType: 'blob'
            }).then((res) => {
                const blob = new Blob([res]);
                const link = document.createElement('a');
                link.href = window.URL.createObjectURL(blob);
                link.download = fileName;
                link.click();
            }).catch(error => {
                this.$modal.msgError("下载备份失败");
            });
        },
        /**
         * 【新增】执行回滚操作
         */
        handleRollback(row) {
            const confirmMsg = `
                <p>确定要回滚这条部署记录吗？</p>
                <ul style="text-align:left;color:#606266;font-size:13px">
                    <li>执行时间：${this.parseTime(row.startTime)}</li>
                    <li>操作类型：${row.type}</li>
                </ul>
                <p style="color:#F56C6C;font-weight:bold">警告：</p>
                <p style="color:#F56C6C;font-size:12px">
                    1. 将使用备份文件还原所有被修改(UPDATE)的元数据。<br/>
                    2. 将永久删除本次部署新增(CREATE)的元数据。<br/>
                    3. 此操作不可逆！
                </p>
            `;

            this.$confirm(confirmMsg, '危险操作：回滚', {
                confirmButtonText: '确定回滚',
                cancelButtonText: '取消',
                type: 'warning',
                dangerouslyUseHTMLString: true,
                confirmButtonClass: 'el-button--danger'
            }).then(() => {
                this.deploying = true; // 复用部署中的 loading 状态
                this.resetProgress();
                this.compStateText = "正在准备回滚包...";

                // 【新增】展开控制台
                this.showConsole = true;
                this.appendLog("Starting Rollback sequence...", 'cmd');

                rollbackDeployment(row.id).then(res => {
                    this.$modal.msgSuccess("回滚请求已发送，开始执行...");
                    // 重新连接 Socket 进行监控，因为回滚本质上是一个新的部署任务
                    this.initWebSocket();
                    // 切换回详情 Tab 看进度
                    // this.activeTab = 'selected'; // 可选：是否跳回主页看进度条
                }).catch(err => {
                    this.deploying = false;
                    this.$modal.msgError("回滚启动失败: " + err.msg);
                });
            });
        },
        // 【新增】智能粘贴处理
        handleSmartPaste(e) {
            // 1. 获取剪贴板文本
            let clipboardData = e.clipboardData || window.clipboardData;
            if (!clipboardData) return;
            let text = clipboardData.getData('Text');

            if (!text) return;

            // 2. 智能拆分：支持 逗号、空格、换行符、分号
            // Regex: /[\s,;\n]+/ 会匹配所有空白和常见分隔符
            let items = text.split(/[\s,;\n]+/).filter(item => item && item.trim().length > 0);

            if (items.length > 0) {
                // 3. 合并去重
                const newSet = new Set([...this.specifiedTestsArr, ...items]);
                this.specifiedTestsArr = Array.from(newSet);

                this.$message.success(`已自动识别并添加 ${items.length} 个测试类`);

                // 4. 解决 ElementUI select 输入框在粘贴后残留文本的问题
                // 强制让 Select 失去焦点再获得焦点，或者直接重置 query
                this.$nextTick(() => {
                    if (this.$refs.testSelect) {
                        this.$refs.testSelect.query = '';
                        this.$refs.testSelect.selectedLabel = '';
                        // 也可以尝试调用 blur 让输入框收起
                        // this.$refs.testSelect.blur();
                    }
                });
            }
        },

        // 【新增】触发自动保存（防抖）
        triggerAutoSave() {
            // 设置状态为“正在保存...”但暂不发送请求，给用户反应时间
            this.saveState = { status: 'saving', text: '正在保存...', icon: 'el-icon-loading' };

            if (this.saveTimer) clearTimeout(this.saveTimer);

            this.saveTimer = setTimeout(() => {
                this.doSaveConfig();
            }, 1000); // 1秒后执行保存，避免频繁请求
        },

        // 【新增】执行保存逻辑
        doSaveConfig() {
            const specTestsStr = this.specifiedTestsArr.join(',');

            // 只有当数据真的变化了才调API（可选优化，这里为了简单直接调）
            const data = {
                id: this.deploymentId,
                testLevel: this.deployment.testLevel,
                specifiedTests: specTestsStr
            };

            updateDeployment(data).then(res => {
                this.saveState = { status: 'saved', text: '配置已保存', icon: 'el-icon-check' };
                // 同步一下主对象，防止某些逻辑依赖
                this.deployment.specifiedTests = specTestsStr;
            }).catch(err => {
                this.saveState = { status: 'error', text: '保存失败，请重试', icon: 'el-icon-warning' };
            });
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
    /* height: calc(100% - 45px); <-- 【删除】 */
    height: 0;
    /* 【新增】关键：让 flex 容器内的滚动生效 */
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

.env-col {
    display: flex;
    align-items: center;
    overflow: hidden;
    /* 防止溢出 */
}

.text-truncate {
    display: inline-block;
    max-width: 100%;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    vertical-align: bottom;
}

.label {
    color: #909399;
    margin-right: 8px;
    font-weight: 500;
    white-space: nowrap;
    flex-shrink: 0;
    /* 防止 label 被压缩 */
}

.val {
    color: #303133;
    font-weight: 600;
    flex: 1;
    /* 让值占据剩余空间 */
}

.config-section {
    margin-top: 20px;
    background-color: #f8f9fa;
    /* 更柔和的背景色 */
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 15px 20px;
    position: relative;
    transition: all 0.3s;
}

.config-section:hover {
    box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.05);
}

.config-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 15px;
    padding-bottom: 10px;
    border-bottom: 1px solid #e4e7ed;
}

.config-title {
    font-weight: bold;
    font-size: 14px;
    color: #303133;
}

.config-form {
    margin-bottom: 0;
}

.config-tip {
    font-size: 12px;
    color: #909399;
    margin-left: 90px;
    /* 对齐 label */
    margin-top: 5px;
}

/* 自动保存状态样式 */
.save-status {
    font-size: 13px;
    transition: all 0.3s;
}

.save-status.saved {
    color: #67C23A;
}

.save-status.saving {
    color: #E6A23C;
}

.save-status.error {
    color: #F56C6C;
}
</style>