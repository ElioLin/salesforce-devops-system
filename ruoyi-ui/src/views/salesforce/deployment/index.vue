<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="queryParams.title" placeholder="请输入部署包标题" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>

      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width: 200px">
          <el-option v-for="dict in dict.type.sys_salesforce_deploy_status" :key="dict.value" :label="dict.label"
            :value="dict.value" />
        </el-select>
      </el-form-item>

      <el-form-item label="创建者" prop="createBy">
        <el-input v-model="queryParams.createBy" placeholder="请输入创建者账号" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>

      <el-form-item label="创建时间">
        <el-date-picker v-model="dateRange" style="width: 240px" value-format="yyyy-MM-dd" type="daterange"
          range-separator="-" start-placeholder="开始日期" end-placeholder="结束日期"></el-date-picker>
      </el-form-item>

      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新建部署包</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single"
          @click="handleUpdate">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple"
          @click="handleDelete">删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table ref="table" v-loading="loading" :data="deploymentList" @selection-change="handleSelectionChange" border
      :default-sort="defaultSort" @sort-change="handleSortChange">
      <el-table-column type="selection" width="55" align="center" />

      <el-table-column label="标题" prop="title" show-overflow-tooltip sortable="custom" min-width="200">
        <template slot-scope="scope">
          <el-link type="primary" :underline="false" @click="handleEnterDetail(scope.row)">
            {{ scope.row.title }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="源环境" prop="sourceOrgId" width="150" align="center">
        <template slot-scope="scope">
          {{ formatOrgName(scope.row.sourceOrgId) }}
        </template>
      </el-table-column>

      <el-table-column label="目标环境" prop="targetOrgId" width="150" align="center">
        <template slot-scope="scope">
          {{ formatOrgName(scope.row.targetOrgId) }}
        </template>
      </el-table-column>

      <el-table-column label="状态" prop="status" width="120" align="center">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_salesforce_deploy_status" :value="scope.row.status" />
        </template>
      </el-table-column>

      <el-table-column label="创建者" prop="createBy" width="100" align="center" />

      <el-table-column label="创建时间" prop="createTime" width="160" align="center" sortable="custom" />

      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-s-operation"
            @click="handleEnterDetail(scope.row)">管理/部署</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      :page-sizes="[50, 100, 150, 200]" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="部署标题" prop="title">
          <el-input v-model="form.title" placeholder="例如: 2025 Sprint 1 上线" />
        </el-form-item>

        <el-row>
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
  </div>
</template>

<script>
import { listDeployment, addDeployment, updateDeployment, delDeployment, getDeployment } from "@/api/salesforce/deployment";
import { listOrg } from "@/api/salesforce/org";
import request from '@/utils/request';

export default {
  name: "Deployment",
  dicts: ['sys_salesforce_deploy_status'],
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      deploymentList: [],
      title: "",
      open: false,
      orgOptions: [],
      dateRange: [],

      // 【优化 3】定义默认排序，用于 UI 显示箭头
      defaultSort: { prop: 'createTime', order: 'descending' },

      queryParams: {
        pageNum: 1,
        pageSize: 50,
        title: null,
        status: null,   // 【新增】
        createBy: null, // 【新增】
        // 【优化 4】设置默认查询参数为按创建时间降序
        orderByColumn: 'create_time',
        isAsc: 'desc'
      },
      form: {},
      rules: {
        // ... rules 保持不变
        title: [{ required: true, message: "标题不能为空", trigger: "blur" }],
        sourceOrgId: [{ required: true, message: "请选择源环境", trigger: "change" }],
        targetOrgId: [{ required: true, message: "请选择目标环境", trigger: "change" }],
        testLevel: [{ required: true, message: "请选择测试级别", trigger: "change" }]
      }
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
      this.queryParams.pageNum = 1;
      this.getList();
    },

    /** 【优化 5】处理排序变更 */
    handleSortChange({ column, prop, order }) {
      // 1. 设置排序字段
      // 如果前端属性名是驼峰 (createTime)，需要转为数据库下划线 (create_time)
      // 若依后端通常通过 orderByColumn 接收
      if (prop === 'createTime') {
        this.queryParams.orderByColumn = 'create_time';
      } else {
        this.queryParams.orderByColumn = prop; // 其他字段假设一致
      }

      // 2. 设置排序顺序
      this.queryParams.isAsc = order === 'ascending' ? 'asc' : 'desc';

      // 3. 如果取消了排序 (order 为 null)，恢复默认排序
      if (order === null) {
        this.queryParams.orderByColumn = 'create_time';
        this.queryParams.isAsc = 'desc';
      }

      this.getList();
    },

    /** 【优化 6】重置按钮需重置排序 */
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
      };
      this.dateRange = [];
      this.resetForm("queryForm");
      this.queryParams.title = null;

      // 重置为默认排序
      this.queryParams.orderByColumn = 'create_time';
      this.queryParams.isAsc = 'desc';
      // 清除表格 UI 上的排序状态
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
      this.form = { testLevel: 'NoTestRun' };
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

              // 【修复与优化】
              // 后端返回结构为: { msg: "...", code: 200, data: "ID字符串" }
              // 取出 data 中的 ID 进行跳转
              const newId = response.data;

              if (newId) {
                this.$router.push({
                  path: "/salesforce/deploymentDetail",
                  query: { id: newId }
                });
              } else {
                // 兜底逻辑：万一后端没返回ID，则回退到刷新列表
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
      this.$modal.confirm('是否确认删除部署包编号为"' + ids + '"的数据项？').then(function () {
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
    }
  }
};
</script>