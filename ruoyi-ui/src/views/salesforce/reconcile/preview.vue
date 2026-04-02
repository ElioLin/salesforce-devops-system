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
        style="width: 200px; margin-left: 15px;" clearable @keyup.enter.native="handleQuery" />
      <el-button type="primary" icon="el-icon-search" size="small" style="margin-left: 10px"
        @click="handleQuery">搜索</el-button>

      <el-checkbox v-model="queryParams.excludePostCutoff" @change="handleQuery" style="margin-left: 20px;">
        仅显示真实差异 (隐藏截止后变更)
      </el-checkbox>
    </div>

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

      <el-table-column prop="fieldName" label="差异字段" width="180" show-overflow-tooltip fixed="left">
        <template slot-scope="scope">
          <span style="font-weight: bold;">{{ scope.row.fieldName || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="源数据 (Source)" align="left">
        <el-table-column prop="sourceKey" label="主键 (Key)" width="150" show-overflow-tooltip />
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
        <el-table-column prop="targetKey" label="主键 (Key)" width="150" show-overflow-tooltip />
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
        :current-page="queryParams.pageNum" :page-sizes="[50, 100, 200]" :page-size="queryParams.pageSize"
        layout="total, sizes, prev, pager, next, jumper" :total="total" />
    </div>
  </el-dialog>
</template>

<script>
import { previewObjResult } from "@/api/salesforce/dataRunObjLog";

export default {
  name: "PreviewResult",
  data() {
    return {
      visible: false,
      loading: false,
      objLogId: null,
      objectName: '',
      tableData: [],
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
    init(objLogId, objectName) {
      this.objLogId = objLogId;
      this.objectName = objectName;
      this.visible = true;
      this.queryParams.pageNum = 1;
      this.queryParams.diffType = '';
      this.queryParams.fieldName = '';
      this.queryParams.excludePostCutoff = true;
      this.getList();
    },
    getList() {
      this.loading = true;
      previewObjResult(this.objLogId, this.queryParams).then(res => {
        this.tableData = res.data.rows;
        this.total = res.data.total;
        this.loading = false;
      }).catch(() => {
        this.loading = false;
      });
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
    color: #b1b3b8; /* 在被忽略行中进一步弱化颜色 */
  }
</style>