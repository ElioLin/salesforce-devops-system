<template>
    <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="任务名称" prop="jobName">
          <el-input v-model="form.jobName" placeholder="请输入任务名称 (例如: 每日客户数据同步检查)" />
        </el-form-item>
        
        <el-row>
          <el-col :span="12">
            <el-form-item label="源环境" prop="sourceOrgId">
              <el-select v-model="form.sourceOrgId" placeholder="请选择源环境" style="width: 100%">
                <el-option
                  v-for="item in orgOptions"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标环境" prop="targetOrgId">
              <el-select v-model="form.targetOrgId" placeholder="请选择目标环境" style="width: 100%">
                <el-option
                  v-for="item in orgOptions"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
  
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </template>
  
  <script>
  import { getJob, addJob, updateJob } from "@/api/salesforce/dataJob";
  import { listOrg } from "@/api/salesforce/org"; // 假设您有 Org 的列表接口
  
  export default {
    name: "JobDialog",
    data() {
      return {
        // 是否显示弹出层
        open: false,
        // 弹出层标题
        title: "",
        // 表单参数
        form: {},
        // 机构选项
        orgOptions: [],
        // 表单校验
        rules: {
          jobName: [
            { required: true, message: "任务名称不能为空", trigger: "blur" }
          ],
          sourceOrgId: [
            { required: true, message: "请选择源环境", trigger: "change" }
          ],
          targetOrgId: [
            { required: true, message: "请选择目标环境", trigger: "change" }
          ]
        }
      };
    },
    methods: {
      // 初始化方法
      init(id) {
        this.reset();
        this.getOrgList(); // 加载环境列表
        if (id) {
          this.title = "修改比对任务";
          getJob(id).then(response => {
            this.form = response.data;
            this.open = true;
          });
        } else {
          this.title = "新建比对任务";
          this.open = true;
        }
      },
      // 获取环境列表
      getOrgList() {
        // 假设这是您的获取所有 Org 的接口
        listOrg().then(response => {
          this.orgOptions = response.rows;
        });
      },
      // 表单重置
      reset() {
        this.form = {
          id: undefined,
          jobName: undefined,
          sourceOrgId: undefined,
          targetOrgId: undefined,
          remark: undefined
        };
        this.resetForm("form");
      },
      // 取消按钮
      cancel() {
        this.open = false;
        this.reset();
      },
      // 提交按钮
      submitForm() {
        this.$refs["form"].validate(valid => {
          if (valid) {
            if (this.form.id != undefined) {
              updateJob(this.form).then(response => {
                this.$modal.msgSuccess("修改成功");
                this.open = false;
                this.$emit("ok");
              });
            } else {
              addJob(this.form).then(response => {
                this.$modal.msgSuccess("新增成功");
                this.open = false;
                this.$emit("ok");
              });
            }
          }
        });
      }
    }
  };
  </script>