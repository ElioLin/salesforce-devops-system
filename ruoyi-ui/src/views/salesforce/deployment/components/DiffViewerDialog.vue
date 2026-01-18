<template>
    <el-dialog :title="title" :visible.sync="visible" width="90%" append-to-body top="2vh" custom-class="diff-dialog"
        :before-close="handleClose">
        <div class="diff-toolbar">
            <div class="toolbar-left">
                <el-checkbox v-model="diffOpts.ignoreTrimWhitespace" @change="updateEditorOptions" border size="mini">
                    忽略空白字符
                </el-checkbox>
                <el-checkbox v-model="diffOpts.wordWrap" @change="updateEditorOptions" border size="mini" class="ml-10">
                    自动换行
                </el-checkbox>
                <el-checkbox v-model="diffOpts.renderSideBySide" @change="updateEditorOptions" border size="mini"
                    class="ml-10" v-if="isDiffMode">
                    双栏显示
                </el-checkbox>
            </div>
            <div class="toolbar-right" v-if="isDiffMode">
                <span class="diff-stat mr-10" v-if="diffStat.changes > 0">
                    共 {{ diffStat.changes }} 处变更
                </span>
                <el-button-group>
                    <el-button size="mini" icon="el-icon-arrow-up" @click="navDiff('prev')">上一处</el-button>
                    <el-button size="mini" icon="el-icon-arrow-down" @click="navDiff('next')">下一处</el-button>
                </el-button-group>
            </div>
        </div>

        <monaco-editor ref="diffEditor" v-if="visible" :value="codeContent" :original="oldCodeContent"
            :diffEditor="isDiffMode" :language="language" height="700px" theme="vs-dark" :options="editorOptions"
            @editorDidMount="handleEditorDidMount" />

        <div slot="footer" class="dialog-footer">
            <el-button @click="close">关 闭</el-button>
        </div>
    </el-dialog>
</template>

<script>
import MonacoEditor from '@/components/MonacoEditor';

export default {
    name: "DiffViewerDialog",
    components: { MonacoEditor },
    data() {
        return {
            visible: false,
            title: "",
            codeContent: "",
            oldCodeContent: "",
            isDiffMode: false,
            language: "java",

            diffOpts: {
                ignoreTrimWhitespace: false,
                wordWrap: false,
                renderSideBySide: true
            },
            diffStat: { changes: 0 },
            editorOptions: {
                readOnly: true,
                originalEditable: false,
                automaticLayout: true,
                renderSideBySide: true,
                ignoreTrimWhitespace: false,
                hideUnchangedRegions: { enabled: true, revealLineCount: 10, minimumLineCount: 20 }
            },
            // 非响应式变量
            _editorInstance: null
        };
    },
    watch: {
        visible(val) {
            if (!val) {
                this._editorInstance = null;
                this.diffStat.changes = 0;
            }
        }
    },
    methods: {
        /**
         * 父组件调用的打开方法
         */
        open(sourceContent, targetContent, title, language, isDiff = true) {
            this.codeContent = sourceContent;
            this.oldCodeContent = targetContent;
            this.title = title;
            this.language = language;
            this.isDiffMode = isDiff;
            this.visible = true;
            // 重置一些状态
            this.diffStat.changes = 0;
        },
        close() {
            this.visible = false;
        },
        handleClose(done) {
            this.close();
        },

        // ================== 下面直接复制原 detail.vue 中那4个经过多次修复的硬核方法 ==================
        // getEditorInstance, handleEditorDidMount, updateEditorOptions, navDiff, initDiffListeners, updateDiffStats
        // 请直接将上一轮对话中最后确认可用的那几个方法复制到这里

        getEditorInstance() {
            // 1. 优先使用缓存
            if (this._editorInstance) {
                // 如果当前是 Diff 模式，必须校验缓存是否有效
                if (this.isDiffMode) {
                    if (typeof this._editorInstance.getLineChanges === 'function') {
                        return this._editorInstance;
                    }
                    this._editorInstance = null; // 缓存失效
                } else {
                    return this._editorInstance;
                }
            }

            // 2. 获取组件 Ref
            const cmp = this.$refs.diffEditor;
            if (!cmp) {
                console.warn("Detail: 组件尚未挂载 (cmp is null)");
                return null;
            }

            let found = null;

            // 3. 【核心逻辑】地毯式搜索
            // 我们定义一个检查函数：只要对象有 getLineChanges 方法，它就是我们要找的 DiffEditor
            const isDiffEditor = (obj) => {
                return obj && typeof obj === 'object' && typeof obj.getLineChanges === 'function';
            };

            // (A) 检查常见入口
            if (isDiffEditor(cmp.diffEditor)) found = cmp.diffEditor;
            else if (isDiffEditor(cmp.editor)) found = cmp.editor;
            else if (isDiffEditor(cmp._diffEditor)) found = cmp._diffEditor;
            else if (isDiffEditor(cmp._editor)) found = cmp._editor;

            // (B) 检查 getEditor() 方法返回值
            if (!found && typeof cmp.getEditor === 'function') {
                const res = cmp.getEditor();
                if (isDiffEditor(res)) found = res;
            }

            // (C) 【大招】遍历组件实例的所有属性 (包括 $data)
            if (!found && this.isDiffMode) {
                // 遍历 $data
                for (const key in cmp.$data) {
                    if (isDiffEditor(cmp.$data[key])) {
                        found = cmp.$data[key];
                        console.log(`Detail: 在 $data.${key} 中找到了 DiffEditor`);
                        break;
                    }
                }
                // 遍历直接属性 (部分封装库直接挂在 this 上)
                if (!found) {
                    for (const key in cmp) {
                        // 跳过 Vue 内部属性 ($开头的) 以防性能损耗，除非明确知道
                        if (key.startsWith('$') && key !== '$refs') continue;
                        try {
                            if (isDiffEditor(cmp[key])) {
                                found = cmp[key];
                                console.log(`Detail: 在 prop [${key}] 中找到了 DiffEditor`);
                                break;
                            }
                        } catch (e) { }
                    }
                }
            }

            // (D) 如果是非 Diff 模式，退化为寻找普通 Editor (有 getPosition 方法)
            if (!this.isDiffMode && !found) {
                const isEditor = (obj) => obj && typeof obj.getPosition === 'function';
                if (isEditor(cmp.editor)) found = cmp.editor;
                else if (typeof cmp.getEditor === 'function') found = cmp.getEditor();
            }

            // 4. 存入缓存
            if (found) {
                this._editorInstance = found;
                return found;
            }

            // 5. 实在找不到，打印整个组件结构供调试
            if (this.isDiffMode) {
                console.error("Detail: 致命错误 - 无法在组件中找到 DiffEditor 实例。组件结构如下:", cmp);
            }
            return null;
        },
        handleEditorDidMount(editor) {
            console.log("Detail: Monaco Mount Event", editor);

            if (this.isDiffMode) {
                // 校验传入的是否为 DiffEditor
                if (editor && typeof editor.getLineChanges === 'function') {
                    this._editorInstance = editor;
                    this.initDiffListeners(editor);
                } else {
                    // 如果传出来的是普通 Editor (极有可能)，则清空缓存，
                    // 迫使 getEditorInstance 下次去组件属性里挖
                    console.warn("Detail: Mount 传入的不是 DiffEditor，将在后续操作中自动修正");
                    this._editorInstance = null;

                    // 尝试立即修正一次
                    this.$nextTick(() => {
                        const realDiff = this.getEditorInstance();
                        if (realDiff) this.initDiffListeners(realDiff);
                    });
                }
            } else {
                this._editorInstance = editor;
            }

            this.updateEditorOptions();
        },
        initDiffListeners(editor) {
            if (editor && editor.onDidUpdateDiff) {
                editor.onDidUpdateDiff(() => {
                    this.updateDiffStats();
                });
            }
            // 延迟兜底
            setTimeout(() => { this.updateDiffStats(); }, 500);
        },
        updateDiffStats() {
            const editor = this.getEditorInstance();
            if (editor && typeof editor.getLineChanges === 'function') {
                const changes = editor.getLineChanges() || [];
                this.diffStat.changes = changes.length;
            }
        },
        updateEditorOptions() {
            const newOpts = {
                readOnly: true,
                originalEditable: false,
                automaticLayout: true,
                renderSideBySide: this.diffOpts.renderSideBySide,
                ignoreTrimWhitespace: this.diffOpts.ignoreTrimWhitespace,
                wordWrap: this.diffOpts.wordWrap ? 'on' : 'off',
                scrollBeyondLastLine: false,
                minimap: { enabled: false }
            };
            this.editorOptions = newOpts;

            this.$nextTick(() => {
                const editor = this.getEditorInstance();
                if (!editor) return;

                try {
                    if (typeof editor.updateOptions === 'function') {
                        editor.updateOptions({
                            renderSideBySide: this.diffOpts.renderSideBySide,
                            ignoreTrimWhitespace: this.diffOpts.ignoreTrimWhitespace
                        });
                    }
                    if (this.isDiffMode && typeof editor.getModifiedEditor === 'function') {
                        const wrapOpts = { wordWrap: this.diffOpts.wordWrap ? 'on' : 'off' };
                        editor.getOriginalEditor().updateOptions(wrapOpts);
                        editor.getModifiedEditor().updateOptions(wrapOpts);
                    }
                } catch (e) {
                    // console.warn(e);
                }
            });
        },

        navDiff(direction) {
            // 1. 获取实例
            const editor = this.getEditorInstance();

            // 如果找不到，或者功能不全，提示用户稍等（可能是因为那 1 秒延迟还没过）
            if (!editor || (this.isDiffMode && typeof editor.getLineChanges !== 'function')) {
                this.$modal.msgWarning("比对引擎正在计算中，请 1 秒后再试...");
                // 清空缓存，下次点击强制重新扫描
                this._editorInstance = null;
                return;
            }

            // 2. 详细的失败判断与日志，方便最后一次排查
            if (!editor) {
                // this.$modal.msgWarning("编辑器正在初始化，请稍后...");
                console.warn("Detail: navDiff 失败 - 无法获取编辑器实例 (Ref 为空或未找到属性)");
                return;
            }

            if (typeof editor.getLineChanges !== 'function') {
                console.warn("Detail: navDiff 失败 - 获取到的实例不支持 Diff (它是普通 Editor)", editor);
                // 既然拿错了，清空缓存，让用户再点一次试试
                this._editorInstance = null;
                this.$modal.msgWarning("编辑器模式校准中，请再试一次");
                return;
            }

            // 3. 正常逻辑
            const changes = editor.getLineChanges() || [];
            if (changes.length === 0) {
                this.$modal.msgWarning('当前视图完全一致');
                return;
            }

            const modifiedEditor = editor.getModifiedEditor();
            if (!modifiedEditor) return;

            const currentLine = modifiedEditor.getPosition().lineNumber;
            let targetLine = -1;

            if (direction === 'next') {
                const nextChange = changes.find(c => c.modifiedStartLineNumber > currentLine);
                targetLine = nextChange ? nextChange.modifiedStartLineNumber : changes[0].modifiedStartLineNumber;
            } else {
                const prevChanges = changes.filter(c => c.modifiedEndLineNumber < currentLine);
                targetLine = prevChanges.length > 0
                    ? prevChanges[prevChanges.length - 1].modifiedStartLineNumber
                    : changes[changes.length - 1].modifiedStartLineNumber;
            }

            if (targetLine < 1) targetLine = 1;

            modifiedEditor.setPosition({ lineNumber: targetLine, column: 1 });
            modifiedEditor.revealLineInCenter(targetLine);
            modifiedEditor.focus();
        }
    }
};
</script>

<style scoped>
/* 这里放入原 detail.vue 中关于 .diff-toolbar 的 CSS */
.diff-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 10px;
    background-color: #252526;
    border-bottom: 1px solid #333;
    color: #ccc;
    border-radius: 4px 4px 0 0;
}

.diff-toolbar .el-checkbox {
    color: #ccc;
}

.diff-toolbar .toolbar-left {
    display: flex;
    align-items: center;
}

.diff-toolbar .toolbar-right {
    display: flex;
    align-items: center;
}

.diff-stat {
    font-size: 12px;
    color: #909399;
}

.mr-10 {
    margin-right: 10px;
}

.ml-10 {
    margin-left: 10px;
}

::v-deep .diff-dialog .el-dialog__body {
    padding: 0;
    overflow: hidden;
}
</style>