<template>
  <div class="monaco-editor-container" ref="monacoContainer"></div>
</template>

<script>
import * as monaco from 'monaco-editor';

export default {
  name: "MonacoEditor",
  props: {
    // 原始代码 (左侧/旧版)
    original: { type: String, default: "" },
    // 修改后的代码 (右侧/新版) —— 如果是普通模式，只传这个 value
    value: { type: String, default: "" },
    // 是否开启比对模式
    diffEditor: { type: Boolean, default: false },
    language: { type: String, default: "java" },
    readOnly: { type: Boolean, default: true },
    theme: { type: String, default: "vs-dark" },
    height: { type: String, default: "500px" }
  },
  data() {
    return {
      editor: null, // 普通编辑器实例
      diffEditorInstance: null // 比对编辑器实例
    };
  },
  watch: {
    // 监听普通模式的值变化
    value(newValue) {
      if (!this.diffEditor && this.editor && newValue !== this.editor.getValue()) {
        this.editor.setValue(newValue);
      }
      // 如果是比对模式，通常不动态监听 value 变化，而是重绘，简化逻辑
    },
    // 监听 original 变化 (仅比对模式)
    original() {
      if (this.diffEditor) {
        this.initMonaco(); // 数据变了直接重建比较简单
      }
    }
  },
  mounted() {
    this.initMonaco();
  },
  beforeDestroy() {
    this.disposeEditor();
  },
  methods: {
    disposeEditor() {
      if (this.editor) {
        this.editor.dispose();
        this.editor = null;
      }
      if (this.diffEditorInstance) {
        this.diffEditorInstance.dispose();
        this.diffEditorInstance = null;
      }
    },
    initMonaco() {
      this.disposeEditor(); // 先清理旧的
      
      // 动态设置高度
      this.$refs.monacoContainer.style.height = this.height;

      // === 模式 A: 差异比对编辑器 ===
      if (this.diffEditor) {
        this.diffEditorInstance = monaco.editor.createDiffEditor(this.$refs.monacoContainer, {
          theme: this.theme,
          readOnly: true, // 比对模式通常只读
          automaticLayout: true,
          originalEditable: false // 左侧不可编辑
        });

        // 创建两个 Model：左边是原始代码，右边是新代码
        const originalModel = monaco.editor.createModel(this.original, this.language === 'apex' ? 'java' : this.language);
        const modifiedModel = monaco.editor.createModel(this.value, this.language === 'apex' ? 'java' : this.language);

        this.diffEditorInstance.setModel({
          original: originalModel,
          modified: modifiedModel
        });
      } 
      // === 模式 B: 普通编辑器 (之前的逻辑) ===
      else {
        this.editor = monaco.editor.create(this.$refs.monacoContainer, {
          value: this.value,
          language: this.language === 'apex' ? 'java' : this.language,
          theme: this.theme,
          readOnly: this.readOnly,
          automaticLayout: true,
          minimap: { enabled: true },
          fontSize: 14,
          fontFamily: 'Consolas, "Courier New", monospace'
        });

        this.editor.onDidChangeModelContent(() => {
          this.$emit("input", this.editor.getValue());
        });
      }
    }
  }
};
</script>

<style scoped>
.monaco-editor-container {
  width: 100%;
  border: 1px solid #ccc;
  border-radius: 4px;
  overflow: hidden;
}
</style>