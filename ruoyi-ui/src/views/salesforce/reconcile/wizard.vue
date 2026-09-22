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
                <el-form-item label="数据截断时间" prop="dataEndTime">
                    <el-date-picker v-model="form.dataEndTime" type="datetime" placeholder="留空则默认拉取全量源数据"
                        value-format="yyyy-MM-dd HH:mm:ss" style="width: 100%">
                    </el-date-picker>
                    <div style="font-size: 12px; color: #E6A23C; line-height: 1.4; margin-top: 4px;">
                        <i class="el-icon-info"></i> 配置后，源环境仅提取 CreatedDate ≤ 该时间的数据。（注：若选定的 Salesforce 对象底层无
                        CreatedDate
                        字段如系统元数据，该限制将自动豁免并执行全量比对）。
                    </div>
                </el-form-item>
                <el-form-item label="默认排除字段" prop="globalExcludedFields">
                    <el-input type="textarea" v-model="globalExcludedFields" placeholder="输入以逗号分隔的API名" :rows="2" />
                    <div style="font-size: 12px; color: #909399; line-height: 1.4; margin-top: 4px;">
                        <i class="el-icon-magic-stick" style="color: #E6A23C"></i>
                        <b>智能辅助</b>：新增比对对象时，系统会自动将这些字段加入排除列表。系统已记住您的配置习惯。（PS:该默认排除的字段不会存入数据库）
                    </div>
                </el-form-item>
                <el-form-item label="备注" prop="remark">
                    <el-input type="textarea" v-model="form.remark" placeholder="请输入任务描述或备注信息" :rows="4" />
                </el-form-item>
            </el-form>
        </div>

        <div v-show="activeStep === 1" class="step-content flex-center">
            <div class="transfer-wrapper" v-loading="loadingObjects">
                <el-transfer v-model="selectedObjects" :data="allObjects" :titles="['可选对象', '已选对象']" filterable
                    :filter-method="filterMethod" filter-placeholder="输入对象名或对象API搜索（支持空格隔开多个精确搜索）" :button-texts="[' 移除 ', ' 添加 ']"
                    @change="handleObjSelectionChange" class="custom-transfer">
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

            <el-button v-if="activeStep === 0" type="success" plain icon="el-icon-monitor" @click="handleSaveAndExit"
                :loading="submitting" style="margin-right: auto; float: left;">保存并前往控制台</el-button>
            <el-button v-if="activeStep === 0" type="primary" @click="nextStep" :loading="submitting">保存并继续下一步 <i
                    class="el-icon-arrow-right"></i></el-button>

            <el-button v-if="activeStep === 1" type="success" plain icon="el-icon-check" @click="handleQuickSave"
                :loading="submitting" style="margin-right: auto; float: left;">保存并稍后配置</el-button>

            <el-button @click="open = false">取 消</el-button>

            <el-button v-if="activeStep > startStep" @click="prevStep">上一步</el-button>

            <el-button v-if="activeStep === 1" type="primary" @click="nextStep">下一步 (精细化字段映射) <i
                    class="el-icon-arrow-right"></i></el-button>

            <el-button v-if="activeStep === 2" type="primary" :loading="submitting" @click="submit">完成所有配置</el-button>
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
const DEFAULT_EXCLUDE = 'Id,IsDeleted,CreatedById,CreatedDate,LastModifiedById,LastModifiedDate,SystemModstamp,ConnectionReceivedId,ConnectionSentId,LastActivityDate,LastReferencedDate,LastViewedDate';
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
            startStep: 0,
            globalExcludedFields: localStorage.getItem('sf_reconcile_default_exclude') || DEFAULT_EXCLUDE,
        };
    },
    watch: {
        orgOptions: {
            handler(val) {
                if (val && val.length > 0) this.orgOptionsList = val;
            },
            immediate: true
        },
        globalExcludedFields(val) {
            localStorage.setItem('sf_reconcile_default_exclude', val);
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
        /**
         * 穿梭框高阶搜索过滤逻辑 (支持：单次输入模糊匹配，Excel批量粘贴精确匹配)
         */
        filterMethod(query, item) {
            // 1. 输入为空时，展示全部
            if (!query) return true;

            // 2. 智能分词：按照 空格、换行符(\n)、制表符(\t)、以及中英文字符的逗号和分号进行拆分
            const keywords = query.toLowerCase()
                .split(/[\s,;，；\n\t]+/)
                .filter(k => k.trim() !== '');

            // 3. 如果切分后没有有效关键词，默认展示全部
            if (keywords.length === 0) return true;

            // 获取当前待选对象的 label(中文名) 和 key(API名)，并去除前后首尾空格
            const label = (item.label || '').toLowerCase().trim();
            const key = (item.key || '').toLowerCase().trim();

            // ==========================================
            // 4. 【核心增强】：双模智能探针，识别当前是单搜还是批量粘贴
            // 触发批量模式条件：拆分出多个有效词，或者原始输入包含特定的批量分隔符(如换行、逗号)
            // ==========================================
            const isBatchMode = keywords.length > 1 || /[\n\t,;，；]/.test(query);

            if (isBatchMode) {
                // 🔴 批量搜索模式 -> 执行【精确匹配】 (Exact Match)
                // 必须完全等于 API名 或 Label 名才放行
                return keywords.some(keyword => label === keyword || key === keyword);
            } else {
                // 🟢 单个搜索模式 -> 执行【模糊匹配】 (Fuzzy Match)
                // 只要包含用户输入的内容就放行
                const keyword = keywords[0];
                return label.includes(keyword) || key.includes(keyword);
            }
        },
        init(jobId, startStep = 0) {
            this.reset();
            this.startStep = startStep;
            this.activeStep = startStep;
            this.open = true;

            if (this.orgOptionsList.length === 0) {
                listOrg().then(res => this.orgOptionsList = res.rows);
            }

            if (jobId) {
                this.title = startStep === 1 ? "添加/修改比对对象" : "编辑比对任务";
                getJob(jobId).then(res => {
                    this.form = res.data;
                    listConfigs(jobId).then(cRes => {
                        this.configList = cRes.data || [];
                        this.selectedObjects = this.configList.map(c => c.objectName);
                        // 如果直接跳到选对象页面，或者已经有选中的对象，就拉取字典库
                        if (this.activeStep === 1 || this.selectedObjects.length > 0) {
                            this.loadAllObjects();
                        }
                    });
                });
            } else {
                this.title = "创建比对任务";
            }
        },
        handleSaveAndExit() {
            this.$refs["form"].validate(valid => {
                if (valid) {
                    this.submitting = true;
                    const jobFunc = this.form.id ? updateJob : addJob;
                    jobFunc(this.form).then(res => {
                        this.submitting = false;
                        const newId = this.form.id || res.data;
                        this.$modal.msgSuccess("基础信息保存成功");
                        this.open = false;
                        // 抛出新 ID，触发 index.vue 跳转到控制台
                        this.$emit("ok", newId);
                    }).catch(() => {
                        this.submitting = false;
                    });
                }
            });
        },
        handleQuickSave() {
            if (this.selectedObjects.length === 0) {
                this.$modal.msgError("请至少选择一个对象");
                return;
            }
            // 自动补齐所选对象的默认配置策略
            this.initConfigs();
            // 直接触发统筹保存
            this.submit();
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
                // 【核心突破】：如果是从第0步点下一步，必须先落盘基础信息生成 JobId，否则后面没法挂载对象！
                this.$refs["form"].validate(valid => {
                    if (valid) {
                        this.submitting = true;
                        const jobFunc = this.form.id ? updateJob : addJob;
                        jobFunc(this.form).then(res => {
                            this.submitting = false;
                            // 如果是新建，必须把后端返回的 ID 赋值给表单，这样后续配置才知道归属哪个 Job
                            if (!this.form.id) {
                                this.form.id = res.data;
                            }
                            this.activeStep++;
                            // 进入选对象页面时，加载元数据字典
                            this.loadAllObjects();
                        }).catch(() => {
                            this.submitting = false;
                        });
                    }
                });
            }
            else if (this.activeStep === 1) {
                if (this.selectedObjects.length === 0) {
                    this.$modal.msgError("请至少选择一个对象");
                    return;
                }
                this.initConfigs();
                this.activeStep++;
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
                        targetKeyField: 'old_sfdc_id__c',
                        excludedFields: this.globalExcludedFields,
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