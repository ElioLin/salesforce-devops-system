<template>
    <div class="app-container">
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch">
            <el-form-item label="任务名称" prop="jobName">
                <el-input v-model="queryParams.jobName" placeholder="搜索任务..." @keyup.enter.native="getList" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" icon="el-icon-search" @click="getList">搜索</el-button>
                <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
            </el-form-item>
        </el-form>

        <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
                <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新建任务</el-button>
            </el-col>
            <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
        </el-row>

        <el-table v-loading="loading" :data="jobList" border>
            <el-table-column label="任务名称" prop="jobName" show-overflow-tooltip min-width="150" />

            <el-table-column label="比对对象" align="left" min-width="220">
                <template slot-scope="scope">
                    <div v-if="scope.row.objectNames && scope.row.objectNames.length > 0">
                        <el-tag v-for="(name, index) in scope.row.objectNames.slice(0, 2)" :key="index" size="mini"
                            effect="plain" style="margin-right: 5px; margin-bottom: 2px;">
                            {{ name }}
                        </el-tag>
                        <el-popover v-if="scope.row.objectNames.length > 2" placement="top" width="250" trigger="hover">
                            <div style="display: flex; flex-wrap: wrap; gap: 5px;">
                                <el-tag v-for="(name, idx) in scope.row.objectNames" :key="idx" size="mini"
                                    type="info">{{ name }}</el-tag>
                            </div>
                            <el-tag slot="reference" size="mini" type="info" style="cursor: pointer">+{{
                                scope.row.objectNames.length - 2 }}</el-tag>
                        </el-popover>
                    </div>
                    <span v-else style="color: #C0C4CC; font-size: 12px;">未配置</span>
                </template>
            </el-table-column>

            <el-table-column label="源环境" align="center" width="120">
                <template slot-scope="scope">
                    <el-tag type="info">{{ getOrgName(scope.row.sourceOrgId) }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="目标环境" align="center" width="120">
                <template slot-scope="scope">
                    <el-tag type="success">{{ getOrgName(scope.row.targetOrgId) }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="最新状态" align="center" width="100">
                <template slot-scope="scope">
                    <el-tag :type="getStatusType(scope.row.status)">{{ scope.row.status }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="创建时间" align="center" prop="createTime" width="160">
                <template slot-scope="scope">
                    <span>{{ parseTime(scope.row.createTime) }}</span>
                </template>
            </el-table-column>

            <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="300" fixed="right">
                <template slot-scope="scope">
                    <el-button size="mini" type="text" icon="el-icon-video-play" @click="handleStart(scope.row)"
                        v-if="scope.row.status !== 'RUNNING'">启动</el-button>

                    <el-button size="mini" type="text" icon="el-icon-monitor" @click="handleMonitor(scope.row)"
                        v-if="scope.row.status !== 'IDLE' && scope.row.status !== null">监控</el-button>

                    <el-button size="mini" type="text" icon="el-icon-setting"
                        @click="handleConfig(scope.row)">配置</el-button>

                    <el-button size="mini" type="text" icon="el-icon-edit"
                        @click="handleUpdate(scope.row)">修改</el-button>
                    <el-button size="mini" type="text" icon="el-icon-delete"
                        @click="handleDelete(scope.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
            :limit.sync="queryParams.pageSize" @pagination="getList" />

        <job-dialog ref="jobDialog" @ok="getList" />
        <wizard-config ref="wizardConfig" @ok="getList" />
    </div>
</template>

<script>
import { listJob, delJob } from "@/api/salesforce/dataJob";
import { runJob } from "@/api/salesforce/reconcile";
// 引入组件
import JobDialog from "./components/JobDialog";
import WizardConfig from "./wizard";

export default {
    name: "ReconcileIndex",
    components: { JobDialog, WizardConfig },
    data() {
        return {
            loading: true,
            jobList: [],
            total: 0,
            showSearch: true,
            queryParams: {
                pageNum: 1,
                pageSize: 10,
                jobName: undefined
            }
        };
    },
    created() {
        this.getList();
    },
    methods: {
        getList() {
            this.loading = true;
            listJob(this.queryParams).then(res => {
                this.jobList = res.rows;
                this.total = res.total;
                this.loading = false;
            });
        },
        resetQuery() {
            this.queryParams.jobName = undefined;
            this.getList();
        },
        getOrgName(id) {
            // 这里可以对接 Org 列表接口回显名称，目前暂显 ID
            return id;
        },
        getStatusType(status) {
            if (status === 'RUNNING') return '';
            if (status === 'FINISHED') return 'success';
            if (status === 'IDLE') return 'info';
            if (status === 'FAILED') return 'danger';
            return 'info';
        },

        // --- 业务操作 ---

        // 1. 启动任务
        handleStart(row) {
            this.$confirm('确认启动比对任务?', '提示', { type: 'warning' }).then(() => {
                runJob(row.id).then(() => {
                    this.$message.success("任务已启动，正在跳转监控台...");
                    this.handleMonitor(row); // 启动成功直接跳转
                    // 刷新当前列表状态
                    this.getList();
                });
            });
        },

        // 2. 跳转监控页
        handleMonitor(row) {
            this.$router.push({ 
                path: '/salesforce/jobMonitor', // <--- 请根据实际菜单路径确认此处
                query: { jobId: row.id } 
            });
        },

        // 3. 打开基础信息弹窗
        handleAdd() {
            this.$refs.jobDialog.init();
        },
        handleUpdate(row) {
            this.$refs.jobDialog.init(row.id);
        },

        // 4. 打开配置向导 (Wizard)
        handleConfig(row) {
            console.log("Opening wizard for job:", row.id);
            // 调用 wizard.vue 中的 init 方法
            this.$refs.wizardConfig.init(row.id);
        },

        // 5. 删除任务
        handleDelete(row) {
            this.$confirm('确认删除该任务吗?', '警告', { type: 'warning' }).then(() => {
                delJob(row.id).then(() => {
                    this.$message.success("删除成功");
                    this.getList();
                });
            });
        }
    }
};
</script>