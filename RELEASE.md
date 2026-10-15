# 本地构建镜像，再由 ECS 拉取运行

代码继续保存在 GitHub。应用镜像在本地构建，上传到自己的镜像仓库；ECS 拉取指定版本，不在服务器编译源码，也不下载 Node / Maven 构建镜像。

此入口复用当前 HOJ 的服务配置和数据卷，解决试用 ECS 下载构建镜像受阻的问题；它还不是完整的生产发布系统。生产备份、HTTPS、数据库迁移、自动验收和回退流程仍需配置。

当前已在 Windows PowerShell 5.1 中核对实际本地镜像并生成发布预览，在断网的临时 Ubuntu 容器中验证镜像配置、版本切换、密码保留及禁止构建的启动流程（拉取、启动和就绪检查为模拟）。自己的 ACR 仓库尚待创建，实际镜像推送及 ECS 启动、判题验收尚未完成。

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

当前面向 Ubuntu amd64 ECS，推送时指定 `linux/amd64` 平台，要求 Docker CLI 支持 `docker push --platform`（本机 Docker 29 已支持）。

已有镜像来自之前的 `616334a3827a` 源码构建，已完成本地 AC/WA 验收；当前前端和 Java 应用源码树与该版本相同。安装脚本后续的更改不代表应用本身发生了变更。增加功能后，应先提交应用代码并通过 `Start-Dev.cmd -Action Build` 构建新的本地镜像、完成验证，再以新的版本发布。发布脚本会阻止应用源码与现有镜像不一致的情况。

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
