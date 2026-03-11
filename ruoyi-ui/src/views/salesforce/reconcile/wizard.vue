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
                                <el-option v-for="item in orgOptionsList" :key="item.id" :label="item.name"
                                    :value="item.id" />
                            </el-select>
                        </el-form-item>
                    </el-col>
                    <el-col :span="12">
                        <el-form-item label="目标环境" prop="targetOrgId">
                            <el-select v-model="form.targetOrgId" placeholder="选择阿里云 Org" style="width:100%"
                                @change="handleOrgChange">
                                <el-option v-for="item in orgOptionsList" :key="item.id" :label="item.name"
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
                    <field-mapping-panel v-if="currentConfig" :config="currentConfig" :source-org-id="form.sourceOrgId"
                        :object-label="getObjDisplayName(currentConfig.objectName)" />
                </el-col>
            </el-row>
        </div>

        <div slot="footer" class="drawer-footer">
            <el-button @click="open = false">取消</el-button>
            <el-button v-if="activeStep > 0" @click="prevStep">上一步</el-button>
            <el-button v-if="activeStep < 2" type="primary" @click="nextStep">下一步</el-button>
            <el-button v-if="activeStep === 2" type="primary" :loading="submitting" @click="submit">完成配置</el-button>
        </div>
    </el-dialog>
</template>

<script>
// 基础依赖
import { listOrg } from "@/api/salesforce/org";
import { addJob, updateJob, getJob } from "@/api/salesforce/dataJob";
import { batchSaveConfigs, listConfigs } from "@/api/salesforce/dataObjConfig";
import { listSObjects } from "@/api/salesforce/describe";

// 【核心引入】引入独立封装的字段映射面板组件
import FieldMappingPanel from "./components/FieldMappingPanel";

export default {
    name: "JobWizard",
    components: { FieldMappingPanel }, // 注册组件
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

            // 内部维护的 Org 列表 (如果 prop 为空)
            orgOptionsList: [],

            // --- Step 1 Data ---
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

            // --- Step 2 Data ---
            loadingObjects: false,
            allObjects: [],
            selectedObjects: [],

            // --- Step 3 Data ---
            currentObjIndex: "0",
            configList: [],
            // 注意：关于字段过滤、搜索、弹窗配置的 data 全都移走了！
        };
    },
    watch: {
        orgOptions: {
            handler(val) {
                if (val && val.length > 0) this.orgOptionsList = val;
            },
            immediate: true
        }
    },
    computed: {
        // 当前选中的对象配置，直接传给子组件
        currentConfig() {
            if (this.configList.length === 0) return null;
            const objName = this.selectedObjects[parseInt(this.currentObjIndex)];
            return this.configList.find(c => c.objectName === objName);
        }
    },
    methods: {
        init(jobId) {
            this.reset();
            this.open = true;

            // 兜底获取Org列表
            if (this.orgOptionsList.length === 0) {
                listOrg().then(res => this.orgOptionsList = res.rows);
            }

            if (jobId) {
                this.title = "编辑比对任务";
                getJob(jobId).then(res => {
                    this.form = res.data;
                    listConfigs(jobId).then(cRes => {
                        this.configList = cRes.data;
                        this.selectedObjects = this.configList.map(c => c.objectName);
                        if (this.selectedObjects.length > 0) {
                            this.loadAllObjects();
                        }
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
                // 注意：这里不再调用 loadFieldsForCurrentObj()，因为子组件被挂载后会自动拉取
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
            // Placeholder for transfer component change event if needed
        },

        // --- Step 3: Config Setup ---
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
            // 切换对象时只需改 Index，子组件计算属性更新后会自动重新拉取对应字段
        },

        // --- Helper ---
        getObjDisplayName(key) {
            const obj = this.allObjects.find(item => item.key === key);
            if (obj) {
                return `${obj.label} - ${obj.key}`;
            }
            return key;
        },

        // --- Final Submit ---
        submit() {
            this.submitting = true;
            const jobFunc = this.form.id ? updateJob : addJob;
            jobFunc(this.form).then(res => {
                const jobId = this.form.id || res.data;
                batchSaveConfigs(jobId, this.configList).then(() => {
                    this.$modal.msgSuccess("配置已保存");
                    this.open = false;
                    this.submitting = false;
                    this.$emit("ok");
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
</style>