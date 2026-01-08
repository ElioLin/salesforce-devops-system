<template>
    <el-dialog
      :title="dialogTitle"
      :visible.sync="visible"
      width="85%"
      top="5vh"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="filter-container">
        <el-radio-group v-model="queryParams.diffType" size="small" @change="handleQuery">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="VALUE_DIFF">字段值差异</el-radio-button>
          <el-radio-button label="MISSING_IN_TARGET">目标缺失</el-radio-button>
          <el-radio-button label="MISSING_IN_SOURCE">源缺失</el-radio-button>
        </el-radio-group>
  
        <el-input
          v-model="queryParams.fieldName"
          placeholder="输入字段名搜索..."
          size="small"
          style="width: 200px; margin-left: 15px;"
          clearable
          @keyup.enter.native="handleQuery"
        />
        <el-button type="primary" icon="el-icon-search" size="small" style="margin-left: 10px" @click="handleQuery">搜索</el-button>
      </div>
  
      <el-table
        v-loading="loading"
        :data="tableData"
        border
        height="600px"
        style="width: 100%"
        :row-class-name="tableRowClassName"
      >
        <el-table-column label="差异类型" width="160" align="center" fixed="left">
          <template slot-scope="scope">
            <el-tag :type="getDiffTag(scope.row.diffType)" effect="light">
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
          <el-table-column prop="sourceValue" label="值 (Value)" min-width="150" show-overflow-tooltip>
            <template slot-scope="scope">
              <span class="value-text">{{ scope.row.sourceValue }}</span>
            </template>
          </el-table-column>
        </el-table-column>
  
        <el-table-column label="目标数据 (Target)" align="left">
          <el-table-column prop="targetKey" label="主键 (Key)" width="150" show-overflow-tooltip />
          <el-table-column prop="targetValue" label="值 (Value)" min-width="150" show-overflow-tooltip>
            <template slot-scope="scope">
              <span class="value-text">{{ scope.row.targetValue }}</span>
            </template>
          </el-table-column>
        </el-table-column>
      </el-table>
  
      <div class="pagination-container" style="text-align: right; margin-top: 15px;">
        <el-pagination
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :current-page="queryParams.pageNum"
          :page-sizes="[50, 100, 200]"
          :page-size="queryParams.pageSize"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
        />
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
          fieldName: ''
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
        if (type === 'VALUE_DIFF') return 'warning';
        if (type && type.includes('MISSING')) return 'danger';
        return 'info';
      },
      formatDiffType(type) {
        const map = {
          'VALUE_DIFF': '内容不一致',
          'MISSING_IN_TARGET': '目标缺失(新增)',
          'MISSING_IN_SOURCE': '源缺失(删除)'
        };
        return map[type] || type;
      },
      tableRowClassName({ row }) {
        if (row.diffType === 'VALUE_DIFF') return 'row-warning';
        return '';
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
  </style>