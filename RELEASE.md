# 本地构建镜像，再由 ECS 拉取运行

代码继续保存在 GitHub。应用镜像在本地构建，上传到自己的镜像仓库；ECS 拉取指定版本，不在服务器编译源码，也不下载 Node / Maven 构建镜像。

此入口复用当前 HOJ 的服务配置和数据卷，解决试用 ECS 下载构建镜像受阻的问题；它还不是完整的生产发布系统。[停机备份及独立恢复演练](./BACKUP.md)已实现并完成一次 ECS → Windows 本地验收；正式环境的自动异地备份、HTTPS、数据库迁移及发布回退流程仍需配置。

当前已在 Windows PowerShell 5.1 中核对实际本地镜像，在断网的临时 Ubuntu 容器中验证镜像配置、版本切换、密码保留及禁止构建的启动流程（拉取、启动和就绪检查为模拟）。2026-10-15 已向自己的 ACR 上传三个 `v0.1.0` 应用镜像，并回读核对运行平台、源码版本和文件系统层，均与本地镜像一致；Ubuntu 22.04 ECS 已完成启动及公网网页/API 检查，使用者已确认可正常使用。同日将 ECS 实际数据备份恢复到本地独立环境，通过文件一致性、管理员登录及 AC/WA 自动验收，详细结果见 [备份恢复演练](./BACKUP.md)。

在另一台全新 Ubuntu 环境安装 Docker、登录仓库并运行指定版本，见[新环境部署与更新步骤](./FRESH-DEPLOY.md)。

`v0.2.0` 已发布班级、共享课程和学生进度卡片，应用源码版本为 `7d207eea3a2f`。本次 ECS 升级的具体命令、备份、自动版本检查及回退边界见 [服务器更新步骤](./UPDATE.md)。镜像上传完成与服务器更新完成分别验收；服务器执行结果尚需确认。

## 1. 创建自己的 ACR 仓库

访问 [容器镜像服务控制台](https://cr.console.aliyun.com/)，在与 ECS 相同的地域创建个人版实例。个人版目前公测限额免费，官方限定用于开发测试，不用于正式生产业务；后续镜像托管服务应按实际使用阶段选择。单个账号个人版实例的数量有限，创建前确认地域。

创建命名空间 `kidkin-oj`，在其中创建三个私有仓库：

- `hoj-backend`
- `hoj-frontend`
- `hoj-judgeserver`

本次由本地 Docker 推送，代码源如有“本地仓库”选项可选择它，不需要配置云端自动构建。GitHub 仍负责保存源代码。

在“访问凭证”设置 Registry 登录密码。这个密码用于 Docker 登录，不要发到聊天，也不要写进 Git。登录名和域名以控制台提供的登录命令为准；新个人版常见域名形式为 `crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com`，不能直接套用旧教程里的共享域名。

参考：[创建个人版实例](https://help.aliyun.com/zh/acr/user-guide/create-a-container-registry-personal-edition-instance/)、[推送和拉取镜像](https://help.aliyun.com/zh/acr/user-guide/use-a-container-registry-personal-edition-instance-to-push-and-pull-images)。

## 2. 在 Windows 发布镜像

先在自己的 Windows 终端登录仓库；从 ACR 控制台复制命令，仅使用域名，不含命名空间。出现密码提示时输入 Registry 密码：

```powershell
docker login --username '控制台显示的登录名' crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com
```

在本地源码目录中预览发布计划，将下面的示例域名替换成真实域名：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Publish-Images.ps1 -RegistryNamespace crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com/kidkin-oj -Version v0.1.0 -PlanOnly
```

检查目标仓库和版本后，去掉 `-PlanOnly` 发布。脚本会检查三个本地应用镜像的源码版本与当前应用代码是否一致，然后为现有镜像添加发布标签并推送；它不会启动或停止本地 HOJ。发布记录保存在 Git 忽略的 `logs/release-images-版本.json` 中，不包含登录密码。

成功发布后还会生成 `releases/版本号.json`，只包含可提交的镜像地址、摘要、源码版本及迁移校验值。将它随代码提交并创建同名 Git 标签，再上传分支和标签；服务器的 `verify_release.py` 使用这个清单确认实际运行内容。不要覆盖已有版本清单。

当前面向 Ubuntu amd64 ECS，推送时指定 `linux/amd64` 平台，要求 Docker CLI 支持 `docker push --platform`（本机 Docker 29 已支持）。

如果 Windows 可以登录 ACR，而 `docker push` 在认证接口发生 TLS 超时，可以使用 [Google 官方 crane](https://github.com/google/go-containerregistry/tree/main/cmd/crane) 从 Windows 客户端直连上传。先从官方 Release 下载 Windows 版本并核对校验值，再向发布脚本增加 `-CranePath C:\实际路径\crane.exe`。它复用 Docker 凭据助手中的既有登录，不需要把密码传给脚本。

此方式将已核对的 `linux/amd64` 镜像临时导出到 `logs/image-archives/`，上传后读取远端配置，比对源码版本和每个文件系统层，并记录仓库摘要。验证成功后删除临时归档。直连设置仅作用于发布进程，不修改 Docker Desktop 的代理或重启服务；导出及上传需要额外的临时磁盘空间。

`v0.1.0` 应用源码版本为 `616334a3827a`；`v0.2.0` 为已完成本地 AC/WA、班级和课程验收的 `7d207eea3a2f`。增加功能后，应先提交应用代码并通过 `Start-Dev.cmd -Action Build` 构建新的本地镜像、完成验证，再以新的版本发布。发布脚本会阻止应用源码与现有镜像不一致的情况。

## 3. 在 ECS 上拉取并运行

确认三个镜像均已推送成功，再在 ECS 终端使用控制台提供的登录命令登录相同镜像仓库。密码只在终端提示时输入。

下面使用示例地址，实际执行前替换：

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 pull --ff-only &&
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun \
  --prebuilt-registry crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com/kidkin-oj \
  --release v0.1.0
```

安装器保留已有 `deploy/.env` 的服务密码，将三个发布镜像地址及 `HOJ_DEPLOYMENT_MODE=prebuilt` 写入该文件。启动入口只拉取应用镜像并以 `--no-build` 启动，沿用同一 Compose 项目、端口和数据卷。MySQL 和数据库检查器从 HOJ 作者的阿里云仓库获取，Nacos 和 Redis 继续复用已下载的固定版本镜像。

网页访问仍需安全组放行指定来源的 TCP 80 端口。通过 ECS 公网 IP 验证首页、登录和 AC/WA 判题后再进行后续开放。云端是独立数据库，不会自动同步 Windows 上的题目和提交。

## 4. 更新与日常操作

发布新版本后，在上面的安装命令中将 `--release v0.1.0` 改为新的版本号。三个仓库使用同一个新版本号；未变化的镜像层由仓库和本地缓存复用。不要覆盖旧版本标签。

已选择镜像运行模式后，`sh scripts/dev.sh start` 会继续使用 `.env` 中记录的发布版本，`git pull` 本身不会将新的应用源码编译进镜像。查看状态、日志和停止操作仍是：

```bash
sh scripts/dev.sh status
sh scripts/dev.sh logs
sh scripts/dev.sh stop
```

应用版本回退不等于数据库回退。涉及表结构或数据变化的版本，需要提前备份并准备相应迁移和恢复方案。不要用删除数据卷处理更新失败。

恢复服务器源码构建模式时，将 `.env` 的 `HOJ_DEPLOYMENT_MODE` 改为 `source`。此时启动会重新使用源代码构建，需要服务器可下载对应构建镜像与依赖。
