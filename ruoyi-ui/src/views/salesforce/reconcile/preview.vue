<template>
  <el-dialog :title="dialogTitle" :visible.sync="visible" width="85%" top="5vh" append-to-body
    :close-on-click-modal="false">
    <div class="filter-container">
      <el-radio-group v-model="queryParams.diffType" size="small" @change="handleQuery">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="VALUE_DIFF">字段值差异</el-radio-button>
        <el-radio-button label="MISSING_IN_TARGET">目标缺失</el-radio-button>
        <el-radio-button label="MISSING_IN_SOURCE">源缺失</el-radio-button>
        <el-radio-button label="POST_CUTOFF_CHANGE">截止后变更</el-radio-button>
      </el-radio-group>

      <el-input v-model="queryParams.fieldName" placeholder="输入字段名搜索..." size="small"
        style="width: 200px; margin-left: 15px;" clearable @clear="handleQuery" @keyup.enter.native="handleQuery" />
      <el-button type="primary" icon="el-icon-search" size="small" style="margin-left: 10px"
        @click="handleQuery">搜索</el-button>

      <el-checkbox v-model="queryParams.excludePostCutoff" @change="handleQuery" style="margin-left: 20px;">
        仅显示真实差异 (隐藏截止后变更)
      </el-checkbox>

      <el-button type="warning" plain icon="el-icon-setting" size="small" style="margin-left: auto;"
        @click="openConfigDrawer">修改字段策略</el-button>
    </div>

    <el-drawer :title="`正在配置: ${objectLabel || objectName}`" :visible.sync="configDrawerVisible" direction="rtl"
      size="60%" :destroy-on-close="true" append-to-body>
      <div v-loading="drawerLoading" style="padding: 0 20px; height: calc(100vh - 130px);">
        <field-mapping-panel v-if="currentEditConfig && !drawerLoading" :config="currentEditConfig"
          :source-org-id="sourceOrgId" :object-label="objectLabel || objectName" />
      </div>
      <div style="padding: 15px 20px; border-top: 1px solid #ebeef5; text-align: right; background: #fff;">
        <el-button @click="configDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingConfig" @click="saveSingleConfig">保存并关闭</el-button>
      </div>
    </el-drawer>

    <el-table v-loading="loading" :data="tableData" border height="600px" style="width: 100%"
      :row-class-name="tableRowClassName">
      <el-table-column label="差异类型" width="160" align="center" fixed="left">
        <template slot-scope="scope">
          <el-tooltip v-if="scope.row.diffType === 'POST_CUTOFF_CHANGE'" effect="dark"
            content="单据在任务截断时间后被业务人员修改过，已安全忽略" placement="top">
            <el-tag :type="getDiffTag(scope.row.diffType)" effect="plain" style="border-style: dashed;">
              <i class="el-icon-time"></i> {{ formatDiffType(scope.row.diffType) }}
            </el-tag>
          </el-tooltip>

          <el-tag v-else :type="getDiffTag(scope.row.diffType)" effect="light">
            {{ formatDiffType(scope.row.diffType) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="fieldName" width="200" fixed="left">
        <template slot="header" slot-scope="scope">
          <div style="display: flex; align-items: center; justify-content: space-between;">
            <span>差异字段</span>
            <el-popover placement="bottom" width="260" trigger="click">
              <div style="max-height: 300px; overflow-y: auto; padding-right: 5px;">
                <div
                  style="margin-bottom: 10px; font-weight: bold; color: #909399; font-size: 12px; border-bottom: 1px solid #ebeef5; padding-bottom: 5px;">
                  <i class="el-icon-pie-chart"></i> 本页异常字段分布
                </div>
                <div v-if="displayFieldCounts.length === 0"
                  style="color: #c0c4cc; font-size: 12px; text-align: center;">暂无数据</div>
                <el-row v-for="item in displayFieldCounts" :key="item.field" class="field-stat-row"
                  @click.native="quickFilterField(item.field)">
                  <el-col :span="18" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                    <span style="font-size: 13px; color: #409EFF;" :title="item.field">{{ item.field }}</span>
                  </el-col>
                  <el-col :span="6" style="text-align: right;">
                    <el-tag size="mini" type="danger" effect="plain">{{ item.count }}</el-tag>
                  </el-col>
                </el-row>
              </div>
              <i slot="reference" class="el-icon-data-analysis filter-icon" title="点击查看本页异常字段分布"></i>
            </el-popover>
          </div>
        </template>
        <template slot-scope="scope">
          <span style="font-weight: bold;">{{ scope.row.fieldName || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="源数据 (Source)" align="left">
        <el-table-column prop="sourceKey" label="主键 (Key)" min-width="240" show-overflow-tooltip>
          <template slot-scope="scope">
            <span v-if="scope.row.sourceKey" style="display: flex; align-items: center; flex-wrap: nowrap;">
              <span style="font-family: Consolas, monospace; margin-right: 8px;">{{ scope.row.sourceKey }}</span>

              <el-tooltip v-if="scope.row.sourceId" content="按真实 ID 直接打开记录" placement="top">
                <i class="el-icon-link action-icon link-icon" @click="jumpToSf(scope.row.sourceId, 'source')"></i>
              </el-tooltip>
              <el-tooltip v-else-if="isSfId(scope.row.sourceKey)" content="按 Salesforce ID 直接打开" placement="top">
                <i class="el-icon-link action-icon link-icon" @click="jumpToSf(scope.row.sourceKey, 'source')"></i>
              </el-tooltip>

              <el-tooltip v-if="!scope.row.sourceId && !isSfId(scope.row.sourceKey)" content="在 Salesforce 全局搜索该值"
                placement="top">
                <i class="el-icon-search action-icon search-icon"
                  @click="searchInSf(scope.row.sourceKey, 'source')"></i>
              </el-tooltip>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column prop="sourceValue" label="值 (Value)" min-width="200" show-overflow-tooltip>
          <template slot-scope="scope">
            <div class="value-text">{{ scope.row.sourceValue }}</div>
            <div v-if="scope.row.sourceCreatedDate || scope.row.sourceLastModifiedDate" class="time-trace-box">
              <span v-if="scope.row.diffType && scope.row.diffType.includes('MISSING') && scope.row.sourceCreatedDate">
                <i class="el-icon-time"></i> 创建: {{ formatTime(scope.row.sourceCreatedDate) }}
              </span>
              <span v-else-if="scope.row.diffType === 'POST_CUTOFF_CHANGE' && scope.row.sourceLastModifiedDate">
                <i class="el-icon-edit-outline"></i> 修改: {{ formatTime(scope.row.sourceLastModifiedDate) }}
              </span>
            </div>
          </template>
        </el-table-column>
      </el-table-column>

      <el-table-column label="目标数据 (Target)" align="left">
        <el-table-column prop="targetKey" label="主键 (Key)" min-width="240" show-overflow-tooltip>
          <template slot-scope="scope">
            <span v-if="scope.row.targetKey" style="display: flex; align-items: center; flex-wrap: nowrap;">
              <span style="font-family: Consolas, monospace; margin-right: 8px;">{{ scope.row.targetKey }}</span>

              <el-tooltip v-if="scope.row.targetId" content="按真实 ID 直接打开目标环境记录" placement="top">
                <i class="el-icon-link action-icon link-icon" @click="jumpToSf(scope.row.targetId, 'target')"></i>
              </el-tooltip>
              <el-tooltip v-else-if="isSfId(scope.row.targetKey)" content="按 Salesforce ID 直接打开" placement="top">
                <i class="el-icon-link action-icon link-icon" @click="jumpToSf(scope.row.targetKey, 'target')"></i>
              </el-tooltip>

              <el-tooltip v-if="!scope.row.targetId && !isSfId(scope.row.targetKey)" content="在 Salesforce 全局搜索该值"
                placement="top">
                <i class="el-icon-search action-icon search-icon"
                  @click="searchInSf(scope.row.targetKey, 'target')"></i>
              </el-tooltip>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>

        <el-table-column prop="targetValue" label="值 (Value)" min-width="200" show-overflow-tooltip>
          <template slot-scope="scope">
            <div class="value-text">{{ scope.row.targetValue }}</div>
            <div v-if="scope.row.targetCreatedDate || scope.row.targetLastModifiedDate" class="time-trace-box">
              <span v-if="scope.row.diffType && scope.row.diffType.includes('MISSING') && scope.row.targetCreatedDate">
                <i class="el-icon-time"></i> 创建: {{ formatTime(scope.row.targetCreatedDate) }}
              </span>
              <span v-else-if="scope.row.diffType === 'POST_CUTOFF_CHANGE' && scope.row.targetLastModifiedDate">
                <i class="el-icon-edit-outline"></i> 修改: {{ formatTime(scope.row.targetLastModifiedDate) }}
              </span>
            </div>
          </template>
        </el-table-column>
      </el-table-column>
    </el-table>

    <div class="pagination-container" style="text-align: right; margin-top: 15px;">
      <el-pagination @size-change="handleSizeChange" @current-change="handleCurrentChange"
        :current-page="queryParams.pageNum" :page-sizes="[50, 100, 200, 500, 1000]" :page-size="queryParams.pageSize"
        layout="total, sizes, prev, pager, next, jumper" :total="total" />
    </div>
  </el-dialog>
</template>

<script>
import { previewObjResult } from "@/api/salesforce/dataRunObjLog";
import { listConfigs, batchSaveConfigs } from "@/api/salesforce/dataObjConfig";
import FieldMappingPanel from './components/FieldMappingPanel';

export default {
  name: "PreviewResult",
  components: { FieldMappingPanel },
  data() {
    return {
      visible: false,
      loading: false,
      objLogId: null,
      objectName: '',
      sourceDomain: '',
      targetDomain: '',

      jobId: null,
      sourceOrgId: null,
      objectLabel: '',

      configDrawerVisible: false,
      drawerLoading: false,
      savingConfig: false,
      currentEditConfig: null,
      fullConfigList: [],

      tableData: [],
      displayFieldCounts: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 50,
        diffType: '', // 默认全部
        fieldName: '',
        excludePostCutoff: true
      }
    };
  },
  computed: {
    dialogTitle() {
      return `差异预览 - ${this.objectName || ''}`;
    }
  },
  methods: {
    init(objLogId, objectName, sourceDomain = '', targetDomain = '', jobId = null, sourceOrgId = null, objectLabel = '') {
      this.objLogId = objLogId;
      this.objectName = objectName;
      this.sourceDomain = sourceDomain;
      this.targetDomain = targetDomain;

      this.jobId = jobId;
      this.sourceOrgId = sourceOrgId;
      this.objectLabel = objectLabel;

      this.visible = true;
      this.queryParams.pageNum = 1;
      this.queryParams.diffType = '';
      this.queryParams.fieldName = '';
      this.queryParams.excludePostCutoff = true;
      this.displayFieldCounts = [];
      this.getList();
    },
    getList() {
      this.loading = true;
      previewObjResult(this.objLogId, this.queryParams).then(res => {
        this.tableData = res.data.rows;
        this.total = res.data.total;
        this.loading = false;

        if (!this.queryParams.fieldName) {
          this.generateFieldCounts();
        }
      }).catch(() => {
        this.loading = false;
      });
    },

    // ==========================================
    // 【新增】：配置抽屉相关的方法
    // ==========================================
    async openConfigDrawer() {
      if (!this.jobId) {
        this.$message.error("缺少任务上下文信息，无法打开配置");
        return;
      }
      this.configDrawerVisible = true;
      this.drawerLoading = true;
      try {
        const confRes = await listConfigs(this.jobId);
        this.fullConfigList = confRes.data || [];
        this.currentEditConfig = this.fullConfigList.find(c => c.objectName === this.objectName);
        if (!this.currentEditConfig) {
          this.$message.warning("未找到该对象的原始配置数据。");
        }
      } catch (err) {
        console.error(err);
        this.$message.error("加载配置失败");
      } finally {
        this.drawerLoading = false;
      }
    },

    async saveSingleConfig() {
      this.savingConfig = true;
      try {
        await batchSaveConfigs(this.jobId, this.fullConfigList);
        this.$message.success("映射策略保存成功！重新启动该对象的验证任务后生效。");
        this.configDrawerVisible = false;
      } catch (err) {
        this.$message.error("保存失败");
      } finally {
        this.savingConfig = false;
      }
    },

    generateFieldCounts() {
      if (!this.tableData || this.tableData.length === 0) {
        this.displayFieldCounts = [];
        return;
      }
      const counts = {};

      this.tableData.forEach(row => {
        if (row.diffType === 'POST_CUTOFF_CHANGE') return;

        let fieldRaw = row.fieldName;
        if (!fieldRaw || fieldRaw.trim() === '') {
          if (row.diffType === 'MISSING_IN_TARGET') fieldRaw = '整行缺失 (目标端漏同步)';
          else if (row.diffType === 'MISSING_IN_SOURCE') fieldRaw = '整行缺失 (源端已被删)';
          else fieldRaw = '未知字段异常';
        }

        const fieldArray = fieldRaw.split(/[,;，；]/).map(f => f.trim()).filter(f => f !== '');
        fieldArray.forEach(f => {
          counts[f] = (counts[f] || 0) + 1;
        });
      });

      this.displayFieldCounts = Object.keys(counts)
        .map(key => ({ field: key, count: counts[key] }))
        .sort((a, b) => b.count - a.count);
    },
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    handleSizeChange(val) {
      this.queryParams.pageSize = val;
      this.getList();
    },
    handleCurrentChange(val) {
      this.queryParams.pageNum = val;
      this.getList();
    },
    // 辅助样式
    getDiffTag(type) {
      if (type === 'VALUE_DIFF' || type === 'FIELD_DIFF') return 'warning';
      if (type && type.includes('MISSING')) return 'danger';
      if (type === 'POST_CUTOFF_CHANGE') return 'info'; // 灰色标签
      return 'info';
    },
    formatDiffType(type) {
      const map = {
        'VALUE_DIFF': '内容不一致',
        'FIELD_DIFF': '内容不一致',
        'MISSING_IN_TARGET': '目标缺失(新增)',
        'MISSING_IN_SOURCE': '源缺失(删除)',
        'POST_CUTOFF_CHANGE': '截止后变更' // 【新增】
      };
      return map[type] || type;
    },
    tableRowClassName({ row }) {
      if (row.diffType === 'VALUE_DIFF' || row.diffType === 'FIELD_DIFF') return 'row-warning';
      //给被忽略的行加上专有的弱化样式
      if (row.diffType === 'POST_CUTOFF_CHANGE') return 'row-ignored';
      return '';
    },
    formatTime(isoStr) {
      if (!isoStr) return '';
      try {
        const date = new Date(isoStr);
        if (isNaN(date.getTime())) return isoStr; // 解析失败则返回原值
        const pad = n => n < 10 ? '0' + n : n;
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
      } catch (e) {
        return isoStr;
      }
    },
    // 【新增】：一键快捷按字段过滤
    quickFilterField(field) {
      // 【防错优化】：如果用户点击的是系统虚拟出来的缺失状态提示，友善地阻止他们并提示用法
      if (field.includes('整行缺失') || field === '未知字段异常') {
        this.$message.info("此类为整行级异常，建议使用上方单选按钮(源缺失/目标缺失)进行过滤");
        return;
      }
      this.queryParams.fieldName = field;
      this.handleQuery();
    },

    // 【新增】：正则严格判断是否为 Salesforce 的标准 ID (15 或 18 位英数字)
    isSfId(val) {
      if (!val) return false;
      const sfIdRegex = /^[a-zA-Z0-9]{15}$|^[a-zA-Z0-9]{18}$/;
      return sfIdRegex.test(val);
    },

    getBaseUrl(sysType) {
      let domain = sysType === 'source' ? this.sourceDomain : this.targetDomain;
      if (domain) {
        // 清理一下可能带入的协议头和尾部斜杠，保证拼接干净
        domain = domain.replace(/^https?:\/\//, '').replace(/\/$/, '');
        return `https://${domain}`;
      }
      return 'https://login.salesforce.com'; // 极端情况的兜底
    },

    // 【新增】：根据 Salesforce ID 直接在新标签页打开记录
    jumpToSf(id, sysType) {
      if (!id) return;
      const baseUrl = this.getBaseUrl(sysType);
      // Lightning 下标准对象的查看路由，比直接跳 /id 更稳定
      const sfUrl = `${baseUrl}/lightning/r/${this.objectName}/${id}/view`;
      window.open(sfUrl, '_blank');
    },

    // 【新增】：非 ID（如业务编号），拉起 Salesforce 全局搜索页面
    searchInSf(keyword, sysType) {
      if (!keyword) return;
      const baseUrl = this.getBaseUrl(sysType);
      // Salesforce 会接管这个跳板路由，并自动平滑重定向到目前版本对应的安全搜索页面
      const sfSearchUrl = `${baseUrl}/_ui/search/ui/UnifiedSearchResults?str=${encodeURIComponent(keyword)}`;
      window.open(sfSearchUrl, '_blank');
    }
  }
};
</script>

<style scoped>
.filter-container {
  background: #f8f8f9;
  padding: 10px;
  border-radius: 4px;
  margin-bottom: 10px;
}

.value-text {
  font-family: Consolas, Menlo, monospace;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.5;
}

::v-deep .el-table .row-warning {
  background: #fdf6ec;
}

/* 截止后变更行的浅灰色背景，降低视觉干扰 */
::v-deep .el-table .row-ignored {
  background: #f4f4f5;
}

::v-deep .el-table .row-ignored .value-text {
  color: #909399;
  /* 让里面比对的值文字也变灰 */
  /* text-decoration: line-through; */
  /* 可选：加上删除线，体现“已作废/忽略”的语意 */
}

/* 【新增】：时间轨迹的弱化样式 */
.time-trace-box {
  margin-top: 4px;
  font-size: 11px;
  color: #909399;
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

::v-deep .el-table .row-ignored .time-trace-box {
  color: #b1b3b8;
  /* 在被忽略行中进一步弱化颜色 */
}

/* 【新增】：表头过滤图标互动样式 */
.filter-icon {
  cursor: pointer;
  color: #909399;
  font-size: 16px;
  transition: color 0.3s;
}

.filter-icon:hover {
  color: #409EFF;
}

/* 【新增】：下拉统计列表项悬浮样式 */
.field-stat-row {
  margin-bottom: 8px;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.field-stat-row:hover {
  background-color: #f2f6fc;
}

/* 优化超链接样式，去掉默认下划线更清爽 */
::v-deep .el-link {
  font-family: Consolas, Menlo, monospace;
}

/* 【新增】：操作图标的美化与悬浮效果 */
.action-icon {
  cursor: pointer;
  margin-right: 6px;
  font-size: 15px;
  transition: transform 0.2s, color 0.2s;
}

.action-icon:hover {
  transform: scale(1.18);
}

.link-icon {
  color: #409EFF;
  /* 源生 ID 跳转用蓝色系 */
}

.search-icon {
  color: #909399;
  /* 搜索用稳重的灰色系 */
}

.search-icon:hover {
  color: #67C23A;
  /* 悬浮变成成功绿，提示用户这是很安全的操作 */
}
</style>