<template>
  <el-dialog title="元数据浏览器" :visible.sync="visible" width="90%" append-to-body :close-on-click-modal="false" top="5vh">
    <div class="filter-container">
      <el-row :gutter="15">
        <el-col :span="5">
          <div class="filter-item">
            <span class="label">源环境</span>
            <el-tag type="success" v-if="currentOrgId" effect="dark" class="w-100 text-center">
              <i class="el-icon-office-building"></i> {{ currentOrgName }}
            </el-tag>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="filter-item">
            <span class="label">目标环境 (基准)</span>
            <el-select v-model="targetOrgId" placeholder="选择用于比对的环境" clearable class="w-100" @change="fetchList">
              <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id"
                :disabled="item.id === currentOrgId" />
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
        <el-col :span="7" class="text-right">
          <el-button-group class="mt-20">
            <el-button type="primary" icon="el-icon-refresh" @click="handleQuery">刷新列表</el-button>
            <el-button type="warning" icon="el-icon-download" @click="handleSync"
              :loading="syncLoading">强制同步</el-button>
          </el-button-group>
        </el-col>
      </el-row>
    </div>

    <div class="stats-panel">
      <el-row :gutter="0">
        <el-col :span="6" class="stat-item">
          <div class="stat-label">服务端加载数</div>
          <div class="stat-value text-primary">
            <i class="el-icon-cloudy"></i> {{ total }}
          </div>
          <div class="stat-desc">符合搜索条件的远程总数</div>
        </el-col>
        <el-col :span="6" class="stat-item">
          <div class="stat-label">当前列表显示</div>
          <div class="stat-value text-warning">
            {{ filteredList.length }}
          </div>
          <div class="stat-desc">经过本地筛选(如差异)后的数量</div>
        </el-col>
        <el-col :span="6" class="stat-item border-left">
          <div class="stat-label">已选 (当前类型)</div>
          <div class="stat-value text-success">
            <i class="el-icon-check"></i> {{ selectedCurrentTypeCount }}
          </div>
          <div class="stat-desc">类型: {{ queryParams.type }}</div>
        </el-col>
        <el-col :span="6" class="stat-item">
          <div class="stat-label">部署包总数</div>
          <div class="stat-value text-info">
            {{ deploymentTotalCount }}
          </div>
          <div class="stat-desc">所有类型元数据总和</div>
        </el-col>
      </el-row>
    </div>

    <el-table ref="metaTable" v-loading="loading" :data="filteredList" height="500" style="width: 100%" border stripe
      highlight-current-row row-key="fullName" @select="handleSelect" @select-all="handleSelectAll">

      <el-table-column type="selection" width="50" align="center" />

      <el-table-column prop="fullName" min-width="240">
        <template slot="header" slot-scope="scope">
          <div class="custom-header">
            <span>元数据名称</span>
            <el-input v-model="nameFilter" size="mini" placeholder="输入名称筛选..." clearable @input="handleInputSearch"
              prefix-icon="el-icon-search" @click.native.stop />
          </div>
        </template>
      </el-table-column>

      <el-table-column label="所属对象" width="180">
        <template slot="header" slot-scope="scope">
          <div class="custom-header">
            <span>所属对象</span>
            <el-input v-model="parentFilter" size="mini" placeholder="输入对象筛选..." clearable @input="handleInputSearch"
              prefix-icon="el-icon-search" @click.native.stop />
          </div>
        </template>
        <template slot-scope="scope">{{ getParentName(scope.row.fullName) }}</template>
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

      <el-table-column prop="lastModifiedByName" label="修改人" width="140" show-overflow-tooltip />

      <el-table-column prop="lastModifiedDate" label="修改时间" width="160" sortable>
        <template slot-scope="scope">{{ parseTime(scope.row.lastModifiedDate) }}</template>
      </el-table-column>

      <el-table-column label="操作" width="140" align="center" fixed="right">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-view" @click="handleView(scope.row)">代码</el-button>
          <el-button size="mini" type="text" icon="el-icon-connection" :disabled="!targetOrgId"
            @click="handleDiff(scope.row)">比对</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      :page-sizes="[50, 100, 200, 300, 500]" @pagination="fetchList" />

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
      mapUpdateTrigger: 0,

      list: [],
      total: 0,
      orgOptions: [],
      metadataTypeOptions: [],
      currentOrgId: null,
      targetOrgId: null,

      queryParams: {
        pageNum: 1,
        pageSize: 50,
        orgId: null,
        type: 'ApexClass',
        keyword: ''
      },

      nameFilter: '',
      parentFilter: '',
      diffFilter: '',
      debounceTimer: null
    };
  },
  computed: {
    currentOrgName() {
      if (!this.currentOrgId) return '';
      const org = this.orgOptions.find(item => item.id === this.currentOrgId);
      return org ? org.name : `ID: ${this.currentOrgId}`;
    },
    selectedCurrentTypeCount() {
      const _ = this.mapUpdateTrigger;
      if (!this.existMap.size) return 0;
      let count = 0;
      const prefix = this.queryParams.type + ':';
      for (let key of this.existMap.keys()) {
        if (key.startsWith(prefix)) {
          count++;
        }
      }
      return count;
    },
    deploymentTotalCount() {
      const _ = this.mapUpdateTrigger;
      return this.existMap.size;
    },
    existingDiffOptions() {
      if (!this.list || this.list.length === 0) return [];
      const statusSet = new Set(
        this.list.map(item => item.diffStatus).filter(s => s)
      );
      return Array.from(statusSet).sort();
    },
    filteredList() {
      if (!this.diffFilter) return this.list;
      return this.list.filter(item => item.diffStatus === this.diffFilter);
    }
  },
  methods: {
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
      this.mapUpdateTrigger = 0;

      // 重置筛选
      this.nameFilter = '';
      this.parentFilter = '';
      this.diffFilter = '';
      this.queryParams.keyword = '';
      this.queryParams.pageNum = 1;

      // 初始化已选Map
      if (itemList && itemList.length > 0) {
        itemList.forEach(item => {
          this.existMap.set(item.metadataType + ':' + item.memberName, item.id);
        });
        this.mapUpdateTrigger++;
      }

      this.getOrgList();
      this.loadMetadataTypes();
      this.fetchList();
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
      this.nameFilter = '';
      this.parentFilter = '';
      this.diffFilter = '';
      this.queryParams.keyword = '';
      this.queryParams.pageNum = 1;
      this.list = [];
      this.fetchList();
    },

    handleInputSearch() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }
      this.debounceTimer = setTimeout(() => {
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
          if (item.fullName) {
            targetMap.set(item.fullName.toLowerCase(), item.lastModifiedDate);
          }
        });

        this.list = sourceList.map(item => {
          let status = '';
          if (this.targetOrgId) {
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
          // 1. 恢复勾选状态
          this.checkExistingRows();

          // 2. 【新增】表格滚动条滚回顶部
          if (this.$refs.metaTable && this.$refs.metaTable.bodyWrapper) {
            this.$refs.metaTable.bodyWrapper.scrollTop = 0;
          }
        });
      }).catch(err => {
        this.loading = false;
        this.list = [];
        this.total = 0;
        console.error("Fetch list error:", err);
        // 【关键修复】显示错误提示，而不是让用户以为是空数据
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
        // 【优化关键点】乐观更新：先在本地 Map 中占位，使计数器立即+1，消除延时感
        // 稍后接口返回成功后，detail.vue 会调用 updateMapAfterAdd 更新为真实的 ID，用户无感知
        this.existMap.set(key, 'PENDING');
        this.mapUpdateTrigger++; // 强制触发计算属性重新计算

        this.$emit('auto-action', { action: 'add', type: currentType, name: row.fullName, key: key });
      } else {
        const itemId = this.existMap.get(key);
        if (itemId) {
          this.$emit('auto-action', { action: 'remove', id: itemId, key: key });
          this.existMap.delete(key);
          this.mapUpdateTrigger++;
        } else {
          this.$refs.metaTable.toggleRowSelection(row, true);
        }
      }
    },

    handleSelectAll(selection) {
      const currentType = this.queryParams.type;
      const isSelectAll = selection.length > 0;

      if (isSelectAll) {
        const batchItems = [];
        selection.forEach(row => {
          const key = currentType + ':' + row.fullName;
          if (!this.existMap.has(key)) {
            batchItems.push({ type: currentType, name: row.fullName, key: key });

            // 【优化关键点】批量乐观更新：直接把所有勾选的都先占位
            this.existMap.set(key, 'PENDING');
          }
        });

        // 如果有新选中的项，触发更新并提交
        if (batchItems.length > 0) {
          this.mapUpdateTrigger++; // 立即刷新界面计数
          this.$emit('auto-action', { action: 'batch-add', items: batchItems });
        }
      } else {
        // (取消全选的逻辑保持不变，因为 delete 本身就是同步的，已经很快了)
        const batchIds = [];
        const batchKeys = [];
        this.filteredList.forEach(row => {
          const key = currentType + ':' + row.fullName;
          if (this.existMap.has(key)) {
            const itemId = this.existMap.get(key);
            if (itemId) {
              batchIds.push(itemId);
              batchKeys.push(key);
            }
          }
        });
        if (batchIds.length > 0) {
          batchKeys.forEach(k => this.existMap.delete(k));
          this.$emit('auto-action', { action: 'batch-remove', ids: batchIds });
          this.mapUpdateTrigger++;
        }
      }
    },

    updateMapAfterAdd(key, newId) {
      this.existMap.set(key, newId);
      this.mapUpdateTrigger++;
    },

    handleView(row) {
      this.$emit('view-code', { type: this.queryParams.type, name: row.fullName });
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
/* 容器调整 */
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

.mt-20 {
  margin-top: 20px;
}

.w-100 {
  width: 100%;
}

/* 数据统计仪表盘 */
.stats-panel {
  background-color: #f8fcfb;
  border: 1px solid #e1e6eb;
  border-radius: 4px;
  margin: 15px 0;
  padding: 15px 0;
}

.stat-item {
  text-align: center;
  position: relative;
}

.stat-item:not(:last-child)::after {
  content: "";
  position: absolute;
  right: 0;
  top: 10%;
  height: 80%;
  width: 1px;
  background-color: #e4e7ed;
}

.stat-label {
  font-size: 12px;
  color: #606266;
  margin-bottom: 5px;
}

.stat-value {
  font-size: 20px;
  font-weight: bold;
  font-family: Arial, sans-serif;
  margin-bottom: 5px;
}

.stat-desc {
  font-size: 11px;
  color: #c0c4cc;
  transform: scale(0.9);
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

/* 表头搜索框 */
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
}
</style>