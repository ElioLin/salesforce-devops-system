<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="queryParams.title" placeholder="请输入部署包标题" clearable @keyup.enter.native="getList" />
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

    <el-table v-loading="loading" :data="deploymentList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="标题" prop="title" show-overflow-tooltip />
      <el-table-column label="源环境ID" prop="sourceOrgId" width="100" align="center" />
      <el-table-column label="目标环境ID" prop="targetOrgId" width="100" align="center" />
      <el-table-column label="状态" prop="status" width="120" align="center">
        <template slot-scope="scope">
          <el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="测试级别" prop="testLevel" width="120" align="center" />
      <el-table-column label="创建者" prop="createBy" width="100" align="center" />
      <el-table-column label="创建时间" prop="createTime" width="160" align="center" />
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
      @pagination="getList" />

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
import { listOrg } from "@/api/salesforce/org"; // 假设你有这个API获取Org列表
import request from '@/utils/request';

export default {
  name: "Deployment",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 总条数
      total: 0,
      // 部署包表格数据
      deploymentList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 环境选项
      orgOptions: [],
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        title: null
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
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
    /** 查询列表 */
    getList() {
      this.loading = true;
      listDeployment(this.queryParams).then(response => {
        this.deploymentList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    /** 获取Org列表 */
    getOrgList() {
      // 假设API是 listOrg
      listOrg({ pageNum: 1, pageSize: 100 }).then(res => this.orgOptions = res.rows);
    },
    /** 状态显示样式 */
    statusType(status) {
      if (status === 'Succeeded') return 'success';
      if (status === 'Failed') return 'danger';
      if (status === 'Deploying' || status === 'Validating') return 'warning';
      return 'info';
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.form = {
        id: null,
        title: null,
        sourceOrgId: null,
        targetOrgId: null,
        status: null,
        testLevel: null,
        createTime: null,
        updateTime: null
      }
      this.resetForm("form")
      this.queryParams.title = null;
      this.handleQuery();
    },
    /** 多选框选中数据 */
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.form = { testLevel: 'NoTestRun' }; // 默认值
      this.open = true;
      this.title = "新建部署包";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.form = {}; // 先重置表单
      // 优先使用行内 ID，如果没传 row (比如点的顶部修改按钮)，就用选中的 ids
      const id = row.id || this.ids;

      if (!id) {
        this.$modal.msgError("请选择要修改的数据");
        return;
      }

      getDeployment(id).then(response => {
        this.form = response.data;

        // 【重要】有些下拉框可能因为数据类型问题回显失败（比如数字 vs 字符串）
        // 如果你的 OrgId 是数字，下拉框 value 也是数字，那就没问题。

        this.open = true;
        this.title = "修改部署包";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateDeployment(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            }).catch(() => {
              // 防止点击没反应：如果报错了，至少控制台会有输出
            });
          } else {
            addDeployment(this.form).then(response => {
              this.$modal.msgSuccess("创建成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
     // 取消按钮
     cancel() {
      this.open = false
      this.reset()
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除部署包编号为"' + ids + '"的数据项？').then(function () {
        return delDeployment(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => { });
    },
    /** 进入详情页 */
    handleEnterDetail(row) {
      // 路由跳转，注意需要在 router/index.js 配置路由
      this.$router.push({
        path: "/salesforce/deploymentDetail", 
        query: { id: row.id }
      });
    }
  }
};
</script>