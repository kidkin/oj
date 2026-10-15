# 将 ECS 备份恢复到当前 Windows 开发版

目标为本机源码开发版 `hoj-dev`，网页地址为 `http://127.0.0.1:18081`。以下命令在自己的 Windows PowerShell 中执行。操作会短暂停止开发版，请先等待正在判题的提交完成，并保持 Docker Desktop 运行。

恢复以备份时刻的数据为准，导入后的账号、密码、题目、提交和网站设置来自备份。它不会合并两边数据，也不会改变 ECS 上的站点。

## 1. 进入目录，设置路径

```powershell
Set-Location 'C:\Users\pzy13\Desktop\oj\HOJ-master'
$hojPython = 'C:\Users\pzy13\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$hojBackup = 'C:\Users\pzy13\Desktop\oj\backups\incoming\hoj-backup-20261015-152637.tar'
```

将备份包和同名 `.tar.sha256` 放在一起。此处使用本机已有 Python；如果以后换电脑，应替换为该电脑的 Python 3.8 以上运行时及实际备份路径。

确认当前开发版网页可访问。若开发版尚未启动，在上述目录执行以下命令，等待成功后继续：

```powershell
.\Start-Dev.cmd -Action Start -SkipBuild
```

## 2. 只做预检

```powershell
& $hojPython .\scripts\restore_dev.py import $hojBackup --plan-only
if ($LASTEXITCODE -ne 0) { throw '预检未通过，请先处理提示，不要执行正式恢复。' }
```

看到 `PLAN PASSED` 后再执行下一步。预检验证归档、运行镜像和拟用配置，不停止开发服务或切换数据；如缺少备份镜像，可能从备份记录的仓库下载。2026-10-15 已用本机真实 ECS 备份通过此预检，以及当前开发版的恢复前备份检查；当前开发版的实际切换留给用户执行。

该入口只接受本机 Windows Docker Desktop 的 `hoj-dev` 项目，要求当前应用与备份应用相同，或有可验证的相同 Git 源码及等价构建结果。发生应用、数据库结构或服务网段差异时会拒绝继续，不能用它代替数据库迁移流程。

## 3. 正式恢复

```powershell
& $hojPython .\scripts\restore_dev.py import $hojBackup
if ($LASTEXITCODE -ne 0) { throw '恢复未完成，请保留完整输出。' }
```

工具依次执行：

1. 在 `backups/dev-import-时间/original-backup` 制作当前开发版完整备份。
2. 保留原开发版的全部数据卷，创建另一组全新数据卷，恢复 ECS 归档并核对内容、属主和权限。
3. 复制备份中的服务凭证，固定备份对应的 MySQL、Redis、Nacos 和数据库检查镜像，再通过本地 `deploy/compose.dev-data.json` 将开发版接入新卷。
4. 使用当前开发源码对应的应用镜像和原 18081 端口启动开发版，并检查 API。普通运行错误时尝试切回原配置、原数据卷并启动；强制结束进程或断电无法保证自动切回。

本机其他构建设置保留，前端、后端和判题端仍可通过原开发入口重新构建。`Start-Dev.ps1`、`dev.sh` 和备份工具均会自动读取数据映射文件。不要删掉该文件或改用只包含基础 Compose 文件的手动命令，否则可能接入原数据卷。

导入快照的 `restored/compose.restore.json` 仅供记录与恢复取证，不要再单独启动：同一组卷已由开发版使用，不能让另一组 MySQL 同时挂载。

成功时会显示：

```text
DEV RESTORE COMPLETE: http://127.0.0.1:18081
Old development data volumes retained. Switch record: ...\switch.json
To switch back in PowerShell: ...
```

## 4. 手动验收

在浏览器打开 `http://127.0.0.1:18081`，退出旧登录状态，再用备份时的账号、密码登录。确认服务器备份中的题目、测试数据和提交记录出现，并重新提交正确程序、错误程序分别得到 AC、WA。

恢复的是 `2026-10-15 15:27` 左右的快照；之后在服务器添加的内容不应出现。本次原始快照有 1 个账号、1 道题和 1 条提交；之前独立恢复验证环境新增的验收题和提交不属于这个原始快照。

## 5. 切回原开发数据

正式恢复成功后的最后一行会打印完整 PowerShell 切回命令，直接复制该行即可。也可在同一 PowerShell 中使用：

```powershell
& $hojPython .\scripts\restore_dev.py undo --state '成功日志中 switch.json 的完整路径'
```

看到 `ORIGINAL DEV DATA RESTORED` 后再访问 18081。切回会保留导入的数据卷，不删除任何一边的数据。如果导入后又修改过服务配置，工具会拒绝自动切回，避免覆盖后续配置；此时保留提示再检查。

备份和 `previous.env` 含真实凭证，`backups/`、`deploy/.env` 与本地数据映射均被 Git 忽略，请私下保管。
