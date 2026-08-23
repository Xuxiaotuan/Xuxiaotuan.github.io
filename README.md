# Xuxiaotuan

Blog：<https://xuyinyin.cn>

## 本地预览

项目使用 Ruby 3.3。未安装 Ruby 环境时可直接使用 Docker：

```bash
docker build -t xuxiaotuan-blog .
docker run --rm -it -p 4000:4000 -p 35729:35729 -v "$PWD":/site xuxiaotuan-blog
```

打开 <http://localhost:4000> 预览。Docker 本地预览不会请求 GitHub API；推送到 `main` 后，GitHub Actions 会使用临时令牌执行完整的 Jekyll 构建检查。
