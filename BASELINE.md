# 源码开发基线验证

验证日期：2026-10-15，Windows + Docker Desktop / WSL2 Linux 引擎。

源码基线：上游 `57d8b5280d96c0aa2d836b6dd1c8d8d1cd216962`；首次开发环境提交 `aaeffcb29ae1`。

已完成：

- 从锁定的构建容器编译 Vue 前端、Java 后端、Java 判题服务，生成三个 `hoj-local/*:dev` 镜像。
- 完整开发环境启动，网页及 API 可访问，MySQL、Redis、Nacos、后端和判题服务健康。
- 超级管理员登录成功；创建 `SOURCE_AB_BASELINE` 测试题，C++ 正确程序提交 #1 返回 AC（状态 0），错误程序提交 #2 返回 WA（状态 -1）。
- 在普通 Windows 用户环境中运行启动入口成功，退出码为 0，重启服务后两条判题结果仍保留，外层原版环境继续可访问。
- Git 排除了真实 `.env`、日志、构建产物和 Docker 数据；上游与自己仓库的历史通过合并保留。

源码版程序 SHA256：

```text
backend /app.jar
c4ae74aee7e4a1ca837bc5bf8c5d4c72e9db37a6de118ed248f01f2c63bae7f5

judgeserver /judge/server/app.jar
79813c52531a03acf693d5a88901b2564a4ea805215408659a29ab3bf045cb5f
```

以上均不同于保留的原版镜像内的 jar。后续重新构建时，jar 时间戳等因素可能改变校验值，这些值用于记录本次验收。

如需重新进行会写入测试题和提交记录的完整检查，在开发环境就绪后执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Verify-Dev.ps1
```

详细结果写入本机 `logs/acceptance.json`，不进入 Git。上游 Maven 配置默认跳过单元测试，本次判题检查是实际运行后的端到端检查。

Ubuntu 服务器部署、发布镜像仓库、自动更新及数据库迁移尚未在本阶段验收。
