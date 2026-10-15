# HOJ 本地开发起点

本仓库的源码基线来自 [HimitZH/HOJ](https://github.com/HimitZH/HOJ)，上游提交为 `57d8b5280d96c0aa2d836b6dd1c8d8d1cd216962`。保留上游 MIT 许可证和 Git 历史，`upstream` 指向官方仓库，`origin` 指向自己的仓库。

## 第一次运行

Windows 安装并启动 Docker Desktop，使用 WSL2 的 Linux 容器模式。双击仓库内的 **Start-Dev.cmd**。首次运行需要联网下载构建镜像、npm 和 Maven 依赖，之后会使用缓存。

也可以在 PowerShell 中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Start-Dev.ps1
```

就绪后打开 <http://127.0.0.1:18081>。原版的初始超级管理员为 `root / hoj123456`。开发版数据库独立初始化，不会包含旧环境的题目和学生。

停止时双击 **Stop-Dev.cmd**，只停止开发容器，保留开发数据。关闭浏览器或命令窗口不会停止容器。

Ubuntu/Linux 开发环境需要 Docker Engine、Docker Compose v2.20 或更新版本、Git 和 OpenSSL，在仓库目录执行：

```sh
sh scripts/dev.sh start
sh scripts/dev.sh stop
```

Linux 入口用于同一套开发环境；它只监听本机地址。阿里云公网发布、域名、HTTPS、备份和版本更新脚本将在发布阶段单独配置。

## 每次修改后的操作

1. 修改 `hoj-vue` 的页面，或 `hoj-springboot` 的 Java 源码。
2. 再次运行 `Start-Dev.cmd`，自动重新构建发生变化的部分并更新开发容器。数据卷继续使用。
3. 浏览器检查功能，通过后提交 Git：

   ```sh
   git status
   git add .
   git commit -m "描述这次完成的修改"
   git push origin main
   ```

只想启动已经构建好的版本，可执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Start-Dev.ps1 -SkipBuild
```

查看状态将 `-Action Status` 传给同一脚本。日志在仓库的 `logs` 文件夹。Linux 对应 `sh scripts/dev.sh build` 和 `sh scripts/dev.sh status`。

## 构建环境与数据

- Java：容器内 Maven 3.9.9 + JDK 8；前端：容器内 Node 16.20.2 + npm 8，使用锁文件安装依赖。本机无需另装 Java、Maven 或 Node 才能完成整包构建。
- `deploy/Dockerfile.java` 从本仓库编译 API、后端和判题服务，把两个新 jar 放入锁定版本的官方运行环境。沙盒程序和各语言编译器继续使用官方镜像中的版本。
- `deploy/Dockerfile.frontend` 从本仓库编译网页，依赖打包到本地，不再通过公共 CDN 加载框架。暂未改动的滚榜页面继续来自官方前端运行镜像。
- 基础镜像均固定 SHA256 摘要。由于本机 Docker Hub 下载限流，Maven、Node 构建镜像使用 Google 官方公共缓存 `mirror.gcr.io`。可通过 Dockerfile 的 `MAVEN_IMAGE`、`NODE_IMAGE` 构建参数切换到相同内容的其他可信源。
- 开发环境的 Compose 项目名是 `hoj-dev`，使用独立的 `172.21.0.0/24` 网络和 `hoj-dev_*` 数据卷。网页端口 `18081`，后端调试端口 `16689`，均只监听本机。MySQL、Redis 和 Nacos 不映射到宿主机。
- 第一次启动时自动生成 `deploy/.env`，其中是真实服务密码。保留该文件，不要删除后重新生成密码来连接已有数据库。文件已排除 Git 和 Docker 构建上下文。它与数据库、题目测试数据分别备份，不上传 GitHub。
- 现有外层 `local-hoj` 原版环境继续由外层的“启动HOJ.cmd”管理，访问 `18080`。不要把整个外层 `oj` 文件夹上传 GitHub，仓库目录就是本文件所在目录。

## Git 回退的边界

Git 保存源码和部署模板；MySQL 数据、上传文件和判题数据位于 Docker 数据卷中。切换源码提交不会回退数据库。后续新增班级/课程表时，需要同时设计数据库迁移及备份，才能支持可靠的服务器更新与回退。

上游使用的是较旧的框架和依赖，本阶段只建立可构建、可运行的功能基线。正式面向公网发布前还需处理依赖维护、初始账号和生产配置。
