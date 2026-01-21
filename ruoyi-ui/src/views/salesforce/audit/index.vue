<template>
    <div class="app-container">
        <el-card shadow="never">
            <div slot="header" class="clearfix">
                <span><i class="el-icon-s-opportunity"></i> 元数据审计中心</span>
            </div>

            <el-form :inline="true" size="small" class="audit-search">
                <el-form-item label="元数据类型">
                    <el-select v-model="queryParams.type" placeholder="如 ApexClass" filterable clearable>
                        <el-option v-for="dict in dict.type.sys_salesforce_metadata_type" :key="dict.value"
                            :label="dict.label" :value="dict.value" />
                    </el-select>
                </el-form-item>
                <el-form-item label="元数据名称">
                    <el-input v-model="queryParams.name" placeholder="支持模糊搜索 (如 Order)" clearable
                        @keyup.enter.native="handleQuery" />
                </el-form-item>
                <el-form-item>
                    <el-button type="primary" icon="el-icon-search" @click="handleQuery"
                        :loading="loading">搜索变更历史</el-button>
                </el-form-item>
            </el-form>

            <div v-loading="loading" class="audit-timeline-box">
                <el-empty v-if="!loading && list.length === 0" description="暂无数据，请输入条件搜索"></el-empty>

                <el-timeline v-else>
                    <el-timeline-item v-for="(item, index) in list" :key="index" :timestamp="item.startTime"
                        placement="top" :color="getStatusColor(item.status)">

                        <el-card class="timeline-card">
                            <div slot="header" class="clearfix timeline-header">
                                <div class="header-left">
                                    <span class="meta-name">{{ item.memberName }}</span>
                                    <el-tag size="mini" effect="plain">{{ item.metadataType }}</el-tag>
                                    <el-tag size="mini" :type="getActionType(item.action)" effect="dark">{{ item.action
                                        }}</el-tag>
                                </div>
                                <div class="header-right">
                                    <el-tooltip content="点击跳转至部署包详情" placement="top" v-if="item.deploymentTitle">
                                        <el-link type="primary" :underline="false" icon="el-icon-connection"
                                            class="deployment-link" @click="handleGoToDeployment(item.deploymentId)">
                                            {{ item.deploymentTitle }}
                                        </el-link>
                                    </el-tooltip>
                                    <span v-else class="info-text">未知部署包</span>

                                    <el-divider direction="vertical"></el-divider>

                                    <span class="info-text"><i class="el-icon-user"></i> {{ item.createBy }}</span>

                                    <el-divider direction="vertical"></el-divider>

                                    <el-tag size="mini" effect="plain" type="info" class="history-type-tag">
                                        {{ getOperationTypeLabel(item.type) }}
                                    </el-tag>

                                    <el-button v-if="item.backupPath" type="text" icon="el-icon-files"
                                        style="margin-left: 10px;"
                                        @click="handlePreviewBackup(item.historyId)">备份包</el-button>
                                </div>
                            </div>

                            <div class="diff-section">
                                <div v-if="item.diffContent">
                                    <el-button size="mini" plain @click="$set(item, 'showDiff', !item.showDiff)">
                                        {{ item.showDiff ? '收起差异' : '查看代码变更 (Diff)' }}
                                    </el-button>

                                    <div v-if="item.showDiff" class="diff-editor-container">
                                        <monaco-editor :value="item.diffContent" language="diff" theme="vs-dark"
                                            height="400px" :options="{ readOnly: true, minimap: { enabled: false } }" />
                                    </div>
                                </div>
                                <div v-else class="no-diff-text">
                                    <i class="el-icon-info"></i> 本次变更无文本差异记录 (可能是新增文件、二进制文件或未检测到变动)
                                </div>
                            </div>
                        </el-card>

                    </el-timeline-item>
                </el-timeline>

                <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
                    :limit.sync="queryParams.pageSize" @pagination="getList" />
            </div>
        </el-card>

        <el-dialog title="历史备份包内容全览" :visible.sync="previewDialog.open" width="85%" append-to-body top="5vh">
            <div v-loading="previewDialog.loading" style="height: 650px;">
                <el-row :gutter="20" style="height: 100%;">
                    <el-col :span="6" style="height: 100%; display: flex; flex-direction: column;">
                        <div class="file-list-header">
                            <span>文件清单 ({{ previewDialog.files.length }})</span>
                            <el-button type="text" size="mini" icon="el-icon-download"
                                @click="handleDownloadBackup(currentPreviewHistoryId)">下载ZIP</el-button>
                        </div>

                        <div style="margin-bottom: 10px; padding: 0 2px;">
                            <el-input v-model="previewSearchQuery" placeholder="输入文件名进行搜索..."
                                prefix-icon="el-icon-search" size="small" clearable>
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
    </div>
</template>

<script>
import request from '@/utils/request';
import MonacoEditor from '@/components/MonacoEditor'; // 确保路径正确

export default {
    name: "MetadataAudit",
    dicts: ['sys_salesforce_metadata_type'],
    components: { MonacoEditor },
    data() {
        return {
            loading: false,
            list: [],
            total: 0,
            queryParams: {
                pageNum: 1,
                pageSize: 10,
                type: '',
                name: ''
            },
            previewOpen: false,
            previewFiles: [],
            currentPreviewHistoryId: null, // 暂存当前预览的 ID 用于下载
            previewDialog: {
                open: false,
                loading: false,
                files: [],
                fileContents: {},
                packageXml: '',
                currentFile: 'package.xml',
                currentContent: ''
            },
            previewSearchQuery: ''
        };
    },
    computed: {

        // 【新增】计算过滤后的文件列表
        filteredPreviewFiles() {
            if (!this.previewSearchQuery) {
                // 排除 package.xml，因为我们在模板里单独置顶写了，防止重复
                return this.previewDialog.files.filter(f => f !== 'package.xml');
            }
            const query = this.previewSearchQuery.toLowerCase();
            return this.previewDialog.files.filter(file =>
                file !== 'package.xml' && file.toLowerCase().includes(query)
            );
        }
    },
    methods: {
        handleQuery() {
            this.queryParams.pageNum = 1;
            this.getList();
        },
        getList() {
            if (!this.queryParams.type && !this.queryParams.name) {
                this.$modal.msgWarning("请至少输入一个搜索条件");
                return;
            }
            this.loading = true;
            request({
                url: '/salesforce/deployment/audit/list',
                method: 'get',
                params: this.queryParams
            }).then(res => {
                this.list = res.rows.map(item => ({ ...item, showDiff: false }));
                this.total = res.total;
                this.loading = false;
            }).catch(() => {
                this.loading = false;
            });
        },
        getStatusColor(status) {
            if (status === 'Succeeded') return '#67C23A';
            if (status === 'Failed') return '#F56C6C';
            return '#909399';
        },
        getActionType(action) {
            if (action === 'CREATE') return 'success';
            if (action === 'UPDATE') return 'warning';
            return 'info';
        },
        handlePreviewBackup(historyId) {
            this.previewSearchQuery = '';
            this.currentPreviewHistoryId = historyId; // 记录 ID
            this.previewDialog.open = true;
            this.previewDialog.loading = true;
            // 重置数据
            this.previewDialog.files = [];
            this.previewDialog.fileContents = {};
            this.previewDialog.packageXml = '';
            this.previewDialog.currentFile = 'package.xml';
            this.previewDialog.currentContent = '';

            request({
                url: `/salesforce/deployment/history/preview/${historyId}`,
                method: 'get'
            }).then(res => {
                const data = res.data;
                this.previewDialog.files = data.files || [];
                this.previewDialog.fileContents = data.fileContents || {};
                this.previewDialog.packageXml = data.packageXml || '';

                // 自动选中第一个文件
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

        /**
         * 【新增】选择文件逻辑
         */
        selectPreviewFile(fileName) {
            this.previewDialog.currentFile = fileName;
            // 特殊处理 package.xml，因为后端可能把它单独字段返回了，也可能在 fileContents 里
            if (fileName === 'package.xml' && this.previewDialog.packageXml && !this.previewDialog.fileContents[fileName]) {
                this.previewDialog.currentContent = this.previewDialog.packageXml;
                return;
            }
            const content = this.previewDialog.fileContents[fileName];
            this.previewDialog.currentContent = content || '(无法预览或文件为空)';
        },

        /**
         * 【新增】获取语言类型 (用于 Monaco 高亮)
         */
        getLanguage(fileName) {
            if (!fileName) return 'xml';
            if (fileName.endsWith('.cls') || fileName.endsWith('.trigger')) return 'java';
            if (fileName.endsWith('.js')) return 'javascript';
            if (fileName.endsWith('.css')) return 'css';
            if (fileName.endsWith('.json')) return 'json';
            return 'xml';
        },
        /**
     * 【新增/修复】下载备份文件
     * 对应后端 SfDeploymentHistoryController.downloadBackup
     */
        handleDownloadBackup(historyId) {
            if (!historyId) {
                this.$modal.msgError("未获取到历史记录ID，无法下载");
                return;
            }

            const fileName = `backup_history_${historyId}.zip`;
            this.$modal.msgSuccess("正在请求下载备份文件...");

            request({
                url: '/salesforce/deployment/history/download/' + historyId,
                method: 'post',
                responseType: 'blob', // 关键：必须设置响应类型为 blob
                timeout: 600000       // 设置较长的超时时间
            }).then(async (res) => {
                // 处理 Blob 响应
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
                    this.$modal.msgSuccess("下载成功");
                } else {
                    // 如果返回的是 JSON 错误信息（虽然设置了 blob，但后端报错时可能返回 json）
                    const text = await res.text();
                    const json = JSON.parse(text);
                    this.$modal.msgError(json.msg || "下载失败，文件可能已丢失");
                }
            }).catch(error => {
                console.error("Download error:", error);
                this.$modal.msgError("下载请求失败");
            });
        },
        /**
         * 【新增】跳转到部署包详情页
         */
        handleGoToDeployment(id) {
            if (!id) return;
            this.$router.push({
                path: "/salesforce/deploymentDetail",
                query: { id: id }
            });
        },

        /**
         * 【新增】美化操作类型的显示
         */
        getOperationTypeLabel(type) {
            const map = {
                'Deploy': '完整部署',
                'Validate': '仅验证',
                'Quick': '快速部署',
                'Rollback': '回滚'
            };
            return map[type] || type;
        }
    }
};
</script>

<style scoped>
.audit-search {
    border-bottom: 1px solid #ebeef5;
    margin-bottom: 20px;
}

.timeline-card {
    border-radius: 8px;
}

.timeline-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.header-left {
    display: flex;
    align-items: center;
    gap: 10px;
}

.meta-name {
    font-weight: bold;
    font-size: 15px;
}

.header-right {
    display: flex;
    align-items: center;
}

.info-text {
    font-size: 13px;
    color: #909399;
    margin: 0 10px;
}

.diff-section {
    margin-top: 10px;
}

.diff-editor-container {
    margin-top: 10px;
    border: 1px solid #dcdfe6;
}

.no-diff-text {
    font-size: 12px;
    color: #C0C4CC;
    font-style: italic;
    margin-top: 5px;
}

.file-list-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-bottom: 10px;
    border-bottom: 1px solid #ebeef5;
    margin-bottom: 10px;
    font-size: 14px;
    font-weight: 600;
    color: #606266;
}

.file-list-container {
    flex: 1;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    overflow-y: auto;
    background: #fff;
    /* height: calc(100% - 45px);  <-- 【删除】这行固定高度 */
    height: 0;
    /* 【新增】配合 flex:1 在列方向上滚动 */
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
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
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
</style>