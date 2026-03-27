<template>
  <div class="app-container">
    <el-collapse-transition>
      <div class="search-wrapper" v-show="showSearch">
        <el-form :model="queryParams" ref="queryForm" size="small" label-position="right" label-width="72px"
          class="custom-search-form">

          <el-row :gutter="24">
            <el-col :span="6">
              <el-form-item label="标题" prop="title">
                <el-input v-model="queryParams.title" placeholder="请输入部署包标题" clearable @input="handleInputSearch"
                  @keyup.enter.native="handleQuery" @clear="handleQuery" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="部署类型" prop="deployType">
                <el-select v-model="queryParams.deployType" placeholder="请选择部署类型" clearable @change="handleQuery"
                  style="width: 100%">
                  <el-option v-for="dict in dict.type.sf_deploy_type" :key="dict.value" :label="dict.label"
                    :value="dict.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="需求单号" prop="demandNo">
                <el-input v-model="queryParams.demandNo" placeholder="请输入需求单号" clearable @input="handleInputSearch"
                  @keyup.enter.native="handleQuery" @clear="handleQuery" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="提出人员" prop="demandPersonnel">
                <el-select v-model="queryParams.demandPersonnel" placeholder="请选择提出人员" clearable filterable
                  @change="handleQuery" style="width: 100%">
                  <el-option v-for="dict in dict.type.sf_demand_personnel" :key="dict.value" :label="dict.label"
                    :value="dict.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="24">
            <el-col :span="6">
              <el-form-item label="状态" prop="status">
                <el-select v-model="queryParams.status" placeholder="请选择状态" clearable @change="handleQuery"
                  style="width: 100%">
                  <el-option v-for="dict in dict.type.sys_salesforce_deploy_status" :key="dict.value"
                    :label="dict.label" :value="dict.value" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="创建者" prop="createBy">
                <el-input v-model="queryParams.createBy" placeholder="请输入创建者账号" clearable @input="handleInputSearch"
                  @keyup.enter.native="handleQuery" @clear="handleQuery" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="创建时间" prop="createTime">
                <el-date-picker v-model="dateRange" value-format="yyyy-MM-dd" type="daterange" range-separator="-"
                  start-placeholder="开始日期" end-placeholder="结束日期" @change="handleQuery"
                  style="width: 100%"></el-date-picker>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <div class="search-btn-container">
                <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜 索</el-button>
                <el-button plain icon="el-icon-refresh" @click="resetQuery">重 置</el-button>
              </div>
            </el-col>
          </el-row>

        </el-form>
      </div>
    </el-collapse-transition>

    <el-row :gutter="10" class="mb8 toolbar-row">
      <el-col :span="1.5">
        <el-button type="primary" icon="el-icon-plus" size="small" @click="handleAdd">新建部署包</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="small" :disabled="single"
          @click="handleUpdate">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button :type="showSearch ? 'info' : 'warning'" plain
          :icon="showSearch ? 'el-icon-arrow-up' : 'el-icon-data-analysis'" size="small"
          @click="showSearch = !showSearch">
          {{ showSearch ? '收起筛选' : '高级筛选' }}
        </el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" class="right-toolbar"></right-toolbar>
    </el-row>

    <div class="table-wrapper">
      <el-table ref="table" v-loading="loading" :data="deploymentList" @selection-change="handleSelectionChange" border
        :default-sort="defaultSort" @sort-change="handleSortChange"
        :header-cell-style="{ background: '#f8f9fa', color: '#303133', fontWeight: 'bold' }"
        class="custom-scroll-table">

        <el-table-column type="selection" width="55" align="center" />

        <el-table-column label="序号" align="center" width="60">
          <template slot-scope="scope">
            <span>{{ (queryParams.pageNum - 1) * queryParams.pageSize + scope.$index + 1 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="标题" prop="title" sortable="custom" width="260">
          <template slot-scope="scope">
            <el-link type="primary" :underline="false" @click="handleEnterDetail(scope.row)"
              class="title-link multi-line-text" :title="scope.row.title">
              {{ scope.row.title }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column label="需求单号" prop="demandNo" width="160" align="center">
          <template slot-scope="scope">
            <div class="multi-line-text" style="font-family: Consolas, monospace;" :title="scope.row.demandNo">
              {{ scope.row.demandNo || '-' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="部署类型" prop="deployType" min-width="130" align="center">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.sf_deploy_type" :value="scope.row.deployType" />
          </template>
        </el-table-column>

        <el-table-column label="提出人员" prop="demandPersonnel" min-width="120" align="center">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.sf_demand_personnel" :value="scope.row.demandPersonnel" />
          </template>
        </el-table-column>

        <el-table-column label="源环境" prop="sourceOrgId" min-width="150" align="center" show-overflow-tooltip>
          <template slot-scope="scope">
            <el-tag size="small" type="info" effect="plain">{{ formatOrgName(scope.row.sourceOrgId) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="目标环境" prop="targetOrgId" min-width="150" align="center" show-overflow-tooltip>
          <template slot-scope="scope">
            <el-tag size="small" type="primary" effect="plain">{{ formatOrgName(scope.row.targetOrgId) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" prop="status" min-width="120" align="center">
          <template slot-scope="scope">
            <dict-tag :options="dict.type.sys_salesforce_deploy_status" :value="scope.row.status" />
          </template>
        </el-table-column>

        <el-table-column label="创建者" prop="createBy" min-width="120" align="center" />

        <el-table-column label="创建时间" prop="createTime" min-width="160" align="center" sortable="custom" />

        <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="260" fixed="right">
          <template slot-scope="scope">
            <div class="action-btns">
              <el-button size="mini" type="text" icon="el-icon-s-operation"
                @click="handleEnterDetail(scope.row)">管理/部署</el-button>
              <el-button size="mini" type="text" icon="el-icon-document-copy"
                @click="handleClone(scope.row)">复制</el-button>
              <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
              <el-button size="mini" type="text" class="text-danger" icon="el-icon-delete"
                @click="handleDelete(scope.row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <pagination class="custom-pagination" :total="total" :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize" :page-sizes="[20, 50, 100, 150, 200]"
      layout="total, sizes, prev, pager, next, jumper" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="680px" append-to-body :close-on-click-modal="false"
      custom-class="modern-dialog">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="部署标题" prop="title">
          <el-input v-model="form.title" placeholder="例如: 2025 Sprint 1 上线" />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="部署类型" prop="deployType">
              <el-select v-model="form.deployType" placeholder="选择类型" style="width:100%">
                <el-option v-for="dict in dict.type.sf_deploy_type" :key="dict.value" :label="dict.label"
                  :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="提出人员" prop="demandPersonnel">
              <el-select v-model="form.demandPersonnel" placeholder="选择人员" filterable style="width:100%">
                <el-option v-for="dict in dict.type.sf_demand_personnel" :key="dict.value" :label="dict.label"
                  :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="需求单号" prop="demandNo">
          <el-input v-model="form.demandNo" placeholder="请输入单号" />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="源环境" prop="sourceOrgId">
              <el-select v-model="form.sourceOrgId" placeholder="请选择源环境" style="width:100%">
                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标环境" prop="targetOrgId">
              <el-select v-model="form.targetOrgId" placeholder="请选择目标环境" style="width:100%">
                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
                  :disabled="item.id === form.sourceOrgId" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="测试级别" prop="testLevel">
          <el-select v-model="form.testLevel" placeholder="请选择测试级别" style="width:100%">
            <el-option label="默认 (NoTestRun)" value="NoTestRun" />
            <el-option label="运行本地测试 (RunLocalTests)" value="RunLocalTests" />
            <el-option label="运行指定测试 (RunSpecifiedTests)" value="RunSpecifiedTests" />
          </el-select>
        </el-form-item>

        <el-form-item label="指定测试类" prop="specifiedTests" v-if="form.testLevel === 'RunSpecifiedTests'">
          <el-input type="textarea" v-model="form.specifiedTests" placeholder="请输入测试类名，用逗号分隔" />
        </el-form-item>

        <el-form-item label="备注" prop="description">
          <el-input type="textarea" v-model="form.description" placeholder="请输入备注信息" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="复制部署包" :visible.sync="cloneOpen" width="550px" append-to-body :close-on-click-modal="false"
      custom-class="modern-dialog">
      <el-form ref="cloneForm" :model="cloneForm" :rules="cloneRules" label-width="90px">
        <el-form-item label="新标题" prop="title">
          <el-input v-model="cloneForm.title" placeholder="请输入新部署包标题" />
        </el-form-item>
        <el-form-item label="需求单号" prop="demandNo">
          <el-input v-model="cloneForm.demandNo" placeholder="请输入单号" />
        </el-form-item>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="部署类型" prop="deployType">
              <el-select v-model="cloneForm.deployType" placeholder="选择类型" style="width:100%">
                <el-option v-for="dict in dict.type.sf_deploy_type" :key="dict.value" :label="dict.label"
                  :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="提出人员" prop="demandPersonnel">
              <el-select v-model="cloneForm.demandPersonnel" placeholder="选择提出人员" filterable style="width:100%">
                <el-option v-for="dict in dict.type.sf_demand_personnel" :key="dict.value" :label="dict.label"
                  :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="源环境" prop="sourceOrgId">
              <el-select v-model="cloneForm.sourceOrgId" placeholder="请选择源环境" style="width:100%">
                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标环境" prop="targetOrgId">
              <el-select v-model="cloneForm.targetOrgId" placeholder="请选择目标环境" style="width:100%">
                <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
                  :disabled="item.id === cloneForm.sourceOrgId" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <div class="tip-box">
          <i class="el-icon-info"></i> 说明：<br />
          1. 将复制原部署包中的所有元数据清单。<br />
          2. 业务属性及测试策略配置将被保留。<br />
          3. 状态将重置为“草稿”，并清除所有比对结果。
        </div>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="cloneLoading" @click="submitClone">确 定</el-button>
        <el-button @click="cloneOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
// 这里保持完全一致的代码逻辑，未修改任何原有的业务处理
import { listDeployment, addDeployment, updateDeployment, delDeployment, getDeployment, cloneDeployment } from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import request from '@/utils/request';

export default {
  name: "Deployment",
  dicts: ['sys_salesforce_deploy_status', 'sf_deploy_type', 'sf_demand_personnel'],
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      inputSearchTimer: null,
      deploymentList: [],
      title: "",
      open: false,
      orgOptions: [],
      dateRange: [],

      defaultSort: { prop: 'createTime', order: 'descending' },

      queryParams: {
        pageNum: 1,
        pageSize: 20,
        title: null,
        status: null,
        createBy: null,
        deployType: null,
        demandNo: null,
        demandPersonnel: null,
        orderByColumn: 'create_time',
        isAsc: 'desc'
      },
      form: {},
      rules: {
        title: [{ required: true, message: "标题不能为空", trigger: "blur" }],
        deployType: [{ required: true, message: "请选择部署类型", trigger: "change" }],
        sourceOrgId: [{ required: true, message: "请选择源环境", trigger: "change" }],
        targetOrgId: [{ required: true, message: "请选择目标环境", trigger: "change" }],
        testLevel: [{ required: true, message: "请选择测试级别", trigger: "change" }]
      },
      cloneOpen: false,
      cloneLoading: false,
      cloneForm: {},
      cloneRules: {
        title: [{ required: true, message: "标题不能为空", trigger: "blur" }],
        deployType: [{ required: true, message: "请选择部署类型", trigger: "change" }],
        sourceOrgId: [{ required: true, message: "请选择源环境", trigger: "change" }],
        targetOrgId: [{ required: true, message: "请选择目标环境", trigger: "change" }],
      },
      originalRow: null,
    };
  },
  created() {
    this.getList();
    this.getOrgList();
  },
  methods: {
    getList() {
      this.loading = true;
      listDeployment(this.addDateRange(this.queryParams, this.dateRange)).then(response => {
        this.deploymentList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    getOrgList() {
      listOrg({ pageNum: 1, pageSize: 100 }).then(res => this.orgOptions = res.rows);
    },
    formatOrgName(orgId) {
      if (!orgId) return '';
      const org = this.orgOptions.find(item => item.id === orgId);
      return org ? org.name : orgId;
    },
    statusType(status) {
      if (status === 'Succeeded') return 'success';
      if (status === 'Failed') return 'danger';
      if (status === 'Deploying' || status === 'Validating') return 'warning';
      return 'info';
    },
    handleQuery() {
      if (this.inputSearchTimer) {
        clearTimeout(this.inputSearchTimer);
      }
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** * ：文本输入框防抖搜索机制
     * 原理：用户每次打字都会清除上一次的定时器，直到用户停止打字 500ms 后，才会真正向后端发送请求
     */
    handleInputSearch() {
      if (this.inputSearchTimer) {
        clearTimeout(this.inputSearchTimer);
      }
      this.inputSearchTimer = setTimeout(() => {
        this.handleQuery();
      }, 500); // 500毫秒（半秒）的黄金停顿体验
    },
    handleSortChange({ column, prop, order }) {
      if (prop === 'createTime') {
        this.queryParams.orderByColumn = 'create_time';
      } else {
        this.queryParams.orderByColumn = prop;
      }
      this.queryParams.isAsc = order === 'ascending' ? 'asc' : 'desc';

      if (order === null) {
        this.queryParams.orderByColumn = 'create_time';
        this.queryParams.isAsc = 'desc';
      }

      this.getList();
    },
    resetQuery() {
      this.form = {
        id: null,
        title: null,
        sourceOrgId: null,
        targetOrgId: null,
        status: null,
        testLevel: null,
        createTime: null,
        updateTime: null,
        deployType: null,
        demandNo: null,
        demandPersonnel: null
      };
      this.dateRange = [];
      this.resetForm("queryForm");
      this.queryParams.title = null;
      this.queryParams.deployType = null;
      this.queryParams.demandNo = null;
      this.queryParams.demandPersonnel = null;

      this.queryParams.orderByColumn = 'create_time';
      this.queryParams.isAsc = 'desc';
      if (this.$refs.table) {
        this.$refs.table.clearSort();
      }

      this.handleQuery();
    },

    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.form = { testLevel: 'RunSpecifiedTests' };
      this.open = true;
      this.title = "新建部署包";
    },
    handleUpdate(row) {
      this.form = {};
      const id = row.id || this.ids;
      if (!id) {
        this.$modal.msgError("请选择要修改的数据");
        return;
      }
      getDeployment(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改部署包";
      });
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateDeployment(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            }).catch(() => { });
          } else {
            addDeployment(this.form).then(response => {
              this.$modal.msgSuccess("创建成功");
              this.open = false;

              const newId = response.data;
              if (newId) {
                this.$router.push({
                  path: "/salesforce/deploymentDetail",
                  query: { id: newId }
                });
              } else {
                this.getList();
              }
            });
          }
        }
      });
    },
    cancel() {
      this.open = false
      this.reset()
    },
    handleDelete(row) {
      const ids = row.id || this.ids;
      let content = '';
      let isRiskOperation = false;

      if (!row || (row.status && row.status !== 'Draft')) {
        isRiskOperation = true;
      }

      if (isRiskOperation) {
        content = `
            <div style="font-size:14px;">
                <p>确定要删除选中的部署包吗？</p>
                <div style="background-color: #fef0f0; color: #f56c6c; padding: 10px; border-radius: 4px; margin-top: 10px; border: 1px solid #fde2e2;">
                    <p style="font-weight:bold; margin-bottom: 5px;">
                        <i class="el-icon-warning"></i> 警告：检测到该部署包包含执行记录
                    </p>
                    <p style="font-size:13px; line-height: 1.6;">
                        删除操作将触发级联清理，永久删除以下关联数据：<br/>
                        1. 所有的 <b>部署历史记录 & 审计日志</b><br/>
                        2. 服务器上的 <b>物理备份文件 (ZIP)</b> <span style="font-weight:bold">(无法恢复!)</span><br/>
                        3. 部署包明细配置
                    </p>
                </div>
                <p style="margin-top:10px; color: #606266;">请确认您已做好备份，或不再需要追溯该次变更。</p>
            </div>
        `;
      } else {
        content = `是否确认删除部署包标题为 "<b>${row.title}</b>" 的数据项？`;
      }

      this.$confirm(content, "删除确认", {
        confirmButtonText: "确认删除",
        cancelButtonText: "取消",
        type: "warning",
        dangerouslyUseHTMLString: true,
        confirmButtonClass: isRiskOperation ? "el-button--danger" : "",
        closeOnClickModal: false
      }).then(function () {
        return delDeployment(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => { });
    },
    handleEnterDetail(row) {
      this.$router.push({
        path: "/salesforce/deploymentDetail",
        query: { id: row.id }
      });
    },
    handleClone(row) {
      this.originalRow = row;
      this.cloneForm = {
        title: row.title + " - Copy",
        sourceOrgId: row.sourceOrgId,
        targetOrgId: row.targetOrgId,
        deployType: row.deployType,
        demandNo: row.demandNo,
        demandPersonnel: row.demandPersonnel
      };
      this.cloneOpen = true;
      this.$nextTick(() => {
        this.$refs["cloneForm"].clearValidate();
      });
    },
    submitClone() {
      this.$refs["cloneForm"].validate(valid => {
        if (valid) {
          this.cloneLoading = true;
          cloneDeployment(this.originalRow.id, this.cloneForm).then(response => {
            this.cloneLoading = false;
            this.cloneOpen = false;
            this.$modal.msgSuccess("复制成功");

            const newId = response.data;
            if (newId) {
              this.$router.push({
                path: "/salesforce/deploymentDetail",
                query: { id: newId }
              });
            } else {
              this.getList();
            }
          }).catch(() => {
            this.cloneLoading = false;
          });
        }
      });
    }
  }
};
</script>

<style scoped>
/* 1. 搜索框两行优雅布局 */
.search-wrapper {
  background-color: #f8f9fc;
  border-radius: 8px;
  padding: 16px 20px 2px 20px;
  margin-bottom: 20px;
  border: 1px solid #ebeef5;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.02);
}

.custom-search-form .search-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.custom-search-form .el-form-item {
  margin-bottom: 14px;
  margin-right: 24px;
}

.custom-search-form .el-form-item__content .el-input,
.custom-search-form .el-form-item__content .el-select {
  width: 170px;
}

/* 右侧操作按钮顶格 */
.search-action-item {
  margin-left: auto;
  margin-right: 0 !important;
}

/* 2. 表格与操作栏美化 */
.toolbar-row {
  margin-bottom: 16px !important;
  display: flex;
  align-items: center;
}

.table-wrapper {
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.04);
  border-radius: 8px;
  overflow: hidden;
}

/* 3. Mac级质感横向滚动条定制 */
::v-deep .el-table__body-wrapper::-webkit-scrollbar {
  height: 12px;
  width: 12px;
}

::v-deep .el-table__body-wrapper::-webkit-scrollbar-thumb {
  background-color: #c0c4cc;
  border-radius: 6px;
  border: 3px solid transparent;
  background-clip: padding-box;
}

::v-deep .el-table__body-wrapper::-webkit-scrollbar-thumb:hover {
  background-color: #909399;
}

::v-deep .el-table__body-wrapper::-webkit-scrollbar-track {
  background-color: #f5f7fa;
}

/* 表格标题链接 */
.title-link {
  font-weight: 600;
  font-size: 14px;
  color: #409eff;
}

.title-link:hover {
  opacity: 0.8;
}

/* 4. 右侧固定列(Fixed)阴影美化 */
::v-deep .el-table__fixed-right::before {
  background-color: transparent !important;
}

::v-deep .el-table__fixed-right {
  box-shadow: -4px 0 10px rgba(0, 0, 0, 0.03);
}

/* 右侧操作按钮间距统一 */
.action-btns .el-button {
  margin-left: 0;
  margin-right: 12px;
  font-weight: 500;
}

.action-btns .el-button:last-child {
  margin-right: 0;
}

.text-danger {
  color: #f56c6c !important;
}

/* 5. 分页栏及其他边角 */
.custom-pagination {
  margin-top: 20px;
  text-align: right;
}

/* 提示说明框 */
.tip-box {
  background-color: #f4f4f5;
  border-left: 4px solid #909399;
  padding: 12px 16px;
  border-radius: 4px;
  margin-top: 15px;
  margin-left: 15px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

/* --- 新增：多行文本优雅截断样式 --- */
.multi-line-text {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  /* 核心：最多显示两行，超过显示省略号 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: normal;
  /* 允许换行 */
  word-break: break-all;
  /* 防止长英文或单号撑破容器 */
  line-height: 1.5;
  /* 增加行高，提升阅读舒适度 */
}
</style>