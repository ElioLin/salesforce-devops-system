<template>
    <div class="field-config-panel" style="height: 100%; display: flex; flex-direction: column;">
        <div class="panel-header">
            <span class="panel-title">{{ objectLabel }} - 字段策略</span>
            <div class="header-actions" style="display: flex; align-items: center; flex-wrap: wrap; gap: 10px;">
                <el-button type="warning" plain size="small" icon="el-icon-magic-stick" @click="autoMapReferences">
                    一键映射
                </el-button>

                <el-select v-model="filterCategory" placeholder="字段来源" size="small" clearable style="width: 110px">
                    <el-option label="标准字段" value="standard" />
                    <el-option label="自定义字段" value="custom" />
                </el-select>

                <el-select v-model="filterStatus" placeholder="配置状态" size="small" clearable style="width: 110px">
                    <el-option label="已排除" value="excluded" />
                    <el-option label="未排除" value="included" />
                </el-select>

                <el-select v-model="filterType" placeholder="字段类型" size="small" clearable style="width: 120px">
                    <el-option label="formula" value="formula" style="color: #E6A23C; font-weight: bold;" />
                    <el-option v-for="type in fieldTypes" :key="type" :label="type" :value="type" />
                </el-select>

                <el-input v-model="filterKeyword" placeholder="搜索字段..." size="small" prefix-icon="el-icon-search"
                    clearable style="width: 180px" />
            </div>
        </div>

        <div class="key-config-bar">
            <span class="label">主键策略:</span>
            <el-input v-model="config.sourceKeyField" size="mini" placeholder="Source Key (Id)" style="width: 180px">
                <template slot="prepend">源</template>
            </el-input>
            <i class="el-icon-right" style="margin: 0 15px; color: #909399"></i>
            <el-input v-model="config.targetKeyField" size="mini" placeholder="Target Key (old_sfdc_id__c)"
                style="width: 240px">
                <template slot="prepend">目标</template>
            </el-input>
        </div>

        <el-table :data="filteredFields" height="calc(100vh - 250px)" border size="small" stripe
            v-loading="loadingFields" style="width: 100%; flex: 1;">
            <el-table-column prop="name" label="字段API名" min-width="180" show-overflow-tooltip>
                <template slot-scope="scope">
                    <i v-if="scope.row.custom" class="el-icon-setting" style="color: #409EFF; margin-right: 4px;"
                        title="自定义字段"></i>
                    <i v-else class="el-icon-box" style="color: #909399; margin-right: 4px;" title="标准字段"></i>
                    {{ scope.row.name }}
                </template>
            </el-table-column>

            <el-table-column prop="label" label="标签" min-width="150" show-overflow-tooltip />

            <el-table-column prop="type" label="类型" width="120" align="center">
                <template slot-scope="scope">
                    <el-tooltip v-if="scope.row.calculated" content="该字段为公式字段" placement="top">
                        <el-tag size="mini" type="warning" effect="dark">
                            <i class="el-icon-s-operation"></i> formula({{ scope.row.type }})
                        </el-tag>
                    </el-tooltip>
                    <el-tag v-else size="mini" :type="getFieldTypeTag(scope.row.type)">{{ scope.row.type }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column min-width="250">
                <template slot="header" slot-scope="scope">
                    <div style="display: flex; align-items: center; justify-content: space-between;">
                        <span>底层公式逻辑</span>
                        <el-tooltip content="开启后公式将自动换行完整显示，关闭则单行截断" placement="top">
                            <el-switch v-model="formulaWrap" size="mini" active-color="#13ce66"
                                inactive-color="#dcdfe6" />
                        </el-tooltip>
                    </div>
                </template>
                <template slot-scope="scope">
                    <div v-if="scope.row.calculated" class="formula-container" :class="{ 'is-wrapped': formulaWrap }">
                        <div class="formula-text">{{ scope.row.calculatedFormula || '隐含公式' }}</div>
                        <el-button type="text" icon="el-icon-copy-document" size="mini" class="copy-btn"
                            @click="copyText(scope.row.calculatedFormula)" title="复制完整公式">
                        </el-button>
                    </div>
                    <span v-else style="color: #C0C4CC;">-</span>
                </template>
            </el-table-column>

            <el-table-column label="映射策略" min-width="200">
                <template slot-scope="scope">
                    <div v-if="isExcluded(scope.row.name)" class="status-excluded">
                        <i class="el-icon-circle-close"></i> 已排除
                    </div>
                    <div v-else-if="hasMapping(scope.row.name)" class="status-mapped">
                        <i class="el-icon-connection"></i> {{ getMappingSummary(scope.row.name) }}
                    </div>
                    <div v-else class="status-default">
                        <span v-if="scope.row.type === 'reference'">自动 ({{ scope.row.relationshipName }} -> Key)</span>
                        <span v-else>直接值比对</span>
                    </div>
                </template>
            </el-table-column>

            <el-table-column label="操作" width="160" align="center" fixed="right">
                <template slot-scope="scope">
                    <el-button v-if="scope.row.type === 'reference' && scope.row.referenceTo" type="text"
                        icon="el-icon-setting" size="mini" @click="openMappingDialog(scope.row)">配置映射</el-button>
                    <el-button type="text" :class="isExcluded(scope.row.name) ? 'btn-recover' : 'btn-exclude'"
                        size="mini" @click="toggleExclude(scope.row.name)">
                        {{ isExcluded(scope.row.name) ? '恢复' : '排除' }}
                    </el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-dialog title="关联字段映射配置" :visible.sync="mappingDialog.open" width="650px" append-to-body>
            <div v-loading="mappingDialog.loading">
                <el-alert type="info" :closable="false" show-icon style="margin-bottom: 20px">
                    <div slot="title"><b>{{ mappingDialog.fieldName }}</b> (关联对象: <b>{{ mappingDialog.referenceTo
                    }}</b>)</div>
                    <div>请选择两端环境用于关联比对的唯一标识字段。</div>
                </el-alert>
                <el-form label-width="120px" size="small">
                    <el-form-item label="源环境字段">
                        <el-select v-model="mappingDialog.sourceField" filterable placeholder="选择源环境字段"
                            style="width: 100%">
                            <el-option v-for="f in mappingDialog.relFields" :key="f.name"
                                :label="f.name + ' (' + f.label + ')'" :value="f.name" />
                        </el-select>
                    </el-form-item>
                    <el-form-item label="目标环境字段">
                        <el-select v-model="mappingDialog.targetField" filterable placeholder="选择目标环境字段"
                            style="width: 100%">
                            <el-option v-for="f in mappingDialog.relFields" :key="f.name"
                                :label="f.name + ' (' + f.label + ')'" :value="f.name" />
                        </el-select>
                    </el-form-item>
                </el-form>
            </div>
            <div slot="footer" class="dialog-footer">
                <el-button @click="mappingDialog.open = false">取 消</el-button>
                <el-button type="primary" @click="saveMapping">确 定</el-button>
            </div>
        </el-dialog>
    </div>
</template>

<script>
import { getSObjectFields } from "@/api/salesforce/describe";

export default {
    name: "FieldMappingPanel",
    props: {
        config: { type: Object, required: true },
        sourceOrgId: { type: [Number, String], required: true },
        objectLabel: { type: String, default: '未命名对象' }
    },
    data() {
        return {
            currentFields: [],
            loadingFields: false,
            filterType: '',
            filterKeyword: '',
            filterCategory: '',
            filterStatus: '',
            fieldTypes: [],
            formulaWrap: false,
            mappingDialog: { open: false, loading: false, fieldName: '', relationshipName: '', referenceTo: '', sourceField: '', targetField: '', relFields: [] }
        };
    },
    computed: {
        filteredFields() {
            if (!this.currentFields) return [];
            return this.currentFields.filter(f => {
                // 1. 关键字匹配
                const keyword = this.filterKeyword ? this.filterKeyword.toLowerCase() : '';
                const matchKeyword = !keyword ||
                    (f.name && f.name.toLowerCase().includes(keyword)) ||
                    (f.label && f.label.toLowerCase().includes(keyword));

                // 2. 字段类型匹配 (兼容人工注入的 'formula' 类型过滤)
                let matchType = true;
                if (this.filterType) {
                    if (this.filterType === 'formula') {
                        matchType = f.calculated === true; // 如果选了公式，只展示 calculated 的
                    } else {
                        matchType = f.type === this.filterType;
                    }
                }

                // 3. 【新增】字段类别匹配 (标准/自定义)
                let matchCategory = true;
                if (this.filterCategory) {
                    if (this.filterCategory === 'custom') matchCategory = f.custom === true;
                    if (this.filterCategory === 'standard') matchCategory = f.custom === false;
                }

                // 4. 【新增】配置状态匹配 (排除/映射/直连)
                let matchStatus = true;
                if (this.filterStatus) {
                    const isExc = this.isExcluded(f.name);
                    if (this.filterStatus === 'excluded') matchStatus = isExc;
                    if (this.filterStatus === 'included') matchStatus = !isExc;
                }

                return matchKeyword && matchType && matchCategory && matchStatus;
            });
        }
    },
    watch: {
        // 当传入的 config 发生对象切换时，重新加载字段
        'config.objectName': {
            handler(newVal) {
                if (newVal) this.loadFields();
            },
            immediate: true
        }
    },
    methods: {
        loadFields() {
            if (!this.config || !this.config.objectName) return;
            this.loadingFields = true;
            getSObjectFields(this.sourceOrgId, this.config.objectName).then(res => {
                this.currentFields = res.data;
                const types = new Set(this.currentFields.map(f => f.type));
                this.fieldTypes = Array.from(types).sort();
                this.loadingFields = false;
            }).catch(e => {
                this.loadingFields = false;
                this.$modal.msgError("获取字段失败: " + e.message);
            });
        },
        copyText(text) {
            if (!text) return;
            const input = document.createElement('textarea');
            input.value = text;
            // 使其不在屏幕上显示
            input.style.position = 'fixed';
            input.style.opacity = '0';
            document.body.appendChild(input);
            input.select();
            try {
                document.execCommand('copy');
                this.$message.success('公式已复制到剪贴板！');
            } catch (e) {
                this.$message.error('复制失败，请手动选择复制。');
            } finally {
                document.body.removeChild(input);
            }
        },
        getFieldTypeTag(type) {
            if (type === 'reference') return 'warning';
            if (type === 'id') return 'danger';
            if (type === 'boolean') return 'success';
            return 'info';
        },
        isExcluded(fieldName) {
            const excluded = this.config.excludedFields ? this.config.excludedFields.split(',') : [];
            return excluded.includes(fieldName);
        },
        toggleExclude(fieldName) {
            let excluded = this.config.excludedFields ? this.config.excludedFields.split(',').filter(f => f) : [];
            if (excluded.includes(fieldName)) excluded = excluded.filter(f => f !== fieldName);
            else excluded.push(fieldName);
            this.$set(this.config, 'excludedFields', excluded.join(','));
        },
        hasMapping(fieldName) {
            const map = JSON.parse(this.config.mappingConfig || '{}');
            return !!map[fieldName];
        },
        getMappingSummary(fieldName) {
            const map = JSON.parse(this.config.mappingConfig || '{}');
            if (!map[fieldName]) return '';
            return `${map[fieldName].sourcePath.split('.')[1]} -> ${map[fieldName].targetPath.split('.')[1]}`;
        },
        openMappingDialog(row) {
            this.mappingDialog.fieldName = row.name;
            this.mappingDialog.relationshipName = row.relationshipName;
            this.mappingDialog.referenceTo = row.referenceTo && row.referenceTo.length > 0 ? row.referenceTo[0] : '';
            this.mappingDialog.open = true;
            this.mappingDialog.loading = true;

            const map = JSON.parse(this.config.mappingConfig || '{}');
            if (map[row.name]) {
                this.mappingDialog.sourceField = map[row.name].sourcePath.split('.')[1];
                this.mappingDialog.targetField = map[row.name].targetPath.split('.')[1];
            } else {
                this.mappingDialog.sourceField = 'Id';
                this.mappingDialog.targetField = this.config.targetKeyField;
            }

            if (this.mappingDialog.referenceTo) {
                getSObjectFields(this.sourceOrgId, this.mappingDialog.referenceTo).then(res => {
                    this.mappingDialog.relFields = res.data;
                    this.mappingDialog.loading = false;
                });
            }
        },
        saveMapping() {
            const { fieldName, relationshipName, sourceField, targetField } = this.mappingDialog;
            const map = JSON.parse(this.config.mappingConfig || '{}');
            map[fieldName] = { type: 'REFERENCE', sourcePath: `${relationshipName}.${sourceField}`, targetPath: `${relationshipName}.${targetField}` };
            this.$set(this.config, 'mappingConfig', JSON.stringify(map));
            this.mappingDialog.open = false;
        },
        //一键智能映射关联字段
        autoMapReferences() {
            if (!this.currentFields || this.currentFields.length === 0) return;

            const map = JSON.parse(this.config.mappingConfig || '{}');
            const targetKey = this.config.targetKeyField || 'old_sfdc_id__c';
            let mappedCount = 0;

            // ==========================================
            // 【新增】：特殊对象映射字典 (对象名 -> 用于跨环境比对的唯一标识字段)
            // ==========================================
            const SPECIAL_OBJ_MAPPING = {
                'RecordType': 'DeveloperName',
                'User': 'StaffId__c',   // 用户的跨环境唯一键通常是 Username
                'Profile': 'Name',
                'Group': 'DeveloperName',
                'Queue': 'DeveloperName'
            };

            this.currentFields.forEach(f => {
                // 必须是 reference 类型，且存在关联对象 (relationshipName 和 referenceTo)
                // 并且该字段当前没有被手动排除，也没有被映射过
                if (f.type === 'reference' && f.relationshipName && f.referenceTo && f.referenceTo.length > 0) {
                    if (!map[f.name] && !this.isExcluded(f.name)) {

                        const refObj = f.referenceTo[0]; // 获取主关联对象名
                        let sourceField = 'Id';
                        let targetField = targetKey;

                        // ==========================================
                        // 【核心智能路由逻辑】
                        // ==========================================

                        // 1. 处理特殊的多态字段 (OwnerId 可能是 User 也可能是 Group)
                        // SOQL 中两者共有的、且能用于比对的最安全字段是 Name
                        if (f.name === 'OwnerId') {
                            sourceField = 'Name';
                            targetField = 'Name';
                        }
                        // 2. 拦截极度危险的多态字段 (任务/事件的关联)，直接跳过，交由人工判断或排除
                        else if (f.name === 'WhoId' || f.name === 'WhatId') {
                            return; // 直接跳过，不计入自动映射
                        }
                        // 3. 处理系统特殊对象 (按字典命中)
                        else if (SPECIAL_OBJ_MAPPING[refObj]) {
                            sourceField = SPECIAL_OBJ_MAPPING[refObj];
                            targetField = SPECIAL_OBJ_MAPPING[refObj];
                        }
                        // 4. 普通业务对象，维持 targetKey (如 old_sfdc_id__c)
                        else {
                            sourceField = 'Id';
                            targetField = targetKey;
                        }

                        // 生成最终的双端 JSON 路径
                        map[f.name] = {
                            type: 'REFERENCE',
                            sourcePath: `${f.relationshipName}.${sourceField}`,
                            targetPath: `${f.relationshipName}.${targetField}`
                        };
                        mappedCount++;
                    }
                }
            });

            if (mappedCount > 0) {
                this.$set(this.config, 'mappingConfig', JSON.stringify(map));
                this.$modal.msgSuccess(`🎉 智能映射完成！共精准处理了 ${mappedCount} 个关联字段。`);
            } else {
                this.$modal.msgInfo("当前没有需要自动映射的关联字段，或特殊字段需手工处理。");
            }
        }
    }
};
</script>

<style scoped>
/* 样式保留之前 wizard.vue 中的样式 */
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

.formula-container {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    width: 100%;
}

.formula-text {
    font-family: Consolas, Menlo, monospace;
    color: #E6A23C;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    margin-right: 8px;
    line-height: 1.5;
}

/* 当开启换行时的样式 */
.formula-container.is-wrapped .formula-text {
    white-space: pre-wrap;
    word-break: break-all;
}

.copy-btn {
    padding: 0;
    font-size: 14px;
    color: #909399;
}

.copy-btn:hover {
    color: #409EFF;
}
</style>