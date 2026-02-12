<template>
    <transition name="el-zoom-in-top">
        <div v-show="visible" class="console-wrapper">
            <div class="console-header">
                <span><i class="el-icon-cpu"></i> {{ title }}</span>
                <div class="console-actions">
                    <el-checkbox v-model="autoScroll" class="console-checkbox">自动滚动</el-checkbox>
                    <i class="el-icon-delete" @click="clearLogs" title="清屏"></i>
                </div>
            </div>
            <div class="console-body" ref="consoleBody">
                <div v-if="logs.length === 0" class="console-empty">
                    > Waiting for commands...<br />
                    > System ready.
                </div>
                <div v-for="(log, index) in logs" :key="index" class="console-line">
                    <span class="log-time">[{{ log.time }}]</span>
                    <span :class="['log-level', log.level]">{{ log.prefix }}</span>
                    <span :class="['log-msg', log.level]" v-html="log.message"></span>
                </div>
            </div>
        </div>
    </transition>
</template>

<script>
export default {
    name: "BuildConsole",
    props: {
        visible: {
            type: Boolean,
            default: false
        },
        title: {
            type: String,
            default: "DEVOPS TERMINAL"
        }
    },
    data() {
        return {
            logs: [],
            autoScroll: true
        };
    },
    methods: {
        clearLogs() {
            this.logs = [];
        },
        /**
         * 对外暴露的方法：追加日志
         */
        appendLog(message, level = 'info') {
            if (!message) return;
            const now = new Date();
            const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`;

            let prefix = 'INFO:';
            if (level === 'error') prefix = 'ERR :';
            else if (level === 'success') prefix = 'DONE:';
            else if (level === 'warn') prefix = 'WARN:';
            else if (level === 'cmd') prefix = 'EXEC:';

            this.logs.push({
                time: timeStr,
                level: level,
                prefix: prefix,
                message: message
            });

            if (this.autoScroll) {
                this.$nextTick(() => {
                    const body = this.$refs.consoleBody;
                    if (body) body.scrollTop = body.scrollHeight;
                });
            }
        }
    }
};
</script>

<style scoped>
/* 这里直接放入原 detail.vue 中关于 .console-wrapper 及内部的所有 CSS */
.console-wrapper {
    margin-top: 15px;
    background-color: #1e1e1e;
    border-radius: 6px;
    border: 1px solid #333;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    overflow: hidden;
    font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}

.console-header {
    background-color: #2d2d2d;
    color: #cccccc;
    padding: 8px 15px;
    font-size: 12px;
    border-bottom: 1px solid #111;
    display: flex;
    justify-content: space-between;
    align-items: center;
    user-select: none;
}

.console-actions {
    display: flex;
    align-items: center;
}

.console-checkbox {
    margin-right: 15px;
    color: #999;
}

::v-deep .console-checkbox .el-checkbox__label {
    color: #999;
    font-size: 12px;
}

.console-header i {
    cursor: pointer;
    font-size: 14px;
    transition: color 0.2s;
}

.console-header i:hover {
    color: #fff;
}

.console-body {
    height: 300px;
    overflow-y: auto;
    padding: 10px 15px;
    color: #d4d4d4;
    font-size: 13px;
    line-height: 1.5;
}

.console-body::-webkit-scrollbar {
    width: 8px;
    background-color: #1e1e1e;
}

.console-body::-webkit-scrollbar-thumb {
    background-color: #444;
    border-radius: 4px;
}

.console-empty {
    color: #555;
    animation: blink 1.5s infinite;
}

.console-line {
    word-break: break-all;
    margin-bottom: 2px;
}

.log-time {
    color: #569cd6;
    margin-right: 10px;
    opacity: 0.7;
    font-size: 12px;
}

.log-level {
    display: inline-block;
    width: 50px;
    font-weight: bold;
    margin-right: 5px;
}

.log-level.info {
    color: #9cdcfe;
}

.log-level.error {
    color: #f44747;
}

.log-level.success {
    color: #6a9955;
}

.log-level.warn {
    color: #dcdcaa;
}

.log-level.cmd {
    color: #c586c0;
}

.log-msg.cmd {
    color: #00bcd4;
    font-weight: bold;
}

/* 青色，用于显示 >>> 阶段 */
.log-msg.success {
    color: #67c23a;
}

.log-msg.warn {
    color: #e6a23c;
}

.log-msg.error {
    color: #f56c6c;
}

.log-msg.info {
    color: #d4d4d4;
}

@keyframes blink {
    50% {
        opacity: 0.5;
    }
}
</style>