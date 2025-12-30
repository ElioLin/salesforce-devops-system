<template>
  <el-dialog title="元数据浏览器" :visible.sync="visible" width="85%" append-to-body :close-on-click-modal="false">
    <el-row :gutter="10" class="mb8">
      <el-col :span="4">
        <el-tag type="success" v-if="currentOrgId">当前源环境 ID: {{ currentOrgId }}</el-tag>
      </el-col>

      <el-col :span="6">
        <el-select v-model="targetOrgId" placeholder="选择目标环境(比对基准)" clearable style="width: 100%" @change="fetchList">
          <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
            :disabled="item.id === currentOrgId" />
        </el-select>
      </el-col>

      <el-col :span="6">
        <el-select v-model="queryParams.type" placeholder="请选择元数据类型" @change="handleTypeChange" style="width: 100%"
          filterable :loading="typesLoading">
          <el-option v-for="dict in dict.type.sys_salesforce_metadata_type" :key="dict.value" :label="dict.label"
            :value="dict.value" />
        </el-select>
      </el-col>

      <el-col :span="8" style="text-align: right;">
        <el-button-group>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">刷新/搜索</el-button>
          <el-button type="warning" icon="el-icon-download" @click="handleSync" :loading="syncLoading">同步</el-button>
        </el-button-group>
      </el-col>
    </el-row>

    <el-table ref="metaTable" v-loading="loading" :data="list" height="600" style="width: 100%" border
      row-key="fullName" @select="handleSelect" @select-all="handleSelectAll">
      <el-table-column type="selection" width="55" align="center" />
      
      <el-table-column prop="fullName">
        <template slot="header" slot-scope="scope">
            <div class="custom-header">
                <span>名称</span>
                <el-input 
                    v-model="nameFilter" 
                    size="mini" 
                    placeholder="输入自动搜索..." 
                    clearable 
                    @input="handleInputSearch" 
                    @click.native.stop 
                />
            </div>
        </template>
      </el-table-column>

      <el-table-column label="所属对象" width="180">
        <template slot="header" slot-scope="scope">
            <div class="custom-header">
                <span>所属对象</span>
                <el-input 
                    v-model="parentFilter" 
                    size="mini" 
                    placeholder="输入自动搜索..." 
                    clearable 
                    @input="handleInputSearch" 
                    @click.native.stop 
                />
            </div>
        </template>
        <template slot-scope="scope">{{ getParentName(scope.row.fullName) }}</template>
      </el-table-column>

      <el-table-column label="差异状态" align="center" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" v-if="scope.row.diffStatus" :type="getDiffTagType(scope.row.diffStatus)">{{
            scope.row.diffStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastModifiedByName" label="修改人" width="150" sortable />
      <el-table-column prop="lastModifiedDate" label="修改时间" width="180" sortable>
        <template slot-scope="scope">{{ parseTime(scope.row.lastModifiedDate) }}</template>
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
import { getMetadataTypes } from "@/api/salesforce/deployment";

export default {
  name: "MetadataBrowser",
  dicts: ['sys_salesforce_metadata_type'],
  data() {
    return {
      visible: false,
      loading: false,
      syncLoading: false,
      typesLoading: false,
      existMap: new Map(),
      list: [],
      total: 0,
      orgOptions: [],
      metadataTypeOptions: [],
      currentOrgId: null,
      targetOrgId: null,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        orgId: null,
        type: 'ApexClass',
        keyword: ''
      },
      // 【新增】用于绑定两个搜索框的变量
      nameFilter: '',
      parentFilter: '',
      // 防抖定时器
      debounceTimer: null
    };
  },
  methods: {
    // ... 原有辅助方法 ...
    getDictLabel(value) {
        if (!value) return '';
        const datas = this.dict.type.sys_salesforce_metadata_type;
        if (datas) {
            const found = datas.find(item => item.value === value);
            if (found) { return found.label; }
        }
        return value;
    },
    getParentName(name) {
      if (name && name.includes('.')) return name.split('.')[0];
      return '-';
    },

    open(orgId, targetOrgId, itemList = []) {
      this.currentOrgId = orgId;
      this.queryParams.orgId = orgId;
      this.targetOrgId = targetOrgId;
      this.visible = true;
      this.existMap.clear();
      // 重置搜索框
      this.nameFilter = '';
      this.parentFilter = '';
      this.queryParams.keyword = '';
      
      if (itemList && itemList.length > 0) {
        itemList.forEach(item => {
          this.existMap.set(item.metadataType + ':' + item.memberName, item.id);
        });
      }
      this.getOrgList();
      this.loadMetadataTypes();
      this.handleQuery();
    },

    getOrgList() {
      request({ url: '/salesforce/org/list', method: 'get', params: { pageNum: 1, pageSize: 100 } }).then(res => { this.orgOptions = res.rows; });
    },
    loadMetadataTypes() {
      this.typesLoading = true;
      getMetadataTypes(this.currentOrgId).then(res => {
        this.metadataTypeOptions = res.data || [];
        this.typesLoading = false;
      }).catch(err => {
        this.typesLoading = false;
        this.metadataTypeOptions = ['ApexClass', 'ApexTrigger', 'CustomObject', 'CustomField'];
      });
    },

    handleQuery() {
      this.queryParams.pageNum = 1;
      this.fetchList();
    },

    handleTypeChange() {
      // 切换类型时，通常也需要清空搜索框
      this.nameFilter = '';
      this.parentFilter = '';
      this.queryParams.keyword = '';
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },

    /** 【新增】处理输入搜索（含防抖逻辑） */
    handleInputSearch() {
      // 清除上一次的定时器
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }
      
      // 设置新的定时器 (500ms 后执行)
      this.debounceTimer = setTimeout(() => {
        // 策略：优先使用名称过滤，如果名称为空，则使用对象过滤
        // 由于后端只支持一个 keyword，我们无法同时精准匹配两个字段，
        // 但对于 Salesforce，搜索对象名其实就是搜索 fullName 的前缀，所以这样映射是合理的。
        if (this.nameFilter) {
            this.queryParams.keyword = this.nameFilter;
        } else if (this.parentFilter) {
            this.queryParams.keyword = this.parentFilter;
        } else {
            this.queryParams.keyword = '';
        }
        
        this.queryParams.pageNum = 1;
        this.fetchList();
      }, 500);
    },

    handleSync() {
      if (!this.queryParams.type) {
        this.$modal.msgWarning("请先选择元数据类型");
        return;
      }
      this.syncLoading = true;
      request({
        url: '/system/sf/meta/sync',
        method: 'get',
        params: { orgId: this.currentOrgId, type: this.queryParams.type }
      }).then(res => {
        this.syncLoading = false;
        this.$modal.msgSuccess(res.msg);
        this.fetchList();
      }).catch(() => {
        this.syncLoading = false;
      });
    },

    fetchList() {
      if (!this.queryParams.orgId) return;
      this.loading = true;

      const pSource = request({
        url: '/system/sf/meta/list',
        method: 'get',
        params: this.queryParams
      });

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
              if (sourceDate !== targetDate) status = 'Changed';
              else status = 'Same';
            }
          }
          return { ...item, diffStatus: status };
        });

        this.loading = false;
        this.$nextTick(() => { this.checkExistingRows(); });
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
        this.$emit('auto-action', { action: 'add', type: currentType, name: row.fullName, key: key });
      } else {
        const itemId = this.existMap.get(key);
        if (itemId) {
          this.$emit('auto-action', { action: 'remove', id: itemId, key: key });
          this.existMap.delete(key);
        } else {
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      }
    },
    handleSelectAll(selection) {
      this.$refs.metaTable.clearSelection();
      this.$modal.msgWarning("为保证系统稳定性，暂不支持批量全选，请逐个勾选。");
      this.checkExistingRows();
    },
    updateMapAfterAdd(key, newId) { this.existMap.set(key, newId); },
    handleView(row) { this.$emit('view-code', { type: this.queryParams.type, name: row.fullName }); },
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

/* 复用自定义表头样式 */
.custom-header {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    line-height: 1.2;
    padding-bottom: 5px;
}
.custom-header span {
    margin-bottom: 5px;
}
.custom-header .el-input {
    width: 100%;
    font-weight: normal;
}
</style>