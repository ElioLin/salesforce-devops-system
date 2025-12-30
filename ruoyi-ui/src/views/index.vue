<template>
  <div class="app-container dashboard-container">
    <el-row :gutter="20" class="panel-group">
      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel">
          <div class="card-panel-icon-wrapper icon-blue">
            <i class="el-icon-office-building card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">已连接环境</div>
            <span class="card-panel-num">12</span>
            <el-tag size="mini" type="success" style="margin-left: 5px">Online</el-tag>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel">
          <div class="card-panel-icon-wrapper icon-green">
            <i class="el-icon-s-promotion card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">本周部署</div>
            <span class="card-panel-num">35</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel">
          <div class="card-panel-icon-wrapper icon-red">
            <i class="el-icon-pie-chart card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">部署成功率</div>
            <span class="card-panel-num">92%</span>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel">
          <div class="card-panel-icon-wrapper icon-yellow">
            <i class="el-icon-timer card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">待验证包</div>
            <span class="card-panel-num">4</span>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      
      <el-col :xs="24" :sm="24" :lg="16">
        <el-card shadow="hover" class="box-card main-chart-card">
          <div slot="header" class="clearfix">
            <span style="font-weight: bold; font-size: 16px;">
              <i class="el-icon-time" style="color: #409EFF; margin-right: 5px;"></i>
              最近部署活动 (Recent Deployments)
            </span>
            <el-button style="float: right; padding: 3px 0" type="text" @click="goRoute('/salesforce/deployment')">查看全部</el-button>
          </div>
          
          <el-table :data="recentDeployments" style="width: 100%" size="medium" :header-cell-style="{background:'#f5f7fa'}">
            <el-table-column prop="name" label="部署包名称" min-width="180">
              <template slot-scope="scope">
                <span class="link-type" @click="goDetail(scope.row.id)">{{ scope.row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="源环境 -> 目标环境" min-width="200">
              <template slot-scope="scope">
                <div class="org-flow">
                  <span class="org-tag source">{{ scope.row.source }}</span>
                  <i class="el-icon-right arrow"></i>
                  <span class="org-tag target">{{ scope.row.target }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="120" align="center">
              <template slot-scope="scope">
                <el-tag :type="getStatusType(scope.row.status)" effect="dark" size="small">
                  <i :class="getStatusIcon(scope.row.status)"></i> {{ scope.row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="time" label="执行时间" width="160" align="right" style="color: #909399;" />
          </el-table>
        </el-card>

        <el-card shadow="hover" style="margin-top: 20px;">
          <div slot="header">
            <span style="font-weight: bold;">
              <i class="el-icon-share" style="color: #67C23A; margin-right: 5px;"></i>
              流水线概览 (Pipeline Status)
            </span>
          </div>
          <div class="pipeline-container">
            <div class="pipeline-step">
              <div class="step-icon dev"><i class="el-icon-code"></i></div>
              <div class="step-title">Dev Sandbox</div>
              <div class="step-status success">同步正常</div>
            </div>
            <div class="pipeline-arrow"><i class="el-icon-d-arrow-right"></i></div>
            <div class="pipeline-step">
              <div class="step-icon uat"><i class="el-icon-cpu"></i></div>
              <div class="step-title">UAT / QA</div>
              <div class="step-status warning">差异: 12 文件</div>
            </div>
            <div class="pipeline-arrow"><i class="el-icon-d-arrow-right"></i></div>
            <div class="pipeline-step">
              <div class="step-icon prod"><i class="el-icon-s-platform"></i></div>
              <div class="step-title">Production</div>
              <div class="step-status success">运行中</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :lg="8">
        <el-card shadow="hover" class="box-card">
          <div slot="header" class="clearfix">
            <span style="font-weight: bold;">快捷操作 (Quick Actions)</span>
          </div>
          <div class="quick-actions">
            <el-button type="primary" icon="el-icon-plus" class="action-btn" @click="goRoute('/salesforce/deployment')">
              新建部署包
            </el-button>
            <el-button type="success" icon="el-icon-connection" class="action-btn" @click="goRoute('/salesforce/org')">
              管理环境 (Org)
            </el-button>
            <el-button type="warning" icon="el-icon-search" class="action-btn" plain @click="goRoute('/salesforce/metadata')">
              元数据浏览器
            </el-button>
            <el-button type="info" icon="el-icon-setting" class="action-btn" plain>
              系统设置
            </el-button>
          </div>
        </el-card>

        <el-card shadow="hover" style="margin-top: 20px;">
          <div slot="header" class="clearfix">
            <span style="font-weight: bold;">Salesforce 服务状态</span>
            <el-tag size="mini" type="success" style="float: right;">Normal</el-tag>
          </div>
          <div class="status-list">
            <div class="status-item">
              <span>Production (AP01)</span>
              <i class="el-icon-success" style="color: #67C23A;"></i>
            </div>
            <div class="status-item">
              <span>Sandbox (CS05)</span>
              <i class="el-icon-success" style="color: #67C23A;"></i>
            </div>
            <div class="status-item">
              <span>Metadata API</span>
              <i class="el-icon-warning" style="color: #E6A23C;"></i>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" style="margin-top: 20px; background-color: #f4f4f5;">
          <div style="font-size: 14px; color: #606266;">
            <i class="el-icon-info"></i> 
            当前版本: <b>v{{ version }}</b><br>
            <p style="margin-top: 10px;">
              需要帮助？请查阅 <a href="#" style="color: #409EFF;">用户手册</a> 或联系管理员。
            </p>
          </div>
        </el-card>

      </el-col>
    </el-row>
  </div>
</template>

<script>
export default {
  name: "Index",
  data() {
    return {
      version: "1.0.0 (DevOps Edition)",
      // 模拟的最近部署数据，实际对接时可以调用后端 API
      recentDeployments: [
        { id: 101, name: "Fix: Account Trigger Logic", source: "Dev Sandbox", target: "UAT", status: "Succeeded", time: "10分钟前" },
        { id: 102, name: "Feature: Q3 Sales Report", source: "UAT", target: "Production", status: "Validating", time: "1小时前" },
        { id: 103, name: "Hotfix: Login Flow", source: "Hotfix Org", target: "Production", status: "Failed", time: "昨天 18:30" },
        { id: 104, name: "POC: LWC Components", source: "Dev 02", target: "Dev Sandbox", status: "Draft", time: "2天前" },
        { id: 105, name: "Release: v2.0.1", source: "Staging", target: "Production", status: "Succeeded", time: "3天前" }
      ]
    };
  },
  methods: {
    goRoute(path) {
      this.$router.push(path);
    },
    goDetail(id) {
      // 假设部署详情页的路由是 /salesforce/deployment/detail
      // 如果没有详情页，可以跳转到列表
      this.$router.push({ path: '/salesforce/deployment', query: { id: id } });
    },
    getStatusType(status) {
      if (status === 'Succeeded') return 'success';
      if (status === 'Failed') return 'danger';
      if (status === 'Validating' || status === 'Deploying') return 'warning';
      return 'info';
    },
    getStatusIcon(status) {
      if (status === 'Succeeded') return 'el-icon-check';
      if (status === 'Failed') return 'el-icon-close';
      if (status === 'Validating' || status === 'Deploying') return 'el-icon-loading';
      return 'el-icon-edit-outline';
    }
  }
};
</script>

<style scoped lang="scss">
.dashboard-container {
  padding: 20px;
  background-color: #f0f2f5;
  min-height: calc(100vh - 84px);

  .panel-group {
    margin-top: 18px;

    .card-panel-col {
      margin-bottom: 20px;
    }

    .card-panel {
      height: 108px;
      cursor: pointer;
      font-size: 12px;
      position: relative;
      overflow: hidden;
      color: #666;
      background: #fff;
      box-shadow: 4px 4px 40px rgba(0, 0, 0, .05);
      border-color: rgba(0, 0, 0, .05);
      border-radius: 6px;

      &:hover {
        .card-panel-icon-wrapper {
          color: #fff;
        }

        .icon-blue { background: #36a3f7; }
        .icon-green { background: #34bfa3; }
        .icon-red { background: #f4516c; }
        .icon-yellow { background: #ffba00; }
      }

      .icon-blue { color: #36a3f7; }
      .icon-green { color: #34bfa3; }
      .icon-red { color: #f4516c; }
      .icon-yellow { color: #ffba00; }

      .card-panel-icon-wrapper {
        float: left;
        margin: 14px 0 0 14px;
        padding: 16px;
        transition: all 0.38s ease-out;
        border-radius: 6px;
      }

      .card-panel-icon {
        float: left;
        font-size: 48px;
      }

      .card-panel-description {
        float: right;
        font-weight: bold;
        margin: 26px 26px 26px 0;
        .card-panel-text {
          line-height: 18px;
          color: rgba(0, 0, 0, 0.45);
          font-size: 16px;
          margin-bottom: 12px;
        }
        .card-panel-num {
          font-size: 20px;
        }
      }
    }
  }

  .link-type {
    color: #409EFF;
    cursor: pointer;
    font-weight: 500;
    &:hover {
      text-decoration: underline;
    }
  }

  .org-flow {
    display: flex;
    align-items: center;
    .org-tag {
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: bold;
    }
    .source { background-color: #e8f3fe; color: #1072b6; }
    .target { background-color: #fef0f0; color: #f56c6c; } /* 生产环境一般用红色警示 */
    .arrow { margin: 0 8px; color: #909399; }
  }

  .quick-actions {
    display: flex;
    flex-direction: column;
    gap: 15px;
    .action-btn {
      width: 100%;
      margin-left: 0 !important;
      justify-content: flex-start;
      height: 50px;
      font-size: 15px;
    }
  }

  .status-list {
    .status-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 0;
      border-bottom: 1px solid #f0f2f5;
      &:last-child {
        border-bottom: none;
      }
      font-size: 14px;
    }
  }

  /* Pipeline Styles */
  .pipeline-container {
    display: flex;
    justify-content: space-around;
    align-items: center;
    padding: 20px 0;
  }
  .pipeline-step {
    text-align: center;
    .step-icon {
      width: 60px;
      height: 60px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto 10px;
      font-size: 28px;
      color: #fff;
    }
    .dev { background: #409EFF; box-shadow: 0 4px 10px rgba(64, 158, 255, 0.3); }
    .uat { background: #E6A23C; box-shadow: 0 4px 10px rgba(230, 162, 60, 0.3); }
    .prod { background: #67C23A; box-shadow: 0 4px 10px rgba(103, 194, 58, 0.3); }
    
    .step-title { font-weight: bold; color: #303133; margin-bottom: 5px; }
    .step-status { font-size: 12px; }
    .success { color: #67C23A; }
    .warning { color: #E6A23C; }
  }
  .pipeline-arrow {
    font-size: 24px;
    color: #C0C4CC;
  }
}
</style>