# 当前本地版本与数据迁移到 ECS

本次使用当前 Windows 开发库作为正式站数据，服务器原数据卷及升级前备份完整保留。目标服务器为 Ubuntu 22.04、`121.43.153.95`，现有仓库目录为 `/root/oj`。本地账号功能已关闭公开注册，并提供“后台管理 → 常用设置 → 用户管理 → 新建账号”，可指定用户名、密码及可选真实姓名；创建者须有用户管理权限，新账号默认是普通用户。

准备版本为 **v0.3.1**。它需要在你回来后运行发布入口，才能成为可供 ECS 下载的发布版。不要将“本地已准备好”当成“服务器已更新”。原计划 v0.3.0 在暂停前只上传了旧后端镜像，没有形成完整发布；保留该标签，不覆盖它。

## 1. 回来后先发布应用和源码（Windows）

本地构建、账号验收和数据备份完成后，数据包位于：

```text
C:\Users\pzy13\Desktop\oj\HOJ-master\backups\ready-v0.3.1\hoj-migration-v0.3.1.zip
```

先启动 Docker Desktop，随后双击外层 `oj\setup\04-publish-prepared.cmd`；也可以在 Windows PowerShell 执行：

```powershell
cd C:\Users\pzy13\Desktop\oj\HOJ-master
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Publish-PreparedRelease.ps1 -Publish
```

该入口核对备份包和准备时的三个镜像，向自己的 ACR 发布 `v0.3.1`，提交不含密码的发布清单，向 GitHub 上传当前分支和 `v0.3.1` 标签。它不操作 ECS。看到 `RELEASE PUBLISHED: v0.3.1` 才继续下一步。

不加 `-Publish` 时只预览，不上传。如果本地镜像或应用代码已修改，入口会拒绝发布旧准备包；应重新构建、验收并生成新包。数据包代表打包时的数据，之后新增的本地数据不会自动进入这个包。

ACR 提示未登录时，在 Windows PowerShell 执行以下命令，在终端输入 ACR 用户名及 Registry 密码，成功后重新运行发布入口。不要将密码发到聊天。

```powershell
& "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe" login crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com
```

入口默认使用本机已有的 Git 代理 `127.0.0.1:10808`。如果已停用该代理，可在有 GitHub 直连能力时增加 `-GitProxy ''`；不关闭证书校验。若镜像已上传、Git 推送失败，修复网络后重复执行同一入口，它会核对已有摘要并继续上传标签，不覆盖其他版本。

## 2. 上传一个私有数据包（阿里云 Workbench）

在阿里云 **Workbench 终端连接** 中登录该实例，先执行：

```bash
install -d -m 700 /root/hoj-incoming
```

然后上传 `hoj-migration-v0.3.1.zip` 到 `/root/hoj-incoming/`：Workbench 2.0 使用左侧“文件管理”，打开该目录后点击“上传文件”；1.0 使用顶部“文件 → 打开新文件树”，在目标目录上右键“上传文件”。如果当前是会话管理连接，没有文件上传入口，应重新选择 Workbench 终端连接。阿里云的[文件管理说明](https://help.aliyun.com/zh/ecs/user-guide/manage-files)提供了两种界面的截图。

这个包包含账号、题目、提交及服务配置，应保存在私有目录，不上传 GitHub、不放进网站下载目录。无需单独解压或逐一传输里面的数据卷文件。

## 3. 在 ECS 恢复、验证并切换（Ubuntu root 终端）

先等待正在进行的提交判题完成，并安排几分钟维护时间。不要同时运行两套完整服务；本流程在启动导入数据前停止旧服务，适用于当前 2 核 4 GiB 试用服务器。原数据保留在原数据卷中。

发布成功、数据包上传完成后，复制整段：

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 fetch origin tag v0.3.1 &&
git switch --detach v0.3.1 &&
python3 scripts/restore_server.py import \
  /root/hoj-incoming/hoj-migration-v0.3.1.zip \
  --release v0.3.1 --publish
```

脚本会按顺序：

1. 验证上传包、备份内容和镜像是否与本地准备版本一致；拉取失败时不切换数据。
2. 备份服务器当前全部数据卷及配置，保存到 `/root/hoj-migrations/import-v0.3.1-时间/previous-backup/`。
3. 将本地数据恢复到全新的数据卷，逐卷验证文件内容、所有者与权限，保留原卷。
4. 停止旧服务，使用新卷和对应服务密码，在 `127.0.0.1:18082` 启动导入后的站点；检查账号、题目、提交及教学数据的数量和主键、三个镜像、V001~V005 迁移及网站 API。
5. 若导入的网站 root 仍使用初始密码，终端会要求你输入两次新密码，6~20 位；这是网站管理员密码，不是 MySQL 密码。密码不回显、不写入日志。旧 root 登录会话会失效。
6. 验证通过后开放原来的 80 端口，再检查一次实际运行版本。任一步切换失败时尝试恢复原服务、原配置及原数据卷。

如果提示 ACR 拉取未授权，在同一个 Ubuntu root 终端重新登录仓库，再重试导入命令：

```bash
docker login crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com
```

成功时应看到：

```text
LOCAL DATA RESTORE VERIFIED: ...
RELEASE VERIFIED: v0.3.1
MIGRATION PUBLISHED: v0.3.1 on port 80
SWITCH RECORD: /root/hoj-migrations/import-v0.3.1-时间/switch.json
```

目录中保留 `previous.env`、原运行配置、原卷名称、旧库备份及切换记录，含密码，不对外分享。若导入在前置检查阶段失败，修复原因后重试会创建新的恢复目录；不需要删除旧卷。

## 4. 从公网验证

打开 <http://121.43.153.95> 并按 `Ctrl+F5`，使用本地原账号登录；网站 root 使用上一步设置的新密码。

- 首页仅有轮播图和最近一周过题榜单；顶部有班级、课程、周周练等当前入口。
- 管理员在用户管理中可看到新建账号按钮，批量导入和随机生成仍保留；退出后没有公开注册入口。
- 原来的班级、课程、上传图标及课程题目顺序保留；学生的完成进度与本地一致。
- 用测试学生做一道公开题，确认 AC/WA 判题、课程进度和排名正常；教师进度表能生成和下载。当前公网 HTTP 地址下，浏览器可能限制图片剪贴板，使用“下载图片”验证即可。

迁移脚本的自动检查不能替代这一步业务操作。当前版本在真实 ECS 的执行与公网验收，须等上述命令实际完成后确认。

## 5. 回到服务器原数据

若需要撤销本次迁移，等待判题结束，在同一仓库目录运行：

```bash
cd /root/oj &&
python3 scripts/restore_server.py undo --latest
```

该命令恢复切换前保存的原配置、原镜像及原数据卷，并验证原数据主键；导入的新卷仍保留。回退后使用迁移前服务器的账号密码。它不会合并迁移后的新提交到旧库。若后来已再次更新部署配置，安全检查会拒绝直接回退，此时按保存的 `switch.json` 单独处理，不强行覆盖。

## 6. 此后只更新功能、保留正式站数据

平时更新应继续使用正式站现有数据，不重复覆盖本地开发库。流程为：本地开发与验收 → 发布新的 Git/ACR 同名版本 → ECS 获取该标签 → 服务器备份 → 运行指定版本安装入口 → 版本检查 → 公网业务验收。

本次采用的外部数据卷配置会被日常启动、备份和安装入口继续使用。普通更新沿用同一个数据库，班级、课程和学生提交不会被本地测试库替换。只有明确需要整库迁移时才再次使用本文件的导入流程。

## 本地准备入口

未来需要重新打包当前开发数据时，在 Windows PowerShell 使用本机 Python 运行，例如：

```powershell
cd C:\Users\pzy13\Desktop\oj\HOJ-master
& "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" .\scripts\prepare_migration.py --version v0.3.1 --output backups/ready-v0.3.1-new
```

它会短暂停止本地服务，备份后自动恢复；输出目录必须是新目录。然后向发布入口传入 `-PreparationDirectory backups/ready-v0.3.1-new`。如果该版本已发布过，应使用新版本号，保留已有发布记录。

## 2026-10-26 本机验收结果

- 后端 56 项测试通过；新建账号、指定密码登录、重复用户名保护、输入校验、学生和教师无创建权限均通过真实 API 验收。后台单账号表单与空字段拦截已在浏览器验证。
- 迁移包包含 18 个账号、23 道题、12 条提交、5 个班级、8 门课程；原有题目、课程、图标、班级和演示数据保留。
- 新备份已恢复到本机独立副本，全部 10 卷的文件内容、属主与权限指纹一致；数据数量和主键、课程权限、新账号登录通过，真实提交分别得到 AC 和 WA。
- 将 2026-10-15 的 ECS 旧备份恢复到另一独立副本，再使用当前新镜像启动，V001~V005 迁移通过，原 1 个账号、1 道题、1 条提交的主键保留；旧配置没有重新打开注册。
- 演练副本已停止且数据保留；原开发站已重新启动，原数据主键及数量未改变。Python 备份与迁移工具的 28 项测试通过，另 1 项默认跳过的辅助容器测试由上述真实整库恢复演练覆盖。

以上均为本机演练。当前 ECS 的实际切换、真实网络下载及公网业务验收，仍待你回来运行前述发布和迁移流程后确认。报告保存在本机 Git 忽略的 `logs/account-provisioning-acceptance.json`、`logs/server-migration-unit-tests.log` 及 `logs/prepared-migration-rehearsal.json`。
