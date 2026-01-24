<template>
  <div class="app-container dashboard-container">
    <el-row :gutter="20" class="panel-group">
      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel" @click="goRoute('/salesforce/org')">
          <div class="card-panel-icon-wrapper icon-blue">
            <i class="el-icon-office-building card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">已连接环境</div>
            <span class="card-panel-num">{{ dashboardData.connectedOrgs || 0 }}</span>
            <el-tag size="mini" type="success" style="margin-left: 5px">Online</el-tag>
          </div>
        </div>
      </el-col>

      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel" @click="goRoute('/salesforce/deployment')">
          <div class="card-panel-icon-wrapper icon-green">
            <i class="el-icon-s-promotion card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">本周部署</div>
            <span class="card-panel-num">{{ dashboardData.weeklyDeployments || 0 }}</span>
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
            <span class="card-panel-num">{{ dashboardData.successRate || '0%' }}</span>
          </div>
        </div>
      </el-col>

      <el-col :xs="12" :sm="12" :lg="6" class="card-panel-col">
        <div class="card-panel">
          <div class="card-panel-icon-wrapper icon-yellow">
            <i v-if="dashboardData.activeTasks > 0" class="el-icon-loading card-panel-icon" />
            <i v-else class="el-icon-timer card-panel-icon" />
          </div>
          <div class="card-panel-description">
            <div class="card-panel-text">正在执行</div>
            <span class="card-panel-num">{{ dashboardData.activeTasks || 0 }}</span>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :xs="24" :sm="24" :lg="16">
        <el-card shadow="hover" class="chart-card">
          <div slot="header" class="clearfix">
            <span class="card-header-title"><i class="el-icon-data-line"></i> 部署频率趋势 (近14天)</span>
          </div>
          <div ref="lineChart" style="height: 350px; width: 100%;"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :lg="8">
        <el-card shadow="hover" class="chart-card">
          <div slot="header" class="clearfix">
            <span class="card-header-title"><i class="el-icon-pie-chart"></i> 部署结果分布</span>
          </div>
          <div ref="pieChart" style="height: 350px; width: 100%;"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :xs="24" :sm="24" :lg="16">
        <el-card shadow="hover">
          <div slot="header" class="clearfix">
            <span class="card-header-title"><i class="el-icon-time"></i> 最新部署动态</span>
            <el-button style="float: right; padding: 3px 0" type="text"
              @click="goRoute('/salesforce/deployment')">查看全部</el-button>
          </div>

          <el-table :data="dashboardData.recentActivities" style="width: 100%" size="small"
            :header-cell-style="{ background: '#f5f7fa' }">
            <el-table-column label="部署包标题" min-width="180" show-overflow-tooltip>
              <template slot-scope="scope">
                <span class="link-type" @click="goHistory(scope.row)">
                  {{ scope.row.deploymentTitle || ('部署任务 #' + scope.row.id) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column prop="type" label="类型" width="100" align="center">
              <template slot-scope="scope">
                <el-tag v-if="scope.row.type === 'Validate'" type="warning" size="mini" effect="plain">验证</el-tag>
                <el-tag v-else-if="scope.row.type === 'Deploy'" type="primary" size="mini" effect="plain">部署</el-tag>
                <el-tag v-else-if="scope.row.type === 'Quick'" type="success" size="mini" effect="plain">快速</el-tag>
                <el-tag v-else-if="scope.row.type === 'Rollback'" type="danger" size="mini" effect="plain">回滚</el-tag>
                <span v-else>{{ scope.row.type }}</span>
              </template>
            </el-table-column>

            <el-table-column prop="status" label="状态" width="120" align="center">
              <template slot-scope="scope">
                <el-tag :type="getStatusTag(scope.row.status)" size="mini" effect="dark">
                  <i :class="getStatusIcon(scope.row.status)"></i> {{ scope.row.status }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="createBy" label="执行人" width="120" align="center">
              <template slot-scope="scope">
                <i class="el-icon-user"></i> {{ scope.row.createBy }}
              </template>
            </el-table-column>

            <el-table-column prop="startTime" label="执行时间" width="160" align="right" style="color: #909399;">
              <template slot-scope="scope">{{ parseTime(scope.row.startTime) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :lg="8">
        <el-card shadow="hover" class="box-card">
          <div slot="header" class="clearfix">
            <span class="card-header-title">快捷操作 (Quick Actions)</span>
          </div>
          <div class="quick-actions">
            <el-button type="primary" icon="el-icon-plus" class="action-btn" @click="goRoute('/salesforce/deployment')">
              新建部署包
            </el-button>
            <el-button type="warning" icon="el-icon-search" class="action-btn" plain
              @click="goRoute('/salesforce/metadataAudit')">
              元数据审计中心
            </el-button>
            <el-button type="info" icon="el-icon-search" class="action-btn" plain
              @click="goRoute('/salesforce/reconcile')">
              数据质量验证
            </el-button>
            <el-button type="success" icon="el-icon-connection" class="action-btn" @click="goRoute('/salesforce/org')">
              管理环境 (Org)
            </el-button>
          </div>
        </el-card>

        <el-card shadow="never" style="margin-top: 20px; background-color: #f4f4f5;">
          <div style="font-size: 13px; color: #606266; line-height: 1.8;">
            <i class="el-icon-info" style="color:#409EFF"></i>
            当前版本: <b>{{ dashboardData.sysVersion }}</b><br>
            系统名称: <span>{{ dashboardData.sysName }}</span><br>
            系统状态: <span style="color:#67C23A"><i class="el-icon-success"></i> 正常运行</span>
            <p style="margin-top: 5px;">
              DevOps 平台助您实现高效、安全的 Salesforce 持续交付。
            </p>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { getDashboardData } from "@/api/salesforce/dashboard";
import * as echarts from 'echarts';
require('echarts/theme/macarons'); // 引入主题

export default {
  name: "Dashboard",
  data() {
    return {
      loading: true,
      dashboardData: {
        connectedOrgs: 0,
        activeTasks: 0,
        weeklyDeployments: 0,
        successRate: '0%',
        chartDates: [],
        chartCounts: [],
        statusPieData: [],
        recentActivities: [],
        sysVersion: 'Loading...',
        sysName: ''
      },
      lineChart: null,
      pieChart: null
    };
  },
  mounted() {
    this.initData();
    // 窗口缩放时自适应图表
    window.addEventListener('resize', this.handleResize);
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize);
    if (this.lineChart) {
      this.lineChart.dispose();
      this.lineChart = null;
    }
    if (this.pieChart) {
      this.pieChart.dispose();
      this.pieChart = null;
    }
  },
  methods: {
    initData() {
      this.loading = true;
      getDashboardData().then(res => {
        this.dashboardData = res.data;
        this.loading = false;
        this.$nextTick(() => {
          this.initLineChart();
          this.initPieChart();
        });
      }).catch(() => {
        this.loading = false;
      });
    },

    // 初始化折线图
    initLineChart() {
      if (this.lineChart) this.lineChart.dispose();
      this.lineChart = echarts.init(this.$refs.lineChart, 'macarons');

      this.lineChart.setOption({
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'cross' }
        },
        grid: {
          left: '3%', right: '4%', bottom: '3%', containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.dashboardData.chartDates || []
        },
        yAxis: { type: 'value' },
        series: [{
          name: '部署次数',
          type: 'line',
          smooth: true,
          data: this.dashboardData.chartCounts || [],
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(64, 158, 255, 0.3)' },
              { offset: 1, color: 'rgba(64, 158, 255, 0.05)' }
            ])
          },
          itemStyle: { color: '#409EFF' },
          lineStyle: { width: 3 }
        }]
      });
    },

    // 初始化饼图
    initPieChart() {
      if (this.pieChart) this.pieChart.dispose();
      this.pieChart = echarts.init(this.$refs.pieChart, 'macarons');

      // 处理一下数据，把英文状态转中文（可选）
      const rawData = this.dashboardData.statusPieData || [];

      this.pieChart.setOption({
        tooltip: {
          trigger: 'item',
          formatter: '{a} <br/>{b} : {c} ({d}%)'
        },
        legend: {
          bottom: 10,
          left: 'center',
          // data: ['Succeeded', 'Failed', 'Validating', 'Draft']
        },
        series: [
          {
            name: '状态分布',
            type: 'pie',
            radius: ['40%', '70%'],
            avoidLabelOverlap: false,
            itemStyle: {
              borderRadius: 10,
              borderColor: '#fff',
              borderWidth: 2
            },
            label: { show: false, position: 'center' },
            emphasis: {
              label: {
                show: true,
                fontSize: '18',
                fontWeight: 'bold'
              }
            },
            data: rawData
          }
        ]
      });
    },

    handleResize() {
      if (this.lineChart) this.lineChart.resize();
      if (this.pieChart) this.pieChart.resize();
    },

    goRoute(path) {
      this.$router.push(path);
    },

    // 跳转到部署包详情（如果知道 DeploymentId）
    goHistory(row) {
      if (row.deploymentId) {
        this.$router.push({ path: '/salesforce/deploymentDetail', query: { id: row.deploymentId } });
      }
    },

    getStatusTag(status) {
      if (status === 'Succeeded' || status === 'Validated') return 'success';
      if (status === 'Failed' || status === 'Canceled') return 'danger';
      if (status === 'Deploying' || status === 'Processing') return 'primary';
      return 'info';
    },
    getStatusIcon(status) {
      if (status === 'Succeeded') return 'el-icon-check';
      if (status === 'Failed') return 'el-icon-close';
      if (status === 'Deploying') return 'el-icon-loading';
      return 'el-icon-info';
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
    margin-top: 10px;

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
      transition: all 0.3s;

      &:hover {
        transform: translateY(-5px);
        box-shadow: 0 10px 20px rgba(0, 0, 0, 0.1);

        .card-panel-icon-wrapper {
          color: #fff;
        }

        .icon-blue {
          background: #36a3f7;
        }

        .icon-green {
          background: #34bfa3;
        }

        .icon-red {
          background: #f4516c;
        }

        .icon-yellow {
          background: #ffba00;
        }
      }

      .icon-blue {
        color: #36a3f7;
      }

      .icon-green {
        color: #34bfa3;
      }

      .icon-red {
        color: #f4516c;
      }

      .icon-yellow {
        color: #ffba00;
      }

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

  .chart-card {
    background: #fff;
    padding: 0;
    margin-bottom: 20px;
  }

  .card-header-title {
    font-weight: bold;
    font-size: 16px;
    color: #303133;
  }

  .link-type {
    color: #409EFF;
    cursor: pointer;
    font-weight: 600;

    &:hover {
      text-decoration: underline;
    }
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
}
</style>