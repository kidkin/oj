# 备份到独立环境恢复验证

将当前本地库作为 ECS 正式数据使用时，采用 [本地版本与数据迁移](./MIGRATION.md)。`hoj_backup.py restore --release 版本号` 支持把开发镜像引用对应到已发布的相同镜像；配置、源码标签、文件系统层及摘要必须匹配。它不会用不同程序版本强行还原开发库。

当前采用“ECS 备份 → Windows 本地独立 HOJ 恢复”的验证路线。源站短暂停机归档后重新启动；恢复目标使用全新的项目名、数据卷和网段，仅在本机 `127.0.0.1:18082` 提供网页。不会覆盖 Windows 上原版或开发版的数据。

2026-10-15 已实际完成一次 ECS → Windows 本地恢复：备份包及全部 10 卷的文件内容、属主、权限校验通过；恢复副本在 `127.0.0.1:18083` 启动，管理员登录通过，原有 1 个账号、1 道题和 1 条提交保留。另在副本中新增私有验收题，正确程序提交 #2 得到 AC，错误程序提交 #3 得到 WA。本地原开发版数据另行保留。验收报告在 Git 忽略的 `logs/restore-ecs-acceptance.json`。

## 备份范围与限制

`scripts/hoj_backup.py` 使用 Python 3.8 以上的标准库与宿主机 Docker CLI，不需要安装 Python 扩展包。Docker 辅助容器只挂载指定数据卷和备份目录，不挂载 Docker socket。

备份包包含：

- 当前 Compose 项目的全部命名数据卷，包括 MySQL（其中也有 Nacos 数据库）、Redis、上传文件、题目测试数据以及判题和日志目录。
- 原 `deploy/.env` 及解析后的部署配置。这里包含真实服务密码，备份必须私下保存，不上传 GitHub。
- 实际运行镜像的固定仓库摘要和配置/文件系统签名。
- 归档校验值以及每卷文件内容、属主和权限的指纹。

这是同一应用与数据库镜像版本下的停机备份、恢复演练，不提供跨 MySQL 大版本迁移、在线增量备份或异地自动备份。恢复前后使用相同数据库镜像；[MySQL 官方备份说明](https://dev.mysql.com/doc/refman/8.0/en/backup-methods.html)和 [Docker 数据卷备份说明](https://docs.docker.com/engine/storage/volumes/#back-up-restore-or-migrate-data-volumes)可作参考。

源环境使用的镜像也必须能在目标环境获得。本次 ECS 的镜像已发布，可按固定摘要拉取。仅存在于本地、未发布到可访问仓库的开发镜像，不能因为生成了数据备份就自动在另一台机器获得。

## 1. 先记下需要恢复的测试数据

在源站记录一个现有测试账号、一道私有测试题的题号、测试数据和已有提交编号。若有图片或附件，记录其中一项。

也可以增加一个容易识别的标记，例如 `BACKUP_CHECK_20261015`。先完成正确程序 AC、错误程序 WA，等待判题结束后再开始备份。

## 2. 在 ECS 制作备份

在 ECS 的 root 终端执行。`python3` 不存在时，先安装：

```bash
apt-get update && apt-get install -y python3
```

更新脚本：

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 pull --ff-only
```

可先检查备份条件，此命令不会停止服务：

```bash
python3 scripts/hoj_backup.py backup --output /root/hoj-backups --check-only
```

准备好短暂停机后，执行真正备份：

```bash
python3 scripts/hoj_backup.py backup --output /root/hoj-backups
```

脚本先停止网页与应用写入，再等待 MySQL、Redis 正常退出，随后归档全部数据卷。归档成功或发生普通错误时，都会在 `finally` 中尝试从缓存镜像重新启动原先运行的服务；主机断电、进程被强制杀死等情况无法保证自动执行。数据库未正常退出时会拒绝制作物理备份。

等到最后出现 `BACKUP COMPLETE` 和文件路径，例如：

```text
BACKUP COMPLETE: /root/hoj-backups/hoj-backup-日期-时间.tar
```

确认源站重新可访问。若重启失败，保留错误输出，在原目录查看 `sh scripts/dev.sh status` 和 `sh scripts/dev.sh logs`，先恢复源站服务。

## 3. 通过 Workbench 下载备份

在阿里云控制台通过 **Workbench 终端连接**登录 ECS。新版 Workbench 在左侧“文件管理”进入文件树，旧版通过顶部“文件 → 打开新文件树”。进入 `/root/hoj-backups`，下载本次生成的两个文件：

- `hoj-backup-日期-时间.tar`
- 同名的 `.tar.sha256`

保存到自己电脑的 `C:\Users\pzy13\Desktop\oj\backups\incoming`。这个目录位于 Git 仓库外，不会被提交到项目。

Workbench 的文件传输需要其终端连接方式；SSM 会话管理方式不提供文件树。界面与限制见[阿里云官方文件管理说明](https://help.aliyun.com/zh/ecs/user-guide/manage-files)。

如果使用 SSM，或找不到文件管理入口，可以回到 ECS 实例详情页，进入“定时与自动化任务 → 上传/下载文件”，点击“上传/下载”，选择“从 ECS 下载文件”，填写完整文件路径。按页面提示同意临时 OSS 存储并提交，任务完成后通过操作列的临时下载链接下载。两个文件分别操作，临时链接在自己的浏览器中使用，不发送到聊天。该方式的 Linux 文件下载限制为 500 MB，详见[实例详情页文件传输](https://help.aliyun.com/zh/ecs/user-guide/upload-and-download-files-on-the-instance-details-page)。

只向聊天报告“已下载”和文件名，不发送服务密码或备份内部配置。如果浏览器默认放在“下载”目录，也可以报告实际文件路径。

## 4. 恢复到本地独立数据卷

本次由助手在 Windows 上执行。先确认本机内存足够；必要时停止已有本地开发版释放内存，保留其数据。不要将备份导入正在使用的 `hoj-dev_*` 或原版数据卷。

通用命令如下，替换备份文件名、Python 路径和目标目录。项目名必须以 `hoj-restore-` 开头，且不能已存在；目标目录也必须不存在：

```powershell
python scripts/hoj_backup.py restore '备份文件的绝对路径.tar' --destination '新的恢复目录' --project hoj-restore-ecs-check --subnet 172.30.240.0/24 --port 18082 --start
```

本机可使用 Codex 已有的 Python 运行时，无需再安装一套。若 `.tar.sha256` 与备份同目录，工具会自动核对整体校验值；也可用 `--sha256 完整校验值` 显式提供。

恢复会先检查包内所有文件的校验值、路径和镜像，再创建全新卷。每个卷还原后会重新读取并比对全部文件内容、属主和权限，匹配后才允许启动。已有 Docker 数据卷、重复的项目名、占用的端口或冲突的 Docker 网段均会被拒绝。

文件校验完成后，启动入口先在恢复副本中启动 MySQL，把 Nacos 保存的数据库、Redis 等地址，以及判题服务注册地址，改到新的独立网段，并更新配置校验值。这个过程仅修改恢复副本中的运行配置，账号、题目和提交数据保留。

若恢复时未加 `--start`，之后可以执行：

```powershell
python scripts/hoj_backup.py start --directory '已完成恢复的目录'
```

这个启动入口会校验副本的数据卷归属并处理上述地址变更；首次恢复后应使用它启动。

为验证期间隔离，数据库、Redis、后端及判题等业务服务使用内部 Docker 网络，仅前端额外连接访问网络以兼容 Docker Desktop 的端口转发。去掉原端口映射，仅发布 `127.0.0.1:18082`，并将服务地址改到新网段。它不用于测试邮件或远程判题等外部网络功能。镜像签名完全一致时复用本地缓存，否则拉取备份中固定摘要的镜像。

## 5. 验收恢复结果

打开 `http://127.0.0.1:18082`，按顺序检查：

1. 用备份时的账号、密码登录，查看网站设置是否保留。
2. 原测试题、测试数据、图片/附件能够打开。
3. 原提交编号、源代码和判题结果仍在。
4. 在恢复环境重新提交正确、错误程序，分别得到 AC、WA。
5. 在恢复环境增加一个新标记，确认源站没有同样变化，验证两套数据库独立。

备份完成后才在源站增加的内容，不应出现在恢复环境中。恢复点以制作备份的时间为准。

验收报告应记录备份时间、包校验值、恢复环境地址、数据比对结果、AC/WA 提交编号及失败项。仅看到备份文件或首页能打开，还不能记为恢复验收通过。

## 6. 演练结束后

停止恢复环境以释放内存，恢复之前暂停的本地开发版。恢复数据先保留，以便复查。停止命令需要明确指定恢复配置和项目名：

```bash
docker compose --project-name hoj-restore-ecs-check --file 恢复目录/compose.restore.json stop
```

基础恢复工具只恢复到全新数据卷。将 ECS 备份接入当前 Windows 开发版，可按 [开发版恢复操作](RESTORE-DEV.md) 使用专用入口；该入口保留原卷并提供切回命令。正式替换 ECS 数据库仍应另行安排切换。
