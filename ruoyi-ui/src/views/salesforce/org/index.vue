<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="90px">
      <el-form-item label="环境类型" prop="orgType">
        <el-input v-model="queryParams.orgType" placeholder="请输入环境类型" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>

      <el-form-item label="登录用户名" prop="username">
        <el-input v-model="queryParams.username" placeholder="请输入登录用户名" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>


      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd"
          v-hasPermi="['system:org:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate"
          v-hasPermi="['system:org:edit']">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete"
          v-hasPermi="['system:org:remove']">删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport"
          v-hasPermi="['system:org:export']">导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="orgList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="主键" align="center" prop="id" />
      <el-table-column label="环境名称" align="center" prop="name" />
      <el-table-column label="环境类型(Production/Sandbox)" align="center" prop="orgType" />
      <el-table-column label="Salesforce Org ID" align="center" prop="orgId" />
      <el-table-column label="登录用户名" align="center" prop="username" />
      <el-table-column label="实例地址" align="center" prop="instanceUrl" />
      <el-table-column label="短期访问令牌" align="center" prop="accessToken" />
      <el-table-column label="长期刷新令牌" align="center" prop="refreshToken" />
      <el-table-column label="App Key" align="center" prop="clientId" />
      <el-table-column label="App Secret" align="center" prop="clientSecret" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-connection" @click="handleAuth(scope.row)"
            v-hasPermi="['system:org:edit']">去授权</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)"
            v-hasPermi="['system:org:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)"
            v-hasPermi="['system:org:remove']">删除</el-button>
          <!-- <el-row :gutter="10" class="mb8">
            <el-col :span="1.5">
              <el-input 
                v-model="testMetadataName" 
                placeholder="输入Apex类名(如 HelloWorld)" 
                size="small" 
                style="width: 200px; margin-right: 10px;"
              />
            </el-col>
            <el-col :span="1.5">
              <el-button
                type="warning"
                plain
                icon="el-icon-download"
                size="mini"
                @click="handleTestRetrieve"
              >测试拉取代码</el-button>
            </el-col>
          </el-row> -->

          <el-button size="mini" type="text" icon="el-icon-folder-opened" @click="handleOpenBrowser(scope.row)">浏览元数据
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      @pagination="getList" />

    <!-- 添加或修改Salesforce环境管理对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="环境名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入环境名称" />
        </el-form-item>
        <el-form-item label="环境类型" prop="orgType">
          <el-input v-model="form.orgType" placeholder="请输入环境类型类型" />
        </el-form-item>
        <el-form-item label="App Key" prop="clientId">
          <el-input v-model="form.clientId" placeholder="请输入App Key" />
        </el-form-item>
        <el-form-item label="App Secret" prop="clientSecret">
          <el-input v-model="form.clientSecret" placeholder="请输入App Secret" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <metadata-browser ref="metaBrowser" @view-code="handleBrowserViewCode" @diff-code="handleBrowserDiffCode" />

    <el-dialog :title="previewTitle" :visible.sync="openCode" width="80%" append-to-body>
      <monaco-editor v-if="openCode" :value="codeContent" :original="oldCodeContent" :diffEditor="isDiffMode"
        language="java" height="600px" theme="vs-dark" />

      <div slot="footer" class="dialog-footer">
        <el-button @click="openCode = false">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listOrg, getOrg, delOrg, addOrg, updateOrg } from "@/api/salesforce/org";
import MonacoEditor from '@/components/MonacoEditor'; // 引入组件
import MetadataBrowser from './MetadataBrowser';
import request from '@/utils/request';
export default {
  components: { MonacoEditor, MetadataBrowser }, // 注册组件
  name: "Org",
  data() {
    return {
      isDiffMode: false, // 是否开启比对
      oldCodeContent: "", // 旧代码
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
      // Salesforce环境管理表格数据
      orgList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: null,
        orgType: null,
        orgId: null,
        username: null,
        instanceUrl: null,
        accessToken: null,
        refreshToken: null,
        clientId: null,
        clientSecret: null,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
      },
      // 代码预览相关
      openCode: false, // 弹窗开关
      codeContent: "", // 代码内容
      previewTitle: "",

      // 临时测试用的输入框
      testMetadataName: "MDMSyncProductInfoWebService" // 默认填一个存在的类名方便测试
    }
  },
  created() {
    this.getList()
  },
  methods: {
    /** 查询Salesforce环境管理列表 */
    getList() {
      this.loading = true
      listOrg(this.queryParams).then(response => {
        this.orgList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    /** 去授权按钮操作 */
    handleAuth(row) {
      const orgId = row.id;
      this.$modal.confirm('确认要跳转到 Salesforce 进行授权吗？').then(function () {
        // 调用后端获取 URL
        return request({
          url: '/system/sf/authUrl',
          method: 'get',
          params: { Id: orgId }
        });
      }).then(response => {
        // 后端返回的是 AjaxResult，URL 在 data 字段里
        const authUrl = response.data; // 获取返回的长链接
        if (authUrl) {
          window.location.href = authUrl; // 执行跳转
        }
      }).catch(() => { });
    },
    // 取消按钮
    cancel() {
      this.open = false
      this.reset()
    },
    // 表单重置
    reset() {
      this.form = {
        id: null,
        name: null,
        orgType: null,
        orgId: null,
        username: null,
        instanceUrl: null,
        accessToken: null,
        refreshToken: null,
        clientId: null,
        clientSecret: null,
        createTime: null,
        updateTime: null
      }
      this.resetForm("form")
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加Salesforce环境管理"
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getOrg(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改Salesforce环境管理"
      })
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateOrg(this.form).then(response => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addOrg(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除Salesforce环境管理编号为"' + ids + '"的数据项？').then(function () {
        return delOrg(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => { })
    },
    /** 导出按钮操作 */
    handleExport() {
      this.download('system/org/export', {
        ...this.queryParams
      }, `org_${new Date().getTime()}.xlsx`)
    },
    // Script Methods
    handleBrowserDiffCode(data) {
      const loading = this.$loading({
        lock: true,
        text: '正在从两个环境同时拉取代码，请稍候...',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      });

      request({
        url: '/system/sf/meta/compare',
        method: 'get',
        params: {
          sourceOrgId: data.sourceOrgId,
          targetOrgId: data.targetOrgId,
          type: data.type,
          name: data.name
        }
      }).then(response => {
        loading.close();
        const diffData = response.data; // { sourceContent: "...", targetContent: "..." }

        // 设置编辑器数据
        this.codeContent = diffData.sourceContent; // 新代码 (右侧/Modified)
        this.oldCodeContent = diffData.targetContent; // 旧代码 (左侧/Original)

        // 开启比对模式
        this.isDiffMode = true;
        this.previewTitle = `比对: ${data.name} (左:目标 vs 右:源)`;
        this.openCode = true; // 打开弹窗
      }).catch(() => {
        loading.close();
      });
    },
    /** 打开浏览器 */
    handleOpenBrowser(row) {
      // 调用子组件的 open 方法
      this.$refs.metaBrowser.open(row.id);
    },
    /** 处理浏览器传来的“查看代码”请求 */
    handleBrowserViewCode(data) {
      // data 包含 { type: 'ApexClass', name: 'MyClass' }
      // 直接复用我们之前写的拉取逻辑，只需改一下参数来源
      this.previewCode(this.$refs.metaBrowser.currentOrgId, data.type, data.name);
    },
    /** 提取出来的通用预览方法 */
    previewCode(orgId, type, name) {
      const loading = this.$loading({ lock: true, text: '加载代码中...', spinner: 'el-icon-loading', background: 'rgba(0, 0, 0, 0.7)' });

      request({
        url: '/system/sf/meta/retrieve',
        method: 'get',
        params: { orgId, type, name }
      }).then(response => {
        loading.close();
        this.codeContent = response.data;
        this.previewTitle = `${type}: ${name}`;
        this.openCode = true; // 打开 Monaco 弹窗
      }).catch(() => loading.close());
    }
  }
}
</script>