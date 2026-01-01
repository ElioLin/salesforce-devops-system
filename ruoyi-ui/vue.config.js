const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
'use strict'
const path = require('path')

function resolve(dir) {
  return path.join(__dirname, dir)
}

const CompressionPlugin = require('compression-webpack-plugin')

const name = process.env.VUE_APP_TITLE || 'Salesforce DevOps' // 网页标题

// 【关键修改】不要写 localhost，强制使用 127.0.0.1 避免 Node.js 解析为 IPv6 导致连接重置
const baseUrl = 'http://127.0.0.1:8080' 

const port = process.env.port || process.env.npm_config_port || 80 // 端口

module.exports = {
  publicPath: process.env.NODE_ENV === "production" ? "/" : "/",
  outputDir: 'dist',
  assetsDir: 'static',
  productionSourceMap: false,
  transpileDependencies: ['quill'],
  configureWebpack: {
    name: name,
    resolve: {
      alias: {
        '@': resolve('src')
      }
    },
    plugins: [
      new MonacoWebpackPlugin({
        languages: ['java', 'apex', 'xml', 'javascript', 'json'] 
      })
    ]
  },
  devServer: {
    host: '0.0.0.0',
    port: port,
    open: true,
    proxy: {
      // 【关键修改】专门针对 WebSocket 的代理配置
      // 必须放在 '/' 或通用配置之前，以确保优先匹配
      [process.env.VUE_APP_BASE_API + '/websocket']: {
        target: baseUrl,
        changeOrigin: true,
        ws: true, // 开启 WebSocket 代理
        secure: false,
        pathRewrite: {
          ['^' + process.env.VUE_APP_BASE_API]: ''
        },
        // 增加日志，方便调试看代理是否生效
        onProxyReqWs: (proxyReq, req, socket, options, head) => {
            socket.on('error', (error) => {
                console.error('WebSocket Proxy Error:', error);
            });
        }
      },
      // 通用 API 代理
      [process.env.VUE_APP_BASE_API]: {
        target: baseUrl,
        changeOrigin: true,
        ws: false, // 普通 HTTP 接口不需要开启 ws，避免冲突
        pathRewrite: {
          ['^' + process.env.VUE_APP_BASE_API]: ''
        }
      },
      // springdoc proxy
      '^/v3/api-docs/(.*)': {
        target: baseUrl,
        changeOrigin: true
      }
    },
    disableHostCheck: true
  },
  css: {
    loaderOptions: {
      sass: {
        sassOptions: { outputStyle: "expanded" }
      }
    }
  },
  chainWebpack(config) {
    config.plugins.delete('preload') 
    config.plugins.delete('prefetch') 

    config.module
      .rule('svg')
      .exclude.add(resolve('src/assets/icons'))
      .end()
    config.module
      .rule('icons')
      .test(/\.svg$/)
      .include.add(resolve('src/assets/icons'))
      .end()
      .use('svg-sprite-loader')
      .loader('svg-sprite-loader')
      .options({
        symbolId: 'icon-[name]'
      })
      .end()

    config.when(process.env.NODE_ENV !== 'development', config => {
          config
            .plugin('ScriptExtHtmlWebpackPlugin')
            .after('html')
            .use('script-ext-html-webpack-plugin', [{
              inline: /runtime\..*\.js$/
            }])
            .end()

          config.optimization.splitChunks({
            chunks: 'all',
            cacheGroups: {
              libs: {
                name: 'chunk-libs',
                test: /[\\/]node_modules[\\/]/,
                priority: 10,
                chunks: 'initial' 
              },
              elementUI: {
                name: 'chunk-elementUI', 
                test: /[\\/]node_modules[\\/]_?element-ui(.*)/, 
                priority: 20 
              },
              commons: {
                name: 'chunk-commons',
                test: resolve('src/components'), 
                minChunks: 3, 
                priority: 5,
                reuseExistingChunk: true
              }
            }
          })
          config.optimization.runtimeChunk('single')
    })
  }
}