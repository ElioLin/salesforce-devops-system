<template>
    <div class="field-config-panel" style="height: 100%; display: flex; flex-direction: column;">
        <div class="panel-header">
            <span class="panel-title">{{ objectLabel }} - 字段策略</span>
            <div class="header-actions">
                <el-select v-model="filterType" placeholder="筛选字段类型" size="small" clearable
                    style="width: 140px; margin-right: 10px">
                    <el-option v-for="type in fieldTypes" :key="type" :label="type" :value="type" />
                </el-select>
                <el-input v-model="filterKeyword" placeholder="搜索字段..." size="small" prefix-icon="el-icon-search"
                    style="width: 180px" />
            </div>
        </div>

        <div class="key-config-bar">
            <span class="label">主键策略:</span>
            <el-input v-model="config.sourceKeyField" size="mini" placeholder="Source Key (Id)" style="width: 180px">
                <template slot="prepend">源</template>
            </el-input>
            <i class="el-icon-right" style="margin: 0 15px; color: #909399"></i>
            <el-input v-model="config.targetKeyField" size="mini" placeholder="Target Key (Source_Org_Id__c)"
                style="width: 240px">
                <template slot="prepend">目标</template>
            </el-input>
        </div>

        <el-table :data="filteredFields" height="calc(100vh - 250px)" border size="small" stripe
            v-loading="loadingFields" style="width: 100%; flex: 1;">
            <el-table-column prop="name" label="字段API名" min-width="180" show-overflow-tooltip />
            <el-table-column prop="label" label="标签" min-width="150" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="100" align="center">
                <template slot-scope="scope">
                    <el-tag size="mini" :type="getFieldTypeTag(scope.row.type)">{{ scope.row.type }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="映射策略" min-width="250">
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
            fieldTypes: [],
            mappingDialog: { open: false, loading: false, fieldName: '', relationshipName: '', referenceTo: '', sourceField: '', targetField: '', relFields: [] }
        };
    },
    computed: {
        filteredFields() {
            if (!this.currentFields) return [];
            return this.currentFields.filter(f => {
                const matchKeyword = !this.filterKeyword || f.name.toLowerCase().includes(this.filterKeyword.toLowerCase());
                const matchType = !this.filterType || f.type === this.filterType;
                return matchKeyword && matchType;
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
</style>