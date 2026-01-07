<template>
    <el-dialog :title="title" :visible.sync="open" width="90%" top="3vh" append-to-body :close-on-click-modal="false"
        custom-class="wizard-dialog">
        <el-steps :active="activeStep" finish-status="success" simple style="margin-bottom: 20px">
            <el-step title="基础信息"></el-step>
            <el-step title="选择对象"></el-step>
            <el-step title="字段映射策略"></el-step>
        </el-steps>

        <div v-show="activeStep === 0" class="step-content">
            <el-form ref="form" :model="form" :rules="rules" label-width="100px"
                style="max-width: 800px; margin: 0 auto;">
                <el-form-item label="任务名称" prop="jobName">
                    <el-input v-model="form.jobName" placeholder="例如: 每日客户数据一致性检查" />
                </el-form-item>
                <el-row>
                    <el-col :span="12">
                        <el-form-item label="源环境" prop="sourceOrgId">
                            <el-select v-model="form.sourceOrgId" placeholder="选择Global Org" style="width:100%"
                                @change="handleOrgChange">
                                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name"
                                    :value="item.id" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="目标环境" prop="targetOrgId">
                            <el-select v-model="form.targetOrgId" placeholder="选择阿里云 Org" style="width:100%"
                                @change="handleOrgChange">
                                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name"
                                    :value="item.id" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                </el-row>
                <el-form-item label="备注" prop="remark">
                    <el-input type="textarea" v-model="form.remark" placeholder="请输入任务描述或备注信息" :rows="4" />
                </el-form-item>
            </el-form>
        </div>

        <div v-show="activeStep === 1" class="step-content flex-center">
            <div class="transfer-wrapper" v-loading="loadingObjects">
                <el-transfer v-model="selectedObjects" :data="allObjects" :titles="['可选对象', '已选对象']" filterable
                    filter-placeholder="输入对象名搜索" :button-texts="[' 移除 ', ' 添加 ']" @change="handleObjSelectionChange"
                    class="custom-transfer">
                    <span slot-scope="{ option }">
                        <el-tooltip :content="option.key" placement="top" :open-delay="1000">
                            <span>{{ option.label }}</span>
                        </el-tooltip>
                    </span>
                </el-transfer>
            </div>
        </div>

        <div v-show="activeStep === 2" class="step-content">
            <el-row :gutter="20" style="height: 100%;">
                <el-col :span="5" style="height: 100%;">
                    <div class="obj-list-card">
                        <div class="card-header">已选对象</div>
                        <el-menu :default-active="currentObjIndex" @select="handleObjMenuSelect" class="obj-menu">
                            <el-menu-item v-for="(objName, index) in selectedObjects" :key="objName"
                                :index="index.toString()">
                                <i class="el-icon-document"></i>
                                <span slot="title" :title="getObjDisplayName(objName)">
                                    {{ getObjDisplayName(objName) }}
                                </span>
                            </el-menu-item>
                        </el-menu>
                    </div>
                </el-col>

                <el-col :span="19" style="height: 100%;">
                    <div class="field-config-panel" v-if="currentConfig">
                        <div class="panel-header">
                            <span class="panel-title">{{ getObjDisplayName(currentConfig.objectName) }} - 字段策略</span>
                            <div class="header-actions">
                                <el-select v-model="filterType" placeholder="筛选字段类型" size="small" clearable
                                    style="width: 140px; margin-right: 10px">
                                    <el-option v-for="type in fieldTypes" :key="type" :label="type" :value="type" />
                                </el-select>
                                <el-input v-model="filterKeyword" placeholder="搜索字段..." size="small"
                                    prefix-icon="el-icon-search" style="width: 180px" />
                            </div>
                        </div>

                        <div class="key-config-bar">
                            <span class="label">主键策略:</span>
                            <el-input v-model="currentConfig.sourceKeyField" size="mini" placeholder="Source Key (Id)"
                                style="width: 180px">
                                <template slot="prepend">源</template>
                            </el-input>
                            <i class="el-icon-right" style="margin: 0 15px; color: #909399"></i>
                            <el-input v-model="currentConfig.targetKeyField" size="mini"
                                placeholder="Target Key (Source_Org_Id__c)" style="width: 240px">
                                <template slot="prepend">目标</template>
                            </el-input>
                        </div>

                        <el-table :data="filteredFields" height="calc(100% - 100px)" border size="small" stripe
                            v-loading="loadingFields" style="width: 100%">
                            <el-table-column prop="name" label="字段API名" min-width="180" show-overflow-tooltip />
                            <el-table-column prop="label" label="标签" min-width="150" show-overflow-tooltip />
                            <el-table-column prop="type" label="类型" width="100" align="center">
                                <template slot-scope="scope">
                                    <el-tag size="mini" :type="getFieldTypeTag(scope.row.type)">{{ scope.row.type
                                        }}</el-tag>
                                </template>
                            </el-table-column>

                            <el-table-column label="映射策略" min-width="250">
                                <template slot-scope="scope">
                                    <div v-if="isExcluded(scope.row.name)" class="status-excluded">
                                        <i class="el-icon-circle-close"></i> 已排除
                                    </div>
                                    <div v-else-if="hasMapping(scope.row.name)" class="status-mapped">
                                        <i class="el-icon-connection"></i>
                                        {{ getMappingSummary(scope.row.name) }}
                                    </div>
                                    <div v-else class="status-default">
                                        <span v-if="scope.row.type === 'reference'">自动 ({{ scope.row.relationshipName }}
                                            -> Key)</span>
                                        <span v-else>直接值比对</span>
                                    </div>
                                </template>
                            </el-table-column>

                            <el-table-column label="操作" width="160" align="center" fixed="right">
                                <template slot-scope="scope">
                                    <el-button v-if="scope.row.type === 'reference' && scope.row.referenceTo"
                                        type="text" icon="el-icon-setting" size="mini"
                                        @click="openMappingDialog(scope.row)">配置映射</el-button>

                                    <el-button :type="isExcluded(scope.row.name) ? 'text' : 'text'"
                                        :class="isExcluded(scope.row.name) ? 'btn-recover' : 'btn-exclude'" size="mini"
                                        @click="toggleExclude(scope.row.name)">
                                        {{ isExcluded(scope.row.name) ? '恢复' : '排除' }}
                                    </el-button>
                                </template>
                            </el-table-column>
                        </el-table>
                    </div>
                </el-col>
            </el-row>
        </div>

        <el-dialog title="关联字段映射配置" :visible.sync="mappingDialog.open" width="650px" append-to-body>
            <div v-loading="mappingDialog.loading">
                <el-alert type="info" :closable="false" show-icon style="margin-bottom: 20px">
                    <div slot="title">
                        <b>{{ mappingDialog.fieldName }}</b> (关联对象: <b>{{ mappingDialog.referenceTo }}</b>)
                    </div>
                    <div>请选择两端环境用于关联比对的唯一标识字段（例如：源环境用 Email，目标环境也用 Email）。</div>
                </el-alert>

                <el-form label-width="120px" size="small">
                    <el-form-item label="源环境字段">
                        <el-select v-model="mappingDialog.sourceField" filterable placeholder="选择源环境字段 (如 Id, Email)"
                            style="width: 100%">
                            <el-option v-for="f in mappingDialog.relFields" :key="f.name"
                                :label="f.name + ' (' + f.label + ')'" :value="f.name" />
                        </el-select>
                        <div class="tips">预览 SOQL: SELECT {{ mappingDialog.relationshipName }}.{{
                            mappingDialog.sourceField ||
                            '...' }} FROM ...</div>
                    </el-form-item>

                    <el-form-item label="目标环境字段">
                        <el-select v-model="mappingDialog.targetField" filterable
                            placeholder="选择目标环境字段 (如 Source_Org_Id__c)" style="width: 100%">
                            <el-option v-for="f in mappingDialog.relFields" :key="f.name"
                                :label="f.name + ' (' + f.label + ')'" :value="f.name" />
                        </el-select>
                        <div class="tips">预览 SOQL: SELECT {{ mappingDialog.relationshipName }}.{{
                            mappingDialog.targetField ||
                            '...' }} FROM ...</div>
                    </el-form-item>
                </el-form>
            </div>
            <div slot="footer" class="dialog-footer">
                <el-button @click="mappingDialog.open = false">取 消</el-button>
                <el-button type="primary" @click="saveMapping">确 定</el-button>
            </div>
        </el-dialog>

        <div slot="footer" class="drawer-footer">
            <el-button @click="open = false">取消</el-button>
            <el-button v-if="activeStep > 0" @click="prevStep">上一步</el-button>
            <el-button v-if="activeStep < 2" type="primary" @click="nextStep">下一步</el-button>
            <el-button v-if="activeStep === 2" type="primary" :loading="submitting" @click="submit">完成创建</el-button>
        </div>
    </el-dialog>
</template>

<script>
import { listOrg } from "@/api/salesforce/org";
import { addJob, updateJob, batchSaveConfigs, getJob, listConfigs } from "@/api/salesforce/reconcile";
import { listSObjects, getSObjectFields } from "@/api/salesforce/describe";

export default {
    name: "JobWizard",
    props: {
        orgOptions: {
            type: Array,
            default: () => []
        }
    },
    data() {
        return {
            title: "创建比对任务",
            open: false,
            activeStep: 0,
            submitting: false,

            // Step 1 Data
            form: {
                id: undefined,
                jobName: '',
                sourceOrgId: undefined,
                targetOrgId: undefined,
                remark: ''
            },
            rules: {
                jobName: [{ required: true, message: "请输入任务名称", trigger: "blur" }],
                sourceOrgId: [{ required: true, message: "请选择源环境", trigger: "change" }],
                targetOrgId: [{ required: true, message: "请选择目标环境", trigger: "change" }]
            },

            // Step 2 Data
            loadingObjects: false,
            allObjects: [],
            selectedObjects: [],

            // Step 3 Data
            currentObjIndex: "0",
            configList: [],
            currentFields: [],
            loadingFields: false,

            // Filters
            filterType: '',
            filterKeyword: '',
            fieldTypes: [],

            // Mapping Dialog
            mappingDialog: {
                open: false,
                loading: false,
                fieldName: '',
                relationshipName: '',
                referenceTo: '',
                sourceField: '',
                targetField: '',
                relFields: []
            }
        };
    },
    computed: {
        currentConfig() {
            if (this.configList.length === 0) return null;
            const objName = this.selectedObjects[parseInt(this.currentObjIndex)];
            return this.configList.find(c => c.objectName === objName);
        },
        filteredFields() {
            if (!this.currentFields) return [];
            return this.currentFields.filter(f => {
                const matchKeyword = !this.filterKeyword || f.name.toLowerCase().includes(this.filterKeyword.toLowerCase());
                const matchType = !this.filterType || f.type === this.filterType;
                return matchKeyword && matchType;
            });
        }
    },
    methods: {
        openWizard(jobId) {
            this.reset();
            this.open = true;
            // 兜底获取Org列表
            if (this.orgOptions.length === 0) {
                listOrg().then(res => this.$emit('update:orgOptions', res.rows));
            }

            if (jobId) {
                this.title = "编辑比对任务";
                getJob(jobId).then(res => {
                    this.form = res.data;
                    listConfigs(jobId).then(cRes => {
                        this.configList = cRes.data;
                        this.selectedObjects = this.configList.map(c => c.objectName);
                    });
                });
            } else {
                this.title = "创建比对任务";
            }
        },
        reset() {
            this.activeStep = 0;
            this.form = { id: undefined, jobName: '', sourceOrgId: undefined, targetOrgId: undefined, remark: '' };
            this.selectedObjects = [];
            this.configList = [];
            this.currentObjIndex = "0";
            this.allObjects = [];
        },
        handleOrgChange() {
            this.selectedObjects = [];
            this.configList = [];
            this.allObjects = [];
        },

        // --- Step Navigation ---
        nextStep() {
            if (this.activeStep === 0) {
                this.$refs.form.validate(valid => {
                    if (valid) {
                        this.activeStep = 1;
                        this.loadAllObjects();
                    }
                });
            } else if (this.activeStep === 1) {
                if (this.selectedObjects.length === 0) {
                    this.$modal.msgError("请至少选择一个对象");
                    return;
                }
                this.activeStep = 2;
                this.initConfigs();
                this.loadFieldsForCurrentObj();
            }
        },
        prevStep() {
            this.activeStep--;
        },

        // --- Step 2: Object Selection ---
        loadAllObjects() {
            if (this.allObjects.length > 0) return;
            this.loadingObjects = true;

            listSObjects(this.form.sourceOrgId).then(res => {
                // 按对象名(Key)进行 A-Z 排序
                const sortedList = res.data.map(item => ({
                    key: item.name,
                    label: item.label
                })).sort((a, b) => a.key.localeCompare(b.key));

                this.allObjects = sortedList;
                this.loadingObjects = false;
            }).catch(() => {
                this.loadingObjects = false;
            });
        },
        handleObjSelectionChange(val) {
        },

        // --- Step 3: Config & Mapping ---
        initConfigs() {
            // 1. 为新选中的对象添加默认配置
            this.selectedObjects.forEach(objName => {
                if (!this.configList.find(c => c.objectName === objName)) {
                    this.configList.push({
                        objectName: objName,
                        sourceKeyField: 'Id',
                        targetKeyField: 'Source_Org_Id__c',
                        excludedFields: '',
                        mappingConfig: '{}'
                    });
                }
            });
            // 2. 移除已取消选择的对象
            this.configList = this.configList.filter(c => this.selectedObjects.includes(c.objectName));
        },
        handleObjMenuSelect(index) {
            this.currentObjIndex = index;
            this.loadFieldsForCurrentObj();
        },
        loadFieldsForCurrentObj() {
            const config = this.currentConfig;
            if (!config) return;

            this.loadingFields = true;
            this.currentFields = [];

            getSObjectFields(this.form.sourceOrgId, config.objectName).then(res => {
                this.currentFields = res.data;
                const types = new Set(this.currentFields.map(f => f.type));
                this.fieldTypes = Array.from(types).sort();
                this.loadingFields = false;
            }).catch(e => {
                this.loadingFields = false;
                this.$modal.msgError("获取字段失败: " + e.message);
            });
        },

        // --- Helper: 获取对象显示名称 ---
        // 【核心新增】
        getObjDisplayName(key) {
            // 尝试从 allObjects 中查找
            const obj = this.allObjects.find(item => item.key === key);
            if (obj) {
                return `${obj.label} - ${obj.key}`;
            }
            // 如果 allObjects 为空（例如直接进入Step3编辑模式未加载Step2），则直接显示 Key
            // 或者可以考虑在 init 的时候预加载 allObjects，但为了性能通常不这么做
            return key;
        },

        // --- Mapping Logic ---
        getFieldTypeTag(type) {
            if (type === 'reference') return 'warning';
            if (type === 'id') return 'danger';
            if (type === 'boolean') return 'success';
            return 'info';
        },
        isExcluded(fieldName) {
            if (!this.currentConfig) return false;
            const excluded = this.currentConfig.excludedFields ? this.currentConfig.excludedFields.split(',') : [];
            return excluded.includes(fieldName);
        },
        toggleExclude(fieldName) {
            const config = this.currentConfig;
            let excluded = config.excludedFields ? config.excludedFields.split(',').filter(f => f) : [];
            if (excluded.includes(fieldName)) {
                excluded = excluded.filter(f => f !== fieldName);
            } else {
                excluded.push(fieldName);
            }
            config.excludedFields = excluded.join(',');
        },

        // Mapping Config Dialog
        hasMapping(fieldName) {
            if (!this.currentConfig) return false;
            const map = JSON.parse(this.currentConfig.mappingConfig || '{}');
            return !!map[fieldName];
        },
        getMappingSummary(fieldName) {
            const map = JSON.parse(this.currentConfig.mappingConfig || '{}');
            const item = map[fieldName];
            if (!item) return '';
            return `${item.sourcePath.split('.')[1]} -> ${item.targetPath.split('.')[1]}`;
        },
        openMappingDialog(row) {
            this.mappingDialog.fieldName = row.name;
            this.mappingDialog.relationshipName = row.relationshipName;
            this.mappingDialog.referenceTo = (row.referenceTo && row.referenceTo.length > 0) ? row.referenceTo[0] : '';
            this.mappingDialog.sourceField = '';
            this.mappingDialog.targetField = '';
            this.mappingDialog.loading = true;
            this.mappingDialog.open = true;

            const map = JSON.parse(this.currentConfig.mappingConfig || '{}');
            if (map[row.name]) {
                const conf = map[row.name];
                this.mappingDialog.sourceField = conf.sourcePath.split('.')[1];
                this.mappingDialog.targetField = conf.targetPath.split('.')[1];
            } else {
                this.mappingDialog.sourceField = 'Id';
                this.mappingDialog.targetField = this.currentConfig.targetKeyField;
            }

            if (this.mappingDialog.referenceTo) {
                this.fetchRelatedObjectFields(this.mappingDialog.referenceTo);
            } else {
                this.mappingDialog.loading = false;
                this.$modal.msgWarning("该引用字段未定义关联对象，无法配置");
            }
        },
        fetchRelatedObjectFields(objectName) {
            getSObjectFields(this.form.sourceOrgId, objectName).then(res => {
                this.mappingDialog.relFields = res.data;
                this.mappingDialog.loading = false;
            }).catch(() => {
                this.mappingDialog.loading = false;
            });
        },
        saveMapping() {
            const { fieldName, relationshipName, sourceField, targetField } = this.mappingDialog;
            const map = JSON.parse(this.currentConfig.mappingConfig || '{}');
            map[fieldName] = {
                type: 'REFERENCE',
                sourcePath: `${relationshipName}.${sourceField}`,
                targetPath: `${relationshipName}.${targetField}`
            };
            this.currentConfig.mappingConfig = JSON.stringify(map);
            this.mappingDialog.open = false;
            this.$forceUpdate();
        },

        // --- Final Submit ---
        submit() {
            this.submitting = true;
            const jobFunc = this.form.id ? updateJob : addJob;
            jobFunc(this.form).then(res => {
                const jobId = this.form.id || res.data;
                batchSaveConfigs(jobId, this.configList).then(() => {
                    this.$modal.msgSuccess("保存成功");
                    this.open = false;
                    this.submitting = false;
                    this.$emit("success");
                });
            }).catch(() => {
                this.submitting = false;
            });
        }
    }
};
</script>

<style scoped>
.step-content {
    padding: 20px;
    height: 65vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.flex-center {
    justify-content: center;
    align-items: center;
}

.transfer-wrapper {
    width: 100%;
    display: flex;
    justify-content: center;
}

::v-deep .custom-transfer .el-transfer-panel {
    width: 380px;
    height: 500px;
}

::v-deep .custom-transfer .el-transfer-panel__body {
    height: 456px;
}

::v-deep .custom-transfer .el-transfer-panel__list.is-filterable {
    height: 400px;
}

.obj-list-card {
    height: 100%;
    border: 1px solid #EBEEF5;
    border-radius: 4px;
    display: flex;
    flex-direction: column;
}

.card-header {
    padding: 10px 15px;
    background: #f5f7fa;
    border-bottom: 1px solid #EBEEF5;
    font-weight: bold;
}

.obj-menu {
    border-right: none;
    overflow-y: auto;
    flex: 1;
}

.field-config-panel {
    height: 100%;
    display: flex;
    flex-direction: column;
    border: 1px solid #EBEEF5;
    border-radius: 4px;
    padding: 10px;
}

.panel-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;
}

.panel-title {
    font-size: 16px;
    font-weight: bold;
    color: #303133;
}

.key-config-bar {
    background: #fdf6ec;
    padding: 10px;
    border-radius: 4px;
    margin-bottom: 10px;
    display: flex;
    align-items: center;
}

.key-config-bar .label {
    font-size: 13px;
    color: #e6a23c;
    margin-right: 15px;
    font-weight: bold;
}

.status-excluded {
    color: #909399;
    font-size: 12px;
}

.status-mapped {
    color: #67C23A;
    font-weight: bold;
    font-size: 12px;
}

.status-default {
    color: #C0C4CC;
    font-size: 12px;
}

.btn-exclude {
    color: #F56C6C;
}

.btn-recover {
    color: #67C23A;
}

.tips {
    font-size: 12px;
    color: #909399;
    margin-top: 5px;
    font-family: monospace;
    background: #f4f4f5;
    padding: 2px 5px;
    border-radius: 3px;
}
</style>