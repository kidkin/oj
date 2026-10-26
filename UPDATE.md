# 从 v0.1.0 更新到 v0.2.0

当前准备的 v0.3.1 以及“将本地数据作为正式站数据、保留服务器旧库”的步骤见 [本地版本与数据迁移](./MIGRATION.md)。下面保留 v0.2.0 的历史更新记录；不要用旧版本号部署当前代码。

本次发布包含班级与成员、教师授权、共享课程和学生进度卡片。三个 `v0.2.0` 应用镜像已上传到自己的 ACR，并逐一回读校验源码版本和文件系统层；源码版本为 `7d207eea3a2f`。发布清单位于 `releases/v0.2.0.json`，Git 标签为 `v0.2.0`。

更新使用服务器已有的数据库、题目、提交和 `deploy/.env`。本地开发库中的班级、课程、学生不会随镜像上传。新后端首次启动时执行新增的 V001/V002 教学表迁移；不重新导入原始数据库。

以下全部在现有 ECS 的 **Ubuntu root 终端**执行。更新与停机备份期间会短暂无法访问，先等待正在进行的判题结束。

## 1. 获取指定版本的脚本

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 fetch origin tag v0.2.0 &&
git switch --detach v0.2.0
```

此时只切换代码和脚本，正在运行的旧版容器继续运行。若 Git 提示本地修改冲突，先保留输出处理，不执行强制覆盖。`deploy/.env` 和数据卷不受 Git 切换影响。

后续采用发布标签更新，不使用 `git pull` 跟随开发分支；处于 detached HEAD 是固定发布版本的预期状态。

## 2. 完整备份服务器当前数据

```bash
cd /root/oj &&
python3 scripts/hoj_backup.py backup --output /root/hoj-backups
```

等到 `BACKUP COMPLETE` 和 `SHA256`，并确认原服务已重新启动，再继续。这个备份工具会短暂停止服务、归档全部数据卷和配置，并尝试自动启动原服务。保留本次生成的 `.tar` 和 `.tar.sha256`，另下载一份到本机。

如果提示找不到 Python：`apt-get update && apt-get install -y python3`，然后重新运行备份。备份和配置含服务密码，不上传 GitHub。

## 3. 拉取并启动新版

```bash
cd /root/oj &&
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun \
  --prebuilt-registry crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj \
  --release v0.2.0 &&
python3 scripts/verify_release.py --release v0.2.0
```

安装器保留原服务密码、Compose 项目和数据卷，只切换应用镜像。服务器不会编译 Node/Maven 源码。拉取提示未授权时，在同一个 root 终端重新执行：

```bash
docker login crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com
```

在终端输入 ACR 用户名和 Registry 固定密码，成功后重新执行第 3 步；不要发送密码。

成功时版本检查的结尾应为：

```text
PASS IMAGE: hoj-backend 7d207eea3a2f
PASS IMAGE: hoj-frontend 7d207eea3a2f
PASS IMAGE: hoj-judgeserver 7d207eea3a2f
PASS SCHEMA: V001__class_members, V002__shared_courses
PASS API: http://127.0.0.1:80
RELEASE VERIFIED: v0.2.0
```

`verify_release.py` 只读检查：容器实际使用的发布镜像、源码标签、镜像层及 ACR 摘要，服务运行及健康检查状态、数据库迁移校验值以及前端 HTTP/API。它不会创建账号、修改数据或把密码打印出来。服务仍在初始化时可稍后单独重跑检查。

## 4. 从公网验收

打开 <http://121.43.153.95>，按 `Ctrl+F5` 强制刷新，然后：

1. 用服务器原账号登录，确认旧题目、附件和已有提交仍在。
2. 超级管理员顶部应出现“班级”“课程”；新建一个测试班级和课程，加入一题公开题，发布并添加到班级。
3. 将服务器已有的测试学生账号加入班级，以学生账号登录：只看到已加入的班级卡片，教学导航只有“班级”，进入后看到课程卡片和本人完成情况。
4. 用测试学生做一道公开题，提交正确程序应为 AC，刷新课程卡片后完成数增加；再提交错误程序确认 WA。
5. 两个班引用同一课程后，修改课程题目顺序，分别刷新，核对两班统一更新。

如果首页能打开但旧数据不在，先停止进一步操作并核对项目和数据卷，不重新初始化数据库。版本检查通过说明代码及迁移已生效，仍须上述浏览器验收确认真实业务行为。

## 5. 失败时保留现场与回退

先查看以下输出；日志可能包含私密运行信息，向聊天发错误片段前移除密码、令牌。

```bash
cd /root/oj
sh scripts/dev.sh status
```

更完整的服务日志在 `sh scripts/dev.sh logs` 和本次安装提示的 `/root/hoj-install-*.log`。

若本次升级失败、尚未使用新的教学功能，可先切回原应用镜像，保留数据库及新增教学表：

```bash
cd /root/oj &&
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun \
  --prebuilt-registry crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj \
  --release v0.1.0
```

这是应用回退，不会撤销数据库迁移或恢复旧数据。如果已经授予教师权限、写入新教学数据，回退前还须核对旧版权限处理的兼容性。需要完整回到升级前的数据时，使用第 2 步备份先在独立环境恢复验证，再安排数据切换，接受恢复点之后新增数据的损失。

## 6. 以后每次更新

本地修改 → 提交 Git → 构建并验收 → 发布新的镜像标签（例如 `v0.2.1`）和对应 Git 标签 → ECS 获取该标签 → 备份 → 用新 `--release` 启动 → 运行版本检查 → 浏览器验收。

每个正式发布版本都提供自己的 `releases/版本号.json`。三个应用镜像统一使用同一个新版本号，保留旧标签，避免覆盖 `v0.2.0`。代码的 GitHub 标签与可执行镜像的 ACR 标签须一起发布；仅 `git pull` 不会更新运行中的程序。

## 本机升级演练结果

2026-10-15，已将先前 ECS 备份还原到独立的 `hoj-restore-v020-check` 项目，在本机 `127.0.0.1:18084` 先启动原 `v0.1.0`，再切换到从 ACR 拉取的 `v0.2.0`。原 1 个账号、1 道题、1 条提交的 ID 均保留；V001/V002 首次迁移及 `verify_release.py` 的镜像、摘要、迁移、HTTP/API 检查通过。原管理员登录通过，另加测试题后，正确程序提交 #2 得到 AC，错误程序 #3 得到 WA。

版本校验器的 5 项测试通过，覆盖旧镜像重新贴标签、源码/文件系统不匹配、错误仓库摘要以及迁移缺失或变更的情况。发布器自动生成清单的内容与本次已发布清单一致。演练副本完成后停止，数据保留；当前开发环境继续运行。实际 ECS 升级结果须由第 3、4 步确认，不能用本地演练替代。
