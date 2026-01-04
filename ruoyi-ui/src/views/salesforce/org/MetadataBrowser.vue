<template>
  <div class="browser-container" v-loading="loading">
    <div class="filter-container">
      <el-row :gutter="15">
        <el-col :span="6">
          <div class="filter-item">
            <span class="label">当前源环境</span>
            <el-tag type="success" v-if="sourceOrgId" effect="dark" class="w-100 text-center">
              <i class="el-icon-office-building"></i> {{ currentOrgName }}
            </el-tag>
            <span v-else class="text-gray">未指定源环境</span>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="filter-item">
            <span class="label">比对基准环境</span>
            <el-select v-model="localTargetOrgId" placeholder="选择用于比对的环境" clearable class="w-100" @change="fetchList">
              <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
                :disabled="item.id === sourceOrgId" />
            </el-select>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="filter-item">
            <span class="label">元数据类型</span>
            <el-select v-model="queryParams.type" placeholder="请选择类型" @change="handleTypeChange" class="w-100"
              filterable :loading="typesLoading">
              <el-option v-for="dict in dict.type.sys_salesforce_metadata_type" :key="dict.value" :label="dict.label"
                :value="dict.value" />
            </el-select>
          </div>
        </el-col>
        <el-col :span="6" class="text-right">
          <div class="filter-item">
            <span class="label">&nbsp;</span>
            <el-button-group>
              <el-button type="primary" icon="el-icon-refresh" @click="handleQuery">刷新列表</el-button>
              <el-button type="warning" icon="el-icon-download" @click="handleSync"
                :loading="syncLoading">强制同步</el-button>
            </el-button-group>
          </div>
        </el-col>
      </el-row>
    </div>

    <el-table ref="metaTable" :data="filteredList" style="width: 100%" border stripe highlight-current-row
      row-key="fullName" @select="handleSelect" @select-all="handleSelectAll">

      <el-table-column type="selection" width="50" align="center" :selectable="checkSelectable" />

      <el-table-column prop="fullName" label="元数据名称" min-width="260">
        <template slot="header" slot-scope="scope">
          <div class="custom-header">
            <span>元数据名称</span>
            <div style="display: flex; width: 100%">
              <el-select v-model="nameFilterOp" size="mini" style="width: 100px; margin-right: 5px;"
                @change="handleInputSearch" @click.native.stop>
                <el-option label="包含" value="contains" />
                <el-option label="不包含" value="not_contains" />
                <el-option label="等于" value="equals" />
              </el-select>
              <el-input v-model="nameFilter" size="mini" placeholder="输入名称筛选..." clearable @input="handleInputSearch"
                prefix-icon="el-icon-search" @click.native.stop />
            </div>
          </div>
        </template>
        <template slot-scope="scope">
          {{ getShortName(scope.row.fullName) }}
        </template>
      </el-table-column>

      <el-table-column label="所属对象" width="240">
        <template slot="header" slot-scope="scope">
          <div class="custom-header">
            <span>所属对象</span>
            <div style="display: flex; width: 100%">
              <el-select v-model="parentFilterOp" size="mini" style="width: 100px; margin-right: 5px;"
                @change="handleInputSearch" @click.native.stop>
                <el-option label="包含" value="contains" />
                <el-option label="不包含" value="not_contains" />
                <el-option label="等于" value="equals" />
              </el-select>
              <el-input v-model="parentFilter" size="mini" placeholder="输入对象筛选..." clearable @input="handleInputSearch"
                prefix-icon="el-icon-search" @click.native.stop />
            </div>
          </div>
        </template>
        <template slot-scope="scope">{{ getParentName(scope.row.fullName) }}</template>
      </el-table-column>

      <el-table-column prop="lastModifiedByName" label="修改人" width="140" show-overflow-tooltip />

      <el-table-column prop="lastModifiedDate" label="修改时间" width="160" sortable>
        <template slot-scope="scope">{{ parseTime(scope.row.lastModifiedDate) }}</template>
      </el-table-column>

      <el-table-column label="差异状态" align="center" width="130">
        <template slot="header" slot-scope="scope">
          <div class="custom-header">
            <span>差异状态</span>
            <el-select v-model="diffFilter" size="mini" placeholder="全部" clearable @click.native.stop>
              <el-option v-for="status in existingDiffOptions" :key="status" :label="status" :value="status" />
            </el-select>
          </div>
        </template>
        <template slot-scope="scope">
          <el-tag size="mini" v-if="scope.row.diffStatus" :type="getDiffTagType(scope.row.diffStatus)" effect="light">
            {{ scope.row.diffStatus }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="140" align="center" fixed="right">
        <template slot-scope="scope">
          <!-- <el-button size="mini" type="text" icon="el-icon-view" @click="handleView(scope.row)">代码</el-button> -->
          <el-button size="mini" type="text" icon="el-icon-connection" :disabled="!localTargetOrgId"
            @click="handleDiff(scope.row)">比对</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <div class="server-count-info">
        <i class="el-icon-cloudy"></i> 服务端加载数: <b>{{ total }}</b>
      </div>

      <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
        :page-sizes="[50, 100, 200, 300, 500]" @pagination="fetchList" />
    </div>

  </div>
</template>

<script>
import request from '@/utils/request';
import { getMetadataTypes } from "@/api/salesforce/deployment";

export default {
  name: "MetadataBrowser",
  dicts: ['sys_salesforce_metadata_type'],
  props: {
    sourceOrgId: { type: String, default: null },
    targetOrgId: { type: String, default: null },
    initialItemList: { type: Array, default: () => [] },
    disabled: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      syncLoading: false,
      typesLoading: false,
      existMap: new Map(),
      mapUpdateTrigger: 0,
      list: [],
      total: 0,
      orgOptions: [],
      metadataTypeOptions: [],
      localTargetOrgId: null,
      queryParams: {
        pageNum: 1,
        pageSize: 50,
        orgId: null,
        type: 'ApexClass',
        keyword: ''
      },
      nameFilter: '',
      // 【新增】筛选操作符默认值
      nameFilterOp: 'contains',
      parentFilter: '',
      parentFilterOp: 'contains',
      diffFilter: '',
      debounceTimer: null
    };
  },
  computed: {
    currentOrgName() {
      if (!this.sourceOrgId) return '';
      const org = this.orgOptions.find(item => item.id === this.sourceOrgId);
      return org ? org.name : `ID: ${this.sourceOrgId}`;
    },
    existingDiffOptions() {
      if (!this.list || this.list.length === 0) return [];
      const statusSet = new Set(
        this.list.map(item => item.diffStatus).filter(s => s)
      );
      return Array.from(statusSet).sort();
    },
    /**
         * 【修改】前端二次过滤逻辑
         * 1. 支持 contains, not_contains, equals
         * 2. 【优化】名称匹配逻辑使用 getShortName() 处理后的短名称进行比对
         */
    filteredList() {
      let result = this.list;

      // 差异状态过滤
      if (this.diffFilter) {
        result = result.filter(item => item.diffStatus === this.diffFilter);
      }

      // 名称过滤
      if (this.nameFilter) {
        const filter = this.nameFilter.toLowerCase();
        const op = this.nameFilterOp;
        result = result.filter(item => {
          // 【关键修改】这里使用 getShortName 获取显示的短名称进行比对
          const val = this.getShortName(item.fullName || '').toLowerCase();

          if (op === 'equals') return val === filter;
          if (op === 'not_contains') return !val.includes(filter);
          return val.includes(filter); // contains
        });
      }

      // 父对象过滤
      if (this.parentFilter) {
        const filter = this.parentFilter.toLowerCase();
        const op = this.parentFilterOp;
        result = result.filter(item => {
          const val = this.getParentName(item.fullName).toLowerCase();
          if (op === 'equals') return val === filter;
          if (op === 'not_contains') return !val.includes(filter);
          return val.includes(filter); // contains
        });
      }

      return result;
    },
    // 以下未使用属性保留或删除均可
    selectedCurrentTypeCount() {
      const _ = this.mapUpdateTrigger;
      if (!this.existMap.size) return 0;
      let count = 0;
      const prefix = this.queryParams.type + ':';
      for (let key of this.existMap.keys()) {
        if (key.startsWith(prefix)) count++;
      }
      return count;
    },
    deploymentTotalCount() {
      const _ = this.mapUpdateTrigger;
      return this.existMap.size;
    }
  },
  watch: {
    sourceOrgId: {
      handler(val) {
        if (val) {
          this.queryParams.orgId = val;
          this.initBrowser();
        }
      },
      immediate: true
    },
    targetOrgId: {
      handler(val) {
        this.localTargetOrgId = val;
      },
      immediate: true
    },
    initialItemList: {
      handler(val) {
        this.syncExistMap(val);
      },
      deep: true,
      immediate: true
    }
  },
  created() {
    this.getOrgList();
  },
  methods: {
    initBrowser() {
      this.loadMetadataTypes();
      this.fetchList();
    },
    getShortName(name) {
      if (name && name.includes('.')) {
        return name.substring(name.indexOf('.') + 1);
      }
      return name;
    },
    syncExistMap(itemList) {
      this.existMap.clear();
      if (itemList && itemList.length > 0) {
        itemList.forEach(item => {
          this.existMap.set(item.metadataType + ':' + item.memberName, item.id);
        });
      }
      this.mapUpdateTrigger++;
      this.$nextTick(() => {
        this.checkExistingRows();
      });
    },
    updateMapAfterAdd(key, newId) {
      this.existMap.set(key, newId);
      this.mapUpdateTrigger++;
    },
    checkSelectable(row) {
      return !this.disabled;
    },
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
    getOrgList() {
      request({ url: '/salesforce/org/list', method: 'get', params: { pageNum: 1, pageSize: 100 } }).then(res => { this.orgOptions = res.rows; });
    },
    loadMetadataTypes() {
      if (!this.sourceOrgId) return;
      this.typesLoading = true;
      getMetadataTypes(this.sourceOrgId).then(res => {
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
      this.nameFilter = '';
      this.nameFilterOp = 'contains';
      this.parentFilter = '';
      this.parentFilterOp = 'contains';
      this.diffFilter = '';
      this.queryParams.keyword = '';
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },
    // 【修改】搜索框输入逻辑
    handleInputSearch() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }
      this.debounceTimer = setTimeout(() => {
        let text = '';
        let isNotContains = false;

        // 优先使用名称过滤
        if (this.nameFilter) {
          text = this.nameFilter;
          if (this.nameFilterOp === 'not_contains') isNotContains = true;
        } else if (this.parentFilter) {
          text = this.parentFilter;
          if (this.parentFilterOp === 'not_contains') isNotContains = true;
        }

        // 核心逻辑：如果是“不包含”，我们不能传给后端，因为后端通常是 LIKE 查询
        // 所以如果不包含，我们传空字符串（查全量），然后靠前端 filteredList 进行过滤
        if (isNotContains) {
          this.queryParams.keyword = '';
        } else {
          this.queryParams.keyword = text;
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
        params: { orgId: this.sourceOrgId, type: this.queryParams.type }
      }).then(res => {
        this.syncLoading = false;
        this.$modal.msgSuccess(res.msg || "同步成功");
        this.fetchList();
      }).catch(err => {
        this.syncLoading = false;
        console.error("Sync error:", err);
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
      if (this.localTargetOrgId) {
        const targetParams = {
          orgId: this.localTargetOrgId,
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
          if (item.fullName) {
            targetMap.set(item.fullName.toLowerCase(), item.lastModifiedDate);
          }
        });
        this.list = sourceList.map(item => {
          let status = '';
          if (this.localTargetOrgId) {
            const targetDateStr = targetMap.get(item.fullName.toLowerCase());
            if (!targetDateStr) {
              status = 'New';
            } else {
              const sourceTime = new Date(item.lastModifiedDate).getTime();
              const targetTime = new Date(targetDateStr).getTime();
              if (Math.abs(sourceTime - targetTime) < 2000) {
                status = 'Same';
              } else {
                status = 'Changed';
              }
            }
          }
          return { ...item, diffStatus: status };
        });
        this.loading = false;
        this.$nextTick(() => {
          this.checkExistingRows();
          if (this.$refs.metaTable && this.$refs.metaTable.bodyWrapper) {
            this.$refs.metaTable.bodyWrapper.scrollTop = 0;
          }
        });
      }).catch(err => {
        this.loading = false;
        this.list = [];
        this.total = 0;
        console.error("Fetch list error:", err);
        this.$modal.msgError("元数据加载失败，请尝试刷新或检查网络");
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
      this.$refs.metaTable.clearSelection();
      this.list.forEach(row => {
        const key = currentType + ':' + row.fullName;
        if (this.existMap.has(key)) {
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      });
    },
    handleSelect(selection, row) {
      if (this.disabled) return;
      const currentType = this.queryParams.type;
      const key = currentType + ':' + row.fullName;
      const isChecked = selection.indexOf(row) !== -1;
      if (isChecked) {
        this.existMap.set(key, 'PENDING');
        this.mapUpdateTrigger++;
        this.$emit('auto-action', { action: 'add', type: currentType, name: row.fullName, key: key });
      } else {
        const itemId = this.existMap.get(key);
        if (itemId) {
          this.$emit('auto-action', { action: 'remove', id: itemId, key: key });
          this.existMap.delete(key);
          this.mapUpdateTrigger++;
        }
      }
    },
    handleSelectAll(selection) {
      if (this.disabled) return;
      const currentType = this.queryParams.type;
      const isSelectAll = selection.length > 0;
      if (isSelectAll) {
        const batchItems = [];
        selection.forEach(row => {
          const key = currentType + ':' + row.fullName;
          if (!this.existMap.has(key)) {
            batchItems.push({ type: currentType, name: row.fullName, key: key });
            this.existMap.set(key, 'PENDING');
          }
        });
        if (batchItems.length > 0) {
          this.mapUpdateTrigger++;
          this.$emit('auto-action', { action: 'batch-add', items: batchItems });
        }
      } else {
        const batchIds = [];
        const batchKeys = [];
        this.filteredList.forEach(row => {
          const key = currentType + ':' + row.fullName;
          if (this.existMap.has(key)) {
            const itemId = this.existMap.get(key);
            if (itemId && itemId !== 'PENDING') {
              batchIds.push(itemId);
            }
            batchKeys.push(key);
          }
        });
        batchKeys.forEach(k => this.existMap.delete(k));
        this.mapUpdateTrigger++;
        if (batchIds.length > 0) {
          this.$emit('auto-action', { action: 'batch-remove', ids: batchIds });
        }
      }
    },
    handleView(row) {
      this.$emit('view-code', { type: this.queryParams.type, name: row.fullName });
    },
    handleDiff(row) {
      if (!this.localTargetOrgId) {
        this.$modal.msgError("请先选择比对基准环境！");
        return;
      }
      this.$emit('diff-code', {
        sourceOrgId: this.sourceOrgId,
        targetOrgId: this.localTargetOrgId,
        type: this.queryParams.type,
        name: row.fullName
      });
    }
  }
};
</script>

<style scoped>
.browser-container {
  padding: 10px 0;
}

.filter-container {
  padding: 0 5px 15px 5px;
  border-bottom: 1px solid #ebeef5;
}

.filter-item {
  display: flex;
  flex-direction: column;
}

.filter-item .label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 5px;
  font-weight: 600;
}

.text-right {
  text-align: right;
}

.text-gray {
  color: #c0c4cc;
  font-size: 12px;
  line-height: 24px;
}

.mt-20 {
  margin-top: 20px;
}

.w-100 {
  width: 100%;
}

.text-primary {
  color: #409EFF;
}

.text-warning {
  color: #E6A23C;
}

.text-success {
  color: #67C23A;
}

.text-info {
  color: #909399;
}

.custom-header {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 2px 0;
}

.custom-header span {
  font-size: 13px;
  margin-bottom: 6px;
  color: #606266;
  font-weight: 600;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-top: 20px;
}

.server-count-info {
  margin-right: 20px;
  font-size: 13px;
  color: #606266;
}
</style>