# 在新的 Ubuntu 环境部署已发布版本

本说明用于新建 Ubuntu 虚拟机或 ECS。安装相同版本只需要 Docker、Git、镜像仓库登录和本仓库脚本；应用在发布前已编译进镜像，服务器无需安装 Java、Maven、Node 或 IDE。

当前已发布版本为 `v0.1.0`，三个应用镜像均已上传并核对内容。2026-10-15，Ubuntu 22.04 ECS 已返回安装完成，外部直连首页和 `/api/get-website-config` 均返回 HTTP 200，接口业务状态为 200。管理员登录和 AC/WA 判题由使用者自行验收，尚未记录为通过。

## 1. 准备系统和访问范围

选择 Ubuntu 22.04 或 24.04 LTS、amd64/x86_64。本次 2 核、4 GiB ECS 已完成启动与网页检查；实际并发能力需要另外测试。磁盘需要容纳 Docker 镜像以及后续题目、测试数据和数据库。

以下命令统一在新 Ubuntu 的 root 终端执行。普通账号先运行 `sudo -i`；已经是 root 时无需再次切换。这样 Docker 登录和安装器使用同一份 root 仓库凭据。

云服务器安全组按实际访问者来源放行 TCP 80，SSH 22 限制为自己的可信来源。MySQL、Redis、Nacos 和判题服务无需开放公网端口。虚拟机则使用可达的虚拟机 IP，或另行配置 NAT 端口转发。

## 2. 安装 Docker 和基础工具

已有可用 Docker Engine、Buildx 和 Compose 插件时，跳过 Docker 软件源配置与安装，保留 Git、curl、证书和 OpenSSL。

全新系统先安装基础工具：

```bash
apt-get update &&
apt-get install -y ca-certificates curl git openssl
```

以下使用 [Docker 官方 Ubuntu apt 安装方法](https://docs.docker.com/engine/install/ubuntu/#install-using-the-apt-repository)。在全新系统执行；已有其他 Docker 安装时先核对，不要直接卸载正在使用的软件。

```bash
install -m 0755 -d /etc/apt/keyrings &&
curl -fsSL --retry 3 --connect-timeout 20 --max-time 180 https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc &&
chmod a+r /etc/apt/keyrings/docker.asc
```

上一步成功后，复制完整代码块，包括最后单独一行的 `EOF`：

```bash
tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
```

然后安装并检查：

```bash
apt-get update &&
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin &&
systemctl enable --now docker &&
docker info --format '{{.OSType}}' &&
docker compose version &&
docker buildx version
```

应显示 `linux` 和 Compose、Buildx 的版本。项目要求 Compose 2.20 或以上。如果 Docker 官方软件源或 GitHub 下载超时，需要先解决新环境的网络连通性；旧电脑的代理不会自动同步，ACR 中的应用镜像也不包含 Docker 安装软件包。

## 3. 从自己的 GitHub 下载脚本

全新环境、`/root/oj` 尚不存在时执行：

```bash
git -c http.version=HTTP/1.1 clone https://github.com/kidkin/oj.git /root/oj &&
cd /root/oj
```

已有这个仓库时改为：

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 pull --ff-only
```

## 4. 登录自己的 ACR

```bash
docker login crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com
```

按提示输入 ACR“访问凭证”中的登录名和 Registry 固定密码。看到 `Login Succeeded` 后继续。每台新机器分别登录；密码不放进脚本、Git 或聊天。

GitHub 保存代码和部署脚本，私有 ACR 保存三个可运行的应用镜像。仅下载 GitHub 仓库并不能代替私有镜像的拉取权限。

## 5. 一条命令启动指定版本

```bash
cd /root/oj &&
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun --prebuilt-registry crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj --release v0.1.0
```

首次启动会下载应用及基础服务镜像、初始化数据库。出现 `Installation complete` 后访问：

- ECS：`http://新服务器的公网IP`。安装日志可能显示内网 IP，外部访问以云控制台的公网 IP 为准。
- Ubuntu 本机：`http://127.0.0.1`。
- 虚拟机外部：`http://可达的虚拟机IP`，实际访问方式取决于虚拟机网络。

全新数据库初始超级管理员为 `root / hoj123456`。登录后修改密码并检查其他默认管理员账号，再检查新建题目、正确程序 AC、错误程序 WA。

日常检查与停止、启动：

```bash
cd /root/oj
sh scripts/dev.sh status
sh scripts/dev.sh logs
curl -fsS http://127.0.0.1/api/get-website-config
sh scripts/dev.sh stop
sh scripts/dev.sh start
```

`stop` 保留业务数据。`start` 在此部署模式下继续拉取并启动已配置的发布版本。

## 6. 发布新功能后的更新

本地修改并提交代码，构建新镜像、完成本地验证，然后按照 [镜像发布说明](./RELEASE.md) 上传新的版本。三个应用仓库统一使用新的版本号，例如 `v0.1.1`；这个版本必须已实际发布，才能在服务器使用。

更新前备份数据库、题目与测试数据、其他业务文件和 `deploy/.env`。[停机备份及独立恢复工具](./BACKUP.md)已完成一次 ECS → Windows 本地验收，数据库迁移、覆盖现有站点的恢复及自动发布回退仍需另外安排。准备好后，在原服务器执行：

```bash
cd /root/oj &&
git -c http.version=HTTP/1.1 pull --ff-only &&
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun --prebuilt-registry crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj --release v0.1.1
```

安装器沿用原有服务密码和数据卷，并切换应用镜像版本。`git pull` 本身只更新代码与脚本，运行中的应用不会自动变成新功能版本。涉及数据库结构变化时，仍需配套迁移，不能只依赖切换镜像。

## 7. 区分重新部署和迁移数据

新环境首次安装会建立独立数据库，账号、题目、课程、班级和提交记录不会从另一台电脑或服务器自动同步。班级和课程功能还需后续开发。

如果只是再建一套空环境，按上面的流程部署即可。如果要将现有站点搬到新服务器，应另外安排停写或一致性备份、恢复全部业务数据和原 `deploy/.env`、验证后切换访问地址。GitHub 和 ACR 都不能代替业务数据备份。

完整新环境流程还没有在另一台全新服务器逐项执行；当前已有的实测证据是本次 ECS 的镜像拉取、安装完成和公网网页/API 检查。
