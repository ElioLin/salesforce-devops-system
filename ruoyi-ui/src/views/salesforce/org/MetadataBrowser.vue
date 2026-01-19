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

      <div class="smart-filter-bar mt-10">
        <div class="filter-group">
          <span class="filter-label"><i class="el-icon-time"></i> 修改时间:</span>
          <el-radio-group v-model="dateFilter" size="mini" @change="handleSmartFilterChange">
            <el-radio-button label="all">全部</el-radio-button>
            <el-radio-button label="today">今天</el-radio-button>
            <el-radio-button label="3days">近3天</el-radio-button>
            <el-radio-button label="7days">近7天</el-radio-button>
          </el-radio-group>
        </div>

        <div class="filter-group ml-20">
          <el-checkbox v-model="onlyDiff" @change="handleSmartFilterChange" border size="mini">
            <i class="el-icon-warning-outline"></i> 仅显示差异项 (New/Changed)
          </el-checkbox>
        </div>
      </div>
    </div>

    <el-table ref="metaTable" :data="filteredList" style="width: 100%" border stripe highlight-current-row
      row-key="fullName" @select="handleSelect" @select-all="handleSelectAll" height="500px">

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
          <el-button size="mini" type="text" icon="el-icon-connection" :disabled="!localTargetOrgId"
            @click="handleDiff(scope.row)">比对</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <div class="server-count-info">
        <i class="el-icon-cloudy"></i> 服务端总数: <b>{{ total }}</b>
        <span class="ml-10" v-if="filteredList.length !== list.length">
          (筛选后: <b class="text-primary">{{ filteredList.length }}</b>)
        </span>
      </div>

      <pagination v-show="total > 0" :total="filteredList.length" :page.sync="queryParams.pageNum"
        :limit.sync="queryParams.pageSize" :page-sizes="[50, 100, 200, 300, 500]" @pagination="handlePagination" />
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
      list: [], // 原始全量数据
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
      // 搜索栏字段
      nameFilter: '',
      nameFilterOp: 'contains',
      parentFilter: '',
      parentFilterOp: 'contains',
      diffFilter: '',

      // 【新增 1.3】智能筛选字段
      dateFilter: 'all', // all, today, 3days, 7days
      onlyDiff: false,   // 仅显示差异

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
     * 【核心优化 1.3】全能筛选列表 (Smart Filtered List)
     * 整合了名称、父对象、差异状态、时间范围的所有过滤逻辑
     */
    filteredList() {
      let result = this.list;

      // 1. 差异状态过滤 (Diff Filter)
      if (this.diffFilter) {
        result = result.filter(item => item.diffStatus === this.diffFilter);
      }

      // 2. 仅显示差异复选框 (Smart Diff Toggle)
      if (this.onlyDiff) {
        result = result.filter(item => ['New', 'Changed'].includes(item.diffStatus));
      }

      // 3. 时间范围过滤 (Smart Date Filter)
      if (this.dateFilter !== 'all') {
        const now = new Date();
        const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
        const oneDay = 24 * 60 * 60 * 1000;

        result = result.filter(item => {
          if (!item.lastModifiedDate) return false;
          const itemTime = new Date(item.lastModifiedDate).getTime();

          if (this.dateFilter === 'today') {
            return itemTime >= todayStart;
          } else if (this.dateFilter === '3days') {
            return itemTime >= (todayStart - 2 * oneDay);
          } else if (this.dateFilter === '7days') {
            return itemTime >= (todayStart - 6 * oneDay);
          }
          return true;
        });
      }

      // 4. 名称过滤 (Name Filter)
      if (this.nameFilter) {
        const filter = this.nameFilter.toLowerCase();
        const op = this.nameFilterOp;
        result = result.filter(item => {
          const val = this.getShortName(item.fullName || '').toLowerCase();
          if (op === 'equals') return val === filter;
          if (op === 'not_contains') return !val.includes(filter);
          return val.includes(filter);
        });
      }

      // 5. 父对象过滤 (Parent Filter)
      if (this.parentFilter) {
        const filter = this.parentFilter.toLowerCase();
        const op = this.parentFilterOp;
        result = result.filter(item => {
          const val = this.getParentName(item.fullName).toLowerCase();
          if (op === 'equals') return val === filter;
          if (op === 'not_contains') return !val.includes(filter);
          return val.includes(filter);
        });
      }

      // 前端分页处理：filteredList 返回的是全部符合条件的数据，
      // 实际表格显示需要再切片，这里我们简化处理，
      // 因为 el-table 对几百条数据支持良好。如果数据量过大，建议下方 pagedList 处理
      return result;
    },
    // 【新增】前端分页数据 (如果想支持前端真分页)
    pagedList() {
      const start = (this.queryParams.pageNum - 1) * this.queryParams.pageSize;
      const end = start + this.queryParams.pageSize;
      return this.filteredList.slice(start, end);
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
        if (this.sourceOrgId) {
          this.fetchList();
        }
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
      // 重置智能筛选
      this.dateFilter = 'all';
      this.onlyDiff = false;

      this.queryParams.keyword = '';
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },
    // 【新增 1.3】处理智能筛选变更
    handleSmartFilterChange() {
      this.queryParams.pageNum = 1; // 重置页码
      // 筛选逻辑全在 computed: filteredList 中，这里只需触发视图更新
    },
    handleInputSearch() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }
      this.debounceTimer = setTimeout(() => {
        let text = '';
        let isNotContains = false;

        if (this.nameFilter) {
          text = this.nameFilter;
          if (this.nameFilterOp === 'not_contains') isNotContains = true;
        } else if (this.parentFilter) {
          text = this.parentFilter;
          if (this.parentFilterOp === 'not_contains') isNotContains = true;
        }

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

      // 1. 获取源环境列表
      const pSource = request({
        url: '/system/sf/meta/list',
        method: 'get',
        params: this.queryParams
      });

      // 2. 获取目标环境列表
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
        // 这里 total 暂存服务端返回的总数，实际显示用 filteredList.length
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
            const itemKey = item.fullName.toLowerCase();
            const targetDateStr = targetMap.get(itemKey);

            if (!targetDateStr) {
              status = 'New';
            } else {
              const sourceTime = new Date(item.lastModifiedDate).getTime();
              const targetTime = new Date(targetDateStr).getTime();

              if (sourceTime > targetTime) {
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
          if (this.$refs.metaTable && this.$refs.metaTable.bodyWrapper) {
            this.$refs.metaTable.bodyWrapper.scrollTop = 0;
          }
        });
      }).catch(err => {
        this.loading = false;
        this.list = [];
        this.total = 0;
        console.error("Fetch list error:", err);
        this.$modal.msgError("元数据加载失败，请尝试点击【刷新列表】重试");
      });
    },
    // 【修改】由于我们在前端做了强大的 filteredList，这里的分页需要伪造一下
    // 其实对于 el-table，只要数据在 filteredList 里，它会自动渲染
    // 但为了配合底部的 pagination 组件，我们需要处理页码事件
    handlePagination() {
      // 由于是前端分页/过滤，其实不需要重新请求 fetchList，
      // 但这里 pagination 组件 emit 的是 fetchList? 
      // 不，我们在 template 里改为了 @pagination="handlePagination"

      // 这里的逻辑：其实不需要做太多，因为 pagedList 计算属性依赖 pageNum
      // 只要 pageNum 变了，Table data (如果是用 pagedList) 就会变
      // 但目前 Table :data="filteredList"，意味着是“一页显示所有筛选结果”
      // 如果想做前端分页，需要把 el-table :data 改为 pagedList

      // 建议：为了简单直观，智能筛选模式下，直接展示所有结果 (filteredList)，
      // 分页组件仅作为数据量展示，或者可以保留 fetchList 以支持服务端分页(如果未来需要)
      // 在此代码中，我保持 Table :data="filteredList"，即“筛选即所得，不分页展示”
      // 这样体验最好，因为用户就是为了找那几个文件。

      // 如果数据量确实很大(>500)，建议 Table data 切回 pagedList
      // 这里演示全量展示逻辑：
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
      // 这里需要遍历 filteredList 还是 list? 
      // 遍历 list 可以确保选中状态在筛选时依然被计算，但 table 只渲染 filteredList
      // 所以这一步主要是 UI 回显
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
        this.$emit('auto-action', { action: 'add', type: currentType, name: row.fullName, key: key, diffStatus: row.diffStatus });
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

      // 注意：全选时，只选择当前 filteredList 里的项，还是所有 list?
      // 通常用户筛选后全选，只期望选择筛选出来的
      const targetList = this.filteredList;

      if (isSelectAll) {
        const batchItems = [];
        targetList.forEach(row => {
          const key = currentType + ':' + row.fullName;
          if (!this.existMap.has(key)) {
            batchItems.push({ type: currentType, name: row.fullName, key: key, diffStatus: row.diffStatus });
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
        targetList.forEach(row => {
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
        targetOrgId: this.localTargetOrgId, // 这里传出了用户在下拉框选的环境ID
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

/* 【优化 1.3】智能筛选栏样式 */
.smart-filter-bar {
  display: flex;
  align-items: center;
  background-color: #f8f9fa;
  padding: 8px 10px;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
}

.filter-group {
  display: flex;
  align-items: center;
}

.filter-label {
  font-size: 13px;
  color: #606266;
  margin-right: 10px;
  font-weight: 500;
}

.text-right {
  text-align: right;
}

.text-gray {
  color: #c0c4cc;
  font-size: 12px;
  line-height: 24px;
}

.mt-10 {
  margin-top: 10px;
}

.mt-20 {
  margin-top: 20px;
}

.ml-10 {
  margin-left: 10px;
}

.ml-20 {
  margin-left: 20px;
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