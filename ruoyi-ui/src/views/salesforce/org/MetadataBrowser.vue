<template>
  <el-dialog title="元数据浏览器" :visible.sync="visible" width="1000px" append-to-body :close-on-click-modal="false">
    <el-row :gutter="10" class="mb8">
      <el-col :span="4">
        <el-tag type="success" v-if="currentOrgId">当前源环境 ID: {{ currentOrgId }}</el-tag>
      </el-col>

      <el-col :span="5">
        <el-select v-model="targetOrgId" placeholder="选择目标环境(比对基准)" clearable style="width: 100%">
          <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
            :disabled="item.id === currentOrgId" />
        </el-select>
      </el-col>

      <el-col :span="5">
        <el-select v-model="queryParams.type" placeholder="请选择元数据类型" @change="handleTypeChange" style="width: 100%"
          filterable>
          <el-option v-for="dict in dict.type.sys_salesforce_metadata_type" :key="dict.value"
            :label="dict.label + ' (' + dict.value + ')'" :value="dict.value" />
        </el-select>
      </el-col>

      <el-col :span="5">
        <el-input v-model="queryParams.keyword" placeholder="搜索文件名..." prefix-icon="el-icon-search" clearable
          @keyup.enter.native="handleQuery" />
      </el-col>

      <el-col :span="5">
        <el-button-group>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="fetchList" :loading="loading">刷新列表</el-button>
        </el-button-group>
      </el-col>
    </el-row>

    <el-table ref="metaTable" v-loading="loading" :data="list" height="600" style="width: 100%" border
      row-key="fullName" @select="handleSelect" @select-all="handleSelectAll">
      <el-table-column type="selection" width="55" align="center" />

      <el-table-column prop="fullName" label="名称" show-overflow-tooltip sortable />
      <el-table-column prop="lastModifiedByName" label="修改人" width="150" sortable />
      <el-table-column prop="lastModifiedDate" label="修改时间" width="180" sortable>
        <template slot-scope="scope">
          {{ parseTime(scope.row.lastModifiedDate) }}
        </template>
      </el-table-column>

      <el-table-column label="操作" width="150" align="center">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-view" @click="handleView(scope.row)">查看</el-button>
          <el-button size="mini" type="text" icon="el-icon-connection" :disabled="!targetOrgId"
            @click="handleDiff(scope.row)">比对</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      @pagination="fetchList" />

    <div slot="footer" class="dialog-footer">
      <el-button @click="visible = false">关 闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import request from '@/utils/request';

export default {
  name: "MetadataBrowser",
  dicts: ['sys_salesforce_metadata_type'],
  data() {
    return {
      visible: false,
      loading: false,

      // 【关键】存储已存在的映射：Key="类型:名称", Value="数据库ID" (用于移除时查找ID)
      existMap: new Map(),

      list: [],
      total: 0,
      orgOptions: [],

      currentOrgId: null,
      targetOrgId: null,

      queryParams: {
        pageNum: 1,
        pageSize: 10,
        orgId: null,
        type: 'ApexClass',
        keyword: ''
      }
    };
  },
  methods: {
    /** 打开弹窗 */
    open(orgId, itemList = []) {
      this.currentOrgId = orgId;
      this.queryParams.orgId = orgId;
      this.visible = true;

      // 1. 构建映射表，用于自动回显和后续的移除操作
      this.existMap.clear();
      if (itemList && itemList.length > 0) {
        itemList.forEach(item => {
          // Key: 类型:名称, Value: 数据库里的主键ID
          this.existMap.set(item.metadataType + ':' + item.memberName, item.id);
        });
      }

      this.getOrgList();
      this.handleQuery();
    },

    /** 获取环境列表 */
    getOrgList() {
      request({
        url: '/salesforce/org/list',
        method: 'get',
        params: { pageNum: 1, pageSize: 100 }
      }).then(response => {
        this.orgOptions = response.rows;
      });
    },

    /** 搜索 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.fetchList();
    },

    /** 切换类型 */
    handleTypeChange() {
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },

    /** 拉取列表 */
    fetchList() {
      if (!this.queryParams.orgId) return;

      this.loading = true;
      request({
        url: '/system/sf/meta/list',
        method: 'get',
        params: this.queryParams
      }).then(response => {
        this.list = response.rows;
        this.total = response.total;
        this.loading = false;

        // 【关键】数据加载完后，根据 existMap 自动给表格行打钩
        this.$nextTick(() => {
          this.checkExistingRows();
        });
      }).catch(() => {
        this.loading = false;
        this.list = [];
        this.total = 0;
      });
    },

    /** 自动回显：遍历当前页数据，如果在 existMap 中，则打勾 */
    checkExistingRows() {
      if (!this.$refs.metaTable) return;
      const currentType = this.queryParams.type;

      this.list.forEach(row => {
        const key = currentType + ':' + row.fullName;
        if (this.existMap.has(key)) {
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      });
    },

    /** 【核心逻辑】用户手动点击某一行的复选框 */
    handleSelect(selection, row) {
      const currentType = this.queryParams.type;
      const key = currentType + ':' + row.fullName;

      // 判断 row 是否在 selection 数组中：
      // 在数组里 -> 说明刚才的操作是“勾选” (添加)
      // 不在数组里 -> 说明刚才的操作是“取消勾选” (移除)
      const isChecked = selection.indexOf(row) !== -1;

      if (isChecked) {
        // --- 执行添加 ---
        this.$emit('auto-action', {
          action: 'add',
          type: currentType,
          name: row.fullName,
          key: key // 把 Key 传回去，方便父组件添加成功后传回 ID
        });
      } else {
        // --- 执行移除 ---
        const itemId = this.existMap.get(key);
        if (itemId) {
          this.$emit('auto-action', {
            action: 'remove',
            id: itemId,
            key: key
          });
          this.existMap.delete(key); // 立即从本地 Map 清除
        } else {
          this.$modal.msgWarning("未找到对应ID，移除失败，请刷新重试");
          // 如果移除失败，把勾选状态恢复回去
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      }
    },

    /** 处理全选 (建议禁用，防止误操作导致并发请求过多) */
    handleSelectAll(selection) {
      this.$refs.metaTable.clearSelection();
      this.$modal.msgWarning("为保证系统稳定性，暂不支持批量全选，请逐个勾选。");
      this.checkExistingRows(); // 恢复之前的勾选状态
    },

    /** 【回调】父组件添加成功后，调用此方法把新生成的ID存回来 */
    updateMapAfterAdd(key, newId) {
      this.existMap.set(key, newId);
    },

    /** 查看代码 */
    handleView(row) {
      this.$emit('view-code', {
        type: this.queryParams.type,
        name: row.fullName
      });
    },

    /** 比对代码 */
    handleDiff(row) {
      if (!this.targetOrgId) {
        this.$modal.msgError("请先在顶部选择一个目标环境！");
        return;
      }
      this.$emit('diff-code', {
        sourceOrgId: this.currentOrgId,
        targetOrgId: this.targetOrgId,
        type: this.queryParams.type,
        name: row.fullName
      });
    }
  }
};
</script>

<style scoped>
.mb8 {
  margin-bottom: 20px;
}
</style>