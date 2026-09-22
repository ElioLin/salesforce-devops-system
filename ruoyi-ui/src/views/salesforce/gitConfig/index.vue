<template>
    <div class="app-container">
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch"
            label-width="68px">
            <el-form-item label="配置名称" prop="name">
                <el-input v-model="queryParams.name" placeholder="请输入名称" clearable @keyup.enter.native="handleQuery" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
                <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
            </el-form-item>
        </el-form>

        <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
                <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增配置</el-button>
            </el-col>
            <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
        </el-row>

        <el-table v-loading="loading" :data="configList" border
            :header-cell-style="{ background: '#f8f9fa', color: '#303133', fontWeight: 'bold' }">
            <el-table-column label="配置名称" prop="name" align="center" width="220" />
            <el-table-column label="仓库地址" prop="repoUrl" align="center" show-overflow-tooltip>
                <template slot-scope="scope">
                    <span style="font-family: Consolas, monospace;"><i class="el-icon-link"></i> {{ scope.row.repoUrl
                        }}</span>
                </template>
            </el-table-column>
            <el-table-column label="认证方式" prop="authType" align="center" width="120">
                <template slot-scope="scope">
                    <el-tag size="small" type="info">{{ scope.row.authType }}</el-tag>
                </template>
            </el-table-column>
            <el-table-column label="全局状态" prop="isActive" align="center" width="100">
                <template slot-scope="scope">
                    <el-switch v-model="scope.row.isActive" :active-value="1" :inactive-value="0"
                        @change="handleStatusChange(scope.row)"></el-switch>
                </template>
            </el-table-column>
            <el-table-column label="创建时间" prop="createTime" align="center" width="160" />

            <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="280" fixed="right">
                <template slot-scope="scope">
                    <el-button size="mini" type="text" icon="el-icon-connection" style="color: #67C23A"
                        @click="handleTest(scope.row)" :loading="testingId === scope.row.id">测试连通性</el-button>
                    <el-button size="mini" type="text" icon="el-icon-edit"
                        @click="handleUpdate(scope.row)">修改</el-button>
                    <el-button size="mini" type="text" icon="el-icon-delete" class="text-danger"
                        @click="handleDelete(scope.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
            @pagination="getList" />

        <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body custom-class="modern-dialog">
            <el-form ref="form" :model="form" :rules="rules" label-width="110px">
                <el-form-item label="配置名称" prop="name">
                    <el-input v-model="form.name" placeholder="如: Core CRM 仓库" />
                </el-form-item>
                <el-form-item label="仓库地址" prop="repoUrl">
                    <el-input v-model="form.repoUrl" placeholder="如: http://10.0.x.x/xxx.git" />
                </el-form-item>
                <el-form-item label="认证类型" prop="authType">
                    <el-radio-group v-model="form.authType">
                        <el-radio label="TOKEN">Access Token / 密码</el-radio>
                        <el-radio label="SSH" disabled>SSH Key (暂不支持)</el-radio>
                    </el-radio-group>
                </el-form-item>
                <el-form-item label="访问凭证" prop="credentials">
                    <el-input v-model="form.credentials" type="password" show-password placeholder="请输入 Token 密文" />
                    <div v-if="form.id" style="font-size: 12px; color: #E6A23C; line-height: 1.4; margin-top: 5px;">
                        <i class="el-icon-lock"></i> 凭证已加密。留空则保持原有秘钥不变。
                    </div>
                </el-form-item>
                <el-form-item label="是否启用" prop="isActive">
                    <el-switch v-model="form.isActive" :active-value="1" :inactive-value="0"></el-switch>
                </el-form-item>
            </el-form>
            <div slot="footer" class="dialog-footer">
                <el-button type="primary" @click="submitForm">确 定</el-button>
                <el-button @click="open = false">取 消</el-button>
            </div>
        </el-dialog>
    </div>
</template>

<script>
import { listConfig, getConfig, delConfig, addConfig, updateConfig, testConnection } from "@/api/salesforce/gitConfig";

export default {
    name: "GitConfig",
    data() {
        return {
            loading: true, showSearch: true, total: 0, configList: [],
            title: "", open: false, testingId: null,
            queryParams: { pageNum: 1, pageSize: 10, name: undefined },
            form: {},
            rules: {
                name: [{ required: true, message: "名称不能为空", trigger: "blur" }],
                repoUrl: [{ required: true, message: "仓库地址不能为空", trigger: "blur" }],
                credentials: [{ required: true, message: "新增时凭证不能为空", trigger: "blur" }]
            }
        };
    },
    created() { this.getList(); },
    methods: {
        getList() {
            this.loading = true;
            listConfig(this.queryParams).then(response => {
                this.configList = response.rows; this.total = response.total; this.loading = false;
            });
        },
        handleQuery() { this.queryParams.pageNum = 1; this.getList(); },
        resetQuery() { this.resetForm("queryForm"); this.handleQuery(); },
        reset() { this.form = { id: null, name: null, repoUrl: null, authType: 'TOKEN', credentials: null, isActive: 1 }; this.resetForm("form"); },
        handleAdd() { this.reset(); this.open = true; this.title = "新增 Git 配置"; this.rules.credentials[0].required = true; },
        handleUpdate(row) {
            this.reset();
            this.rules.credentials[0].required = false; // 修改时密码非必填
            getConfig(row.id).then(response => { this.form = response.data; this.open = true; this.title = "修改 Git 配置"; });
        },
        submitForm() {
            this.$refs["form"].validate(valid => {
                if (valid) {
                    const action = this.form.id != null ? updateConfig : addConfig;
                    action(this.form).then(() => { this.$modal.msgSuccess("保存成功"); this.open = false; this.getList(); });
                }
            });
        },
        handleStatusChange(row) {
            updateConfig({ id: row.id, isActive: row.isActive }).then(() => { this.$modal.msgSuccess("状态切换成功"); });
        },
        handleDelete(row) {
            this.$confirm('是否确认删除名称为"' + row.name + '"的配置？', "警告", { type: "warning" }).then(() => {
                return delConfig(row.id);
            }).then(() => { this.getList(); this.$modal.msgSuccess("删除成功"); }).catch(() => { });
        },
        handleTest(row) {
            this.testingId = row.id;
            testConnection(row).then(() => {
                this.$notify({ title: '连接成功', message: '网络链路与 Token 鉴权通过！', type: 'success' });
                this.testingId = null;
            }).catch(() => { this.testingId = null; });
        }
    }
};
</script>
<style scoped>
.text-danger {
    color: #f56c6c;
}

::v-deep .modern-dialog .el-dialog__header {
    border-bottom: 1px solid #ebeef5;
    padding-bottom: 20px;
    font-weight: bold;
}

::v-deep .modern-dialog .el-dialog__footer {
    border-top: 1px solid #ebeef5;
    padding-top: 15px;
    background-color: #fafafa;
    border-radius: 0 0 8px 8px;
}
</style>