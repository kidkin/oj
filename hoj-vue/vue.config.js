const CompressionWebpackPlugin = require('compression-webpack-plugin')

// Bundle dependencies locally so the site can run without public CDN access.
module.exports = {
  publicPath: '/',
  assetsDir: 'assets',
  productionSourceMap: false,
  devServer: {
    open: false,
    host: '127.0.0.1',
    port: 8066,
    proxy: {
      '/api': {
        target: process.env.HOJ_BACKEND_URL || 'http://127.0.0.1:16689',
        changeOrigin: true
      }
    }
  },
  configureWebpack: config => {
    if (process.env.NODE_ENV === 'production') {
      config.plugins.push(new CompressionWebpackPlugin({
        filename: '[path].gz[query]',
        algorithm: 'gzip',
        test: /\.(js|css|html|svg|json)$/,
        threshold: 10240,
        deleteOriginalAssets: false,
        minRatio: 0.8
      }))
    }
    if (process.env.ANALYZE === 'true') {
      const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer')
      config.plugins.push(new BundleAnalyzerPlugin({ analyzerMode: 'static', openAnalyzer: false }))
    }
  }
}
