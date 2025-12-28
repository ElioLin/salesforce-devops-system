<template>
  <el-dialog title="元数据浏览器" :visible.sync="visible" width="1000px" append-to-body :close-on-click-modal="false">
    <el-row :gutter="10" class="mb8">
      <el-col :span="4">
        <el-tag type="success" v-if="currentOrgId">当前源环境 ID: {{ currentOrgId }}</el-tag>
      </el-col>

      <el-col :span="5">
        <el-select v-model="targetOrgId" placeholder="选择目标环境(比对基准)" clearable style="width: 100%" @change="fetchList">
          <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
            :disabled="item.id === currentOrgId" />
        </el-select>
      </el-col>

      <el-col :span="5">
        <el-select v-model="queryParams.type" placeholder="请选择元数据类型" @change="handleTypeChange" style="width: 100%"
          filterable :loading="typesLoading">
          <el-option v-for="type in metadataTypeOptions" :key="type" :label="type" :value="type" />
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
      
      <el-table-column label="差异状态" align="center" width="100">
          <template slot-scope="scope">
             <el-tag size="mini" v-if="scope.row.diffStatus" :type="getDiffTagType(scope.row.diffStatus)">{{ scope.row.diffStatus }}</el-tag>
          </template>
      </el-table-column>

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
import { getMetadataTypes } from "@/api/salesforce/deployment"; // 引入获取类型的API

export default {
  name: "MetadataBrowser",
  dicts: ['sys_salesforce_metadata_type'], // 保留这个字典以防万一，但主要用动态获取的
  data() {
    return {
      visible: false,
      loading: false,
      typesLoading: false, // 类型加载中
      existMap: new Map(),
      list: [],
      total: 0,
      orgOptions: [],
      metadataTypeOptions: [], // 动态获取的类型列表
      currentOrgId: null,
      targetOrgId: null,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        orgId: null,
        type: 'ApexClass', // 默认选中
        keyword: ''
      }
    };
  },
  methods: {
    /** 打开弹窗 */
    open(orgId, targetOrgId, itemList = []) {
      this.currentOrgId = orgId;
      this.queryParams.orgId = orgId;
      this.targetOrgId = targetOrgId; 
      
      this.visible = true;
      this.existMap.clear();
      if (itemList && itemList.length > 0) {
        itemList.forEach(item => {
          this.existMap.set(item.metadataType + ':' + item.memberName, item.id);
        });
      }
      this.getOrgList();
      this.loadMetadataTypes(); // 加载所有类型
      this.handleQuery();
    },

    getOrgList() {
      request({
        url: '/salesforce/org/list',
        method: 'get',
        params: { pageNum: 1, pageSize: 100 }
      }).then(response => {
        this.orgOptions = response.rows;
      });
    },

    /** 加载元数据类型 */
    loadMetadataTypes() {
      this.typesLoading = true;
      getMetadataTypes(this.currentOrgId).then(res => {
        this.metadataTypeOptions = res.data || [];
        this.typesLoading = false;
      }).catch(err => {
        this.typesLoading = false;
        // 失败时回退到默认列表
        this.metadataTypeOptions = ['ApexClass', 'ApexTrigger', 'CustomObject', 'LightningComponentBundle'];
      });
    },

    handleQuery() {
      this.queryParams.pageNum = 1;
      this.fetchList();
    },

    handleTypeChange() {
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },

    /** 拉取列表并计算状态 */
    fetchList() {
      if (!this.queryParams.orgId) return;
      this.loading = true;

      // 1. 源环境请求
      const pSource = request({
        url: '/system/sf/meta/list',
        method: 'get',
        params: this.queryParams
      });

      // 2. 目标环境请求
      let pTarget = Promise.resolve({ rows: [] });
      if (this.targetOrgId) {
          const targetParams = { 
              orgId: this.targetOrgId,
              type: this.queryParams.type,
              pageNum: 1,
              pageSize: 10000, 
              keyword: this.queryParams.keyword 
          };
          pTarget = request({
            url: '/system/sf/meta/list',
            method: 'get',
            params: targetParams
          });
      }

      Promise.all([pSource, pTarget]).then(([resSource, resTarget]) => {
        const sourceList = resSource.rows || [];
        const targetList = resTarget.rows || [];
        this.total = resSource.total;

        const targetMap = new Map();
        targetList.forEach(item => {
            targetMap.set(item.fullName, item.lastModifiedDate);
        });

        this.list = sourceList.map(item => {
            let status = '';
            if (this.targetOrgId) {
                if (!targetMap.has(item.fullName)) {
                    status = 'New';
                } else {
                    const sourceDate = new Date(item.lastModifiedDate).getTime();
                    const targetDate = new Date(targetMap.get(item.fullName)).getTime();
                    if (sourceDate !== targetDate) {
                        status = 'Changed';
                    } else {
                        status = 'Same';
                    }
                }
            }
            return { ...item, diffStatus: status };
        });

        this.loading = false;
        this.$nextTick(() => {
          this.checkExistingRows();
        });
      }).catch(() => {
        this.loading = false;
        this.list = [];
        this.total = 0;
      });
    },

    getDiffTagType(status) {
        if (status === 'New') return 'success';
        if (status === 'Changed') return 'warning';
        if (status === 'Same') return 'info';
        return '';
    },

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

    handleSelect(selection, row) {
      const currentType = this.queryParams.type;
      const key = currentType + ':' + row.fullName;
      const isChecked = selection.indexOf(row) !== -1;
      if (isChecked) {
        this.$emit('auto-action', {
          action: 'add',
          type: currentType,
          name: row.fullName,
          key: key
        });
      } else {
        const itemId = this.existMap.get(key);
        if (itemId) {
          this.$emit('auto-action', {
            action: 'remove',
            id: itemId,
            key: key
          });
          this.existMap.delete(key);
        } else {
          this.$modal.msgWarning("未找到对应ID，移除失败，请刷新重试");
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      }
    },

    handleSelectAll(selection) {
      this.$refs.metaTable.clearSelection();
      this.$modal.msgWarning("为保证系统稳定性，暂不支持批量全选，请逐个勾选。");
      this.checkExistingRows();
    },

    updateMapAfterAdd(key, newId) {
      this.existMap.set(key, newId);
    },

    handleView(row) {
      this.$emit('view-code', {
        type: this.queryParams.type,
        name: row.fullName
      });
    },

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