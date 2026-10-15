# 在 Ubuntu 虚拟机中安装与验证 HOJ

此入口安装当前源码开发版，用于验证 Ubuntu 环境。安装器用 [Docker 官方脚本](https://docs.docker.com/engine/install/ubuntu/#install-using-the-convenience-script) 配置软件源，再安装 Engine、CLI、containerd、Buildx 和 Compose；已有可用 Docker 时复用。正式阿里云发布、域名 HTTPS、发布镜像和数据库迁移将在发布阶段配置。

目前已通过 Ubuntu 24.04 容器中的脚本语法、配置、随机密码与文件权限、重复初始化和缺失密码文件保护检查，并预演了 Docker 官方软件源配置。完整的新虚拟机安装及该虚拟机内的 AC/WA 判题仍待本次人工验证；Ubuntu 容器检查不等同于完整虚拟机验收。

## 1. 准备虚拟机

建议 Ubuntu **24.04 LTS Desktop、amd64/x86_64**，分配 **4 个 CPU、8 GB 内存、80 GB 动态磁盘**；脚本也支持 Ubuntu 22.04 LTS amd64。使用普通 Ubuntu 安装，不需要安装 Docker Desktop、Java、Maven、Node 或 IDE。

网络先用 NAT，确认 Ubuntu 浏览器能上网。先在 Ubuntu 自己的浏览器访问网站最方便；如果使用 Ubuntu Server 或希望从 Windows 访问，再看第 3 步。

这台 Windows 电脑约 16 GB 内存，启动虚拟机前建议先退出 Windows Docker Desktop，给虚拟机腾出内存。退出不会删除原来的 HOJ 数据。

## 2. 复制执行安装命令

在 **Ubuntu 的终端**打开后，依次执行：

```bash
sudo apt-get update &&
sudo apt-get install -y ca-certificates curl &&
curl -fL --retry 3 --connect-timeout 20 --max-time 180 https://raw.githubusercontent.com/kidkin/oj/main/scripts/install-ubuntu.sh -o install-hoj.sh &&
bash install-hoj.sh
```

输入 Ubuntu 登录密码时，终端不显示星号是正常的。不要把整条安装命令放到 Windows PowerShell 执行。

安装器会准备 Git / curl / OpenSSL，将代码下载到 `~/oj`，安装 Docker Engine、Buildx 和 Compose 插件，自动生成 `~/oj/deploy/.env`，从源码构建前端、后端与判题服务，然后启动 MySQL、Redis、Nacos 及应用。它会使用 `sudo` 执行需要管理员权限的 Docker 命令，无需注销、重启或把用户加入 Docker 组。

首次运行需要下载较大的镜像和依赖。保留终端，直到出现 **Installation complete** 和访问地址。成功后，在 **Ubuntu 虚拟机内的浏览器**打开：

```text
http://127.0.0.1:18081
```

超级管理员初始账号：**root / hoj123456**。这是全新的虚拟机数据库，不会自动带入 Windows 环境中的测试题和账号数据。

## 3. 可选：从 Windows 浏览器访问虚拟机

安装时将最后一条命令换成：

```bash
bash install-hoj.sh --lan
```

已安装过时可以执行：

```bash
cd ~/oj
bash scripts/install-ubuntu.sh --lan
```

该选项把网页发布到虚拟机所有网卡的 `18081` 端口；数据库、Redis、Nacos 不对外映射。测试期间只在可信的虚拟机网络内使用初始账号。

成功后使用安装器输出的地址，例如 `http://192.168.1.100:18081`。**Windows 中的 127.0.0.1 是 Windows 自己，不是 Ubuntu 虚拟机。**

如果虚拟机使用桥接模式，确保 Windows 能访问该虚拟机 IP；如果使用 NAT，是否能直接访问取决于虚拟机软件。无法直连时，配置 NAT 端口转发：Windows 本机端口 `18082` → Ubuntu `18081`，然后 Windows 浏览器访问 `http://127.0.0.1:18082`。Ubuntu 内部访问地址不变。

如需改变 Ubuntu 自身网页端口：`bash install-hoj.sh --lan --port 18082`。这些选项保存在 `.env` 中，后续启动继续生效。也可编辑 `.env` 将 `HOJ_WEB_BIND` 恢复为 `127.0.0.1` 后再次启动。

## 4. 验证与日常操作

```bash
cd ~/oj
sh scripts/dev.sh status    # 查看容器，mysql-checker 执行完成后退出是正常的
sh scripts/dev.sh logs      # 查看最近日志
sh scripts/dev.sh stop      # 停止，保留数据库和题目数据
sh scripts/dev.sh start     # 从源码构建并启动；已有缓存会复用
```

首次人工验收建议按顺序检查：首页能打开 → root 登录后台 → 新建 A+B 题目及测试数据 → 提交正确程序得到 AC → 提交错误程序得到 WA。已有题目时，停止并再次启动，确认题目、账号和提交记录仍在。

下载新的测试代码并重新构建：

```bash
cd ~/oj
git pull --ff-only
sh scripts/dev.sh start
```

此命令用于当前开发环境的测试更新；生产数据库迁移及版本回退另行设计。

## 5. 遇到问题

- 完整安装日志保存在 Ubuntu 用户家目录的 `hoj-install-日期-时间-进程号.log`。保留报错和这份日志。
- `curl` / Git / 镜像 / npm / Maven 下载超时，表示对应网络链路失败；Windows 的代理设置不会自动应用到 Ubuntu 虚拟机。先确认虚拟机可访问 GitHub、`get.docker.com`、`download.docker.com`、镜像仓库、npm 和 Maven 源，再重试安装命令。不要反复删除数据或重装 Ubuntu。
- Docker Hub 提示拉取限流时，可在 Ubuntu 运行 `sudo docker login` 登录自己的 Docker Hub 账号后重试。
- 检测到旧 Docker 安装冲突时，安装器会停止并提示，不会自动卸载其他软件。新建干净的 Ubuntu 虚拟机通常更便于本次验证。
- `deploy/.env` 里面是真实服务密码，请保留；不要删除它后重新生成密码连接原数据。数据卷中已有 MySQL 数据而 `.env` 缺失时，Linux 入口会要求先恢复原文件。
- 不要执行带 `down -v` 或 `docker volume prune` 的清理命令来处理启动错误，这会删除业务数据。

源代码由 Git 保存；数据库、上传文件、测试数据位于 Docker 数据卷中，和 `.env` 分别备份。Git 回退不会回退数据库。
