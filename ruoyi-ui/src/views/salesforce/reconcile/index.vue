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

            <el-table-column label="比对对象" align="left" min-width="280">
                <template slot-scope="scope">
                    <div v-if="scope.row.objectNames && scope.row.objectNames.length > 0"
                        style="display: flex; align-items: center; flex-wrap: wrap; gap: 5px;">
                        <el-tag v-for="(name, index) in scope.row.objectNames.slice(0, 3)" :key="index" size="small"
                            effect="light" type="primary">
                            <i class="el-icon-document"></i> {{ name }}
                        </el-tag>

                        <el-popover v-if="scope.row.objectNames.length > 3" placement="bottom" width="300"
                            trigger="hover">
                            <div style="max-height: 250px; overflow-y: auto;">
                                <div
                                    style="font-size: 13px; font-weight: bold; margin-bottom: 10px; color: #606266; border-bottom: 1px solid #EBEEF5; padding-bottom: 6px;">
                                    包含的所有比对对象 (共 {{ scope.row.objectNames.length }} 个)
                                </div>
                                <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                                    <el-tag v-for="(name, idx) in scope.row.objectNames" :key="idx" size="small"
                                        type="info" effect="plain">
                                        {{ name }}
                                    </el-tag>
                                </div>
                            </div>
                            <el-tag slot="reference" size="small" type="warning" effect="dark"
                                style="cursor: pointer; border-radius: 12px; padding: 0 10px;">
                                +{{ scope.row.objectNames.length - 3 }} 更多...
                            </el-tag>
                        </el-popover>
                    </div>
                    <span v-else style="color: #909399; font-size: 12px; font-style: italic;">
                        <i class="el-icon-warning-outline"></i> 尚未配置对象
                    </span>
                </template>
            </el-table-column>

            <el-table-column label="源环境" align="center" width="140">
                <template slot-scope="scope">
                    <el-tag type="info" size="medium">
                        <i class="el-icon-cloudy"></i> {{ getOrgName(scope.row.sourceOrgId) }}
                    </el-tag>
                </template>
            </el-table-column>

            <el-table-column label="目标环境" align="center" width="140">
                <template slot-scope="scope">
                    <el-tag type="success" size="medium">
                        <i class="el-icon-cloudy-and-sunny"></i> {{ getOrgName(scope.row.targetOrgId) }}
                    </el-tag>
                </template>
            </el-table-column>

            <el-table-column label="数据截断时间" align="center" width="160">
                <template slot-scope="scope">
                    <el-tag v-if="scope.row.dataEndTime" type="warning" size="small" effect="plain">
                        <i class="el-icon-time"></i> {{ scope.row.dataEndTime }}
                    </el-tag>
                    <span v-else style="color: #909399; font-size: 12px; font-style: italic;">
                        全量拉取 (无限制)
                    </span>
                </template>
            </el-table-column>

            <el-table-column label="最新状态" align="center" width="100">
                <template slot-scope="scope">
                    <el-tag :type="getStatusType(scope.row.status)">{{ scope.row.status }}</el-tag>
                </template>
            </el-table-column>

            <el-table-column label="最近比对结果" align="center" width="160">
                <template slot-scope="scope">
                    <div v-if="scope.row.lastDiffCount !== undefined">
                        <el-tooltip v-if="scope.row.lastDiffCount > 0"
                            :content="`其中有 ${scope.row.diffObjCount} 个对象存在数据差异`" placement="top">
                            <el-tag type="danger" effect="dark" size="small" style="cursor: help;">
                                <i class="el-icon-warning"></i> 差异数据: {{ scope.row.lastDiffCount }} 条
                            </el-tag>
                        </el-tooltip>
                        <el-tag v-else type="success" effect="plain" size="small">
                            <i class="el-icon-circle-check"></i> 数据完全一致
                        </el-tag>
                    </div>
                    <span v-else style="color: #909399; font-size: 12px; font-style: italic;">尚未执行</span>
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

                    <el-button size="mini" type="text" icon="el-icon-monitor"
                        @click="handleMonitor(scope.row)">控制台</el-button>

                    <el-button size="mini" type="text" icon="el-icon-setting"
                        @click="handleConfig(scope.row)">向导配置</el-button>

                    <el-button size="mini" type="text" icon="el-icon-delete" style="color: #F56C6C"
                        @click="handleDelete(scope.row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>

        <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
            :limit.sync="queryParams.pageSize" @pagination="getList" />

        <wizard-config ref="wizardConfig" @ok="handleJobCreated" />

    </div>
</template>

<script>
import { listJob, delJob } from "@/api/salesforce/dataJob";
import { runJob } from "@/api/salesforce/reconcile";
// 引入组件
import WizardConfig from "./wizard";
import { listOrg } from "@/api/salesforce/org";

export default {
    name: "ReconcileIndex",
    components: { WizardConfig },
    data() {
        return {
            loading: true,
            jobList: [],
            orgList: [],
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
        this.getOrgList();
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
        getOrgList() {
            listOrg().then(res => {
                // 兼容分页数据或全量数据的结构
                this.orgList = res.rows || res.data || [];
            });
        },

        //捕获新建成功的任务 ID，实现创建即跳转的丝滑体验
        handleJobCreated(jobId) {
            this.getList();
            // 如果存在有效的新建任务 ID，则自动路由至工作台
            if (jobId && (typeof jobId === 'number' || typeof jobId === 'string')) {
                this.handleMonitor({ id: jobId });
            }
        },
        resetQuery() {
            this.queryParams.jobName = undefined;
            this.getList();
        },
        getOrgName(id) {
            if (!id) return '未配置';
            const org = this.orgList.find(item => item.id === id);
            // 如果找到了匹配的Org，返回名称，否则降级显示ID
            return org ? org.name : `[未知ID:${id}]`;
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
                path: '/salesforce/jobMonitor',
                query: { jobId: row.id }
            });
        },

        // 3. 打开基础信息弹窗
        handleAdd() {
            this.$refs.wizardConfig.init();
        },
        handleUpdate(row) {
            this.$refs.wizardConfig.init(row.id);
        },

        // 4. 打开配置向导 (Wizard)
        handleConfig(row) {
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