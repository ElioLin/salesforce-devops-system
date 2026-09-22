<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="90px">
      <el-form-item label="环境类型" prop="orgType">
        <el-select v-model="queryParams.orgType" placeholder="请选择环境类型" clearable style="width: 200px">
          <el-option label="生产环境 / Developer" value="Production" />
          <el-option label="沙盒环境 (Sandbox)" value="Sandbox" />
        </el-select>
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
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete">删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="orgList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="50" />
      <el-table-column label="环境名称" align="center" prop="name" />
      <el-table-column label="环境类型" align="center" prop="orgType">
        <template slot-scope="scope">
          <el-tag :type="scope.row.orgType === 'Production' ? 'danger' : 'success'">
            {{ scope.row.orgType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="自定义域名" align="center" prop="customDomain" show-overflow-tooltip />
      <el-table-column label="登录用户名" align="center" prop="username" width="200" />
      <el-table-column label="授权模式" align="center" width="120">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.clientId" type="warning" size="mini">自定义App</el-tag>
          <el-tag v-else type="info" size="mini">全局App</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="授权状态" align="center" width="100">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.accessToken" type="success"><i class="el-icon-check"></i> 已授权</el-tag>
          <el-tag v-else type="info"><i class="el-icon-close"></i> 未授权</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" align="center" width="300" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-connection" @click="handleAuth(scope.row)">去授权</el-button>

          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>

          <!-- <el-button size="mini" type="text" icon="el-icon-refresh" @click="handleSyncDict(scope.row)">同步字典</el-button> -->

          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="环境名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：UAT环境 / 阿里云生产" />
        </el-form-item>

        <el-form-item label="环境类型" prop="orgType">
          <el-select v-model="form.orgType" placeholder="请选择环境类型" style="width: 100%">
            <el-option label="生产环境 / Developer Edition" value="Production" />
            <el-option label="沙盒环境 (Sandbox)" value="Sandbox" />
          </el-select>
        </el-form-item>

        <el-form-item label="自定义域名" prop="customDomain">
          <el-input v-model="form.customDomain" placeholder="例如：https://my-domain.my.salesforce.com" />
          <div style="font-size: 12px; color: #909399; line-height: 1.5;">
            可选。阿里云版或使用了 My Domain 的环境建议填写，留空则使用通用登录页。
          </div>
        </el-form-item>

        <el-divider content-position="left">高级设置 (可选)</el-divider>

        <el-form-item label="App Key" prop="clientId">
          <el-input v-model="form.clientId" placeholder="Consumer Key" />
        </el-form-item>
        <el-form-item label="App Secret" prop="clientSecret">
          <el-input v-model="form.clientSecret" placeholder="Consumer Secret" show-password />
          <div style="font-size: 12px; color: #E6A23C; line-height: 1.5;">
            <i class="el-icon-info"></i> 若留空，将使用系统默认的全局 App 进行授权（推荐）。<br>
            仅在需要使用阿里云版 Salesforce 或特定 Connected App 时填写。
          </div>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- <metadata-browser ref="metaBrowser" @view-code="handleBrowserViewCode" @diff-code="handleBrowserDiffCode" /> -->

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
import MonacoEditor from '@/components/MonacoEditor';
import MetadataBrowser from './MetadataBrowser';
import request from '@/utils/request';

export default {
  components: { MonacoEditor, MetadataBrowser },
  name: "Org",
  data() {
    return {
      isDiffMode: false,
      oldCodeContent: "",
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      orgList: [],
      title: "",
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: null,
        orgType: null,
        username: null
      },
      form: {},
      rules: {
        name: [{ required: true, message: "环境名称不能为空", trigger: "blur" }],
        orgType: [{ required: true, message: "环境类型不能为空", trigger: "change" }]
      },
      openCode: false,
      codeContent: "",
      previewTitle: ""
    }
  },
  created() {
    this.getList();
    this.checkAuthCallback();
  },
  methods: {
    checkAuthCallback() {
      const auth = this.$route.query.auth;
      if (auth === 'success') {
        this.$modal.msgSuccess("Salesforce 授权成功！");
        this.$router.replace({ query: {} });
        this.getList(); // 授权回来后刷新列表，更新状态
      }
    },

    getList() {
      this.loading = true
      listOrg(this.queryParams).then(response => {
        this.orgList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    handleAuth(row) {
      const orgId = row.id;
      // 调用后端获取 URL
      request({
        url: '/system/sf/authUrl',
        method: 'get',
        params: { Id: orgId }
      }).then(response => {
        const authUrl = response.data;
        if (authUrl) {
          window.location.href = authUrl;
        }
      });
    },

    /** 【新增】同步元数据类型到数据字典 */
    handleSyncDict(row) {
      if (!row.accessToken) {
        this.$modal.msgError("请先完成授权后再同步字典！");
        return;
      }

      const loading = this.$loading({
        lock: true,
        text: '正在从 Salesforce 获取元数据类型并同步到系统字典，请稍候...',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      });

      request({
        url: '/system/sf/meta/syncDict',
        method: 'get',
        params: { orgId: row.id }
      }).then(response => {
        loading.close();
        this.$modal.msgSuccess(response.msg || "同步成功！请刷新页面或在部署包中查看最新类型。");
      }).catch(() => {
        loading.close();
      });
    },

    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        id: null,
        name: null,
        orgType: 'Sandbox',
        orgId: null,
        username: null,
        customDomain: null, // 自定义域名
        clientId: null,     // 可选填
        clientSecret: null  // 可选填
      }
      this.resetForm("form")
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加Salesforce环境管理"
    },
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getOrg(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改Salesforce环境管理"
      })
    },
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
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除Salesforce环境管理编号为"' + ids + '"的数据项？').then(function () {
        return delOrg(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => { })
    },
    // 保留这些方法以防组件依赖
    handleBrowserDiffCode(data) { /* ... */ },
    handleBrowserViewCode(data) { this.previewCode(this.$refs.metaBrowser.currentOrgId, data.type, data.name); },
    previewCode(orgId, type, name) {
      // 简单保留逻辑，或者可以留空
      const loading = this.$loading({ lock: true, text: '加载中...', spinner: 'el-icon-loading', background: 'rgba(0, 0, 0, 0.7)' });
      request({
        url: '/system/sf/meta/retrieve',
        method: 'get',
        params: { orgId, type, name }
      }).then(response => {
        loading.close();
        this.codeContent = response.data;
        this.oldCodeContent = "";
        this.isDiffMode = false;
        this.previewTitle = `${type}: ${name}`;
        this.openCode = true;
      }).catch(() => loading.close());
    }
  }
}
</script>