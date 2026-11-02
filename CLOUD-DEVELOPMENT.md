# 云端构建、服务器验证

本地电脑可关闭 Docker Desktop。源码保存在 GitHub；GitHub Actions 编译前后端并运行教学模块测试，Ubuntu 服务器只组装运行镜像和验证实际网站。

## 日常开发

1. 在开发分支修改并提交源码，推送 GitHub。服务器也可以编辑 `/root/oj` 下的 Git 源码，但应先切到开发分支，不要直接修改运行容器中的文件。
2. 查看仓库 Actions 中的 **HOJ cloud build**。构建使用固定的 Maven/Java 8 与 Node 16 镜像，必须通过后端测试和前端编译。
3. 下载对应提交的 `hoj-applications-提交号` 构建产物，校验 SHA256。构建包只含编译好的程序、前端资源及来源记录，没有数据库、服务密码或学生文件。
4. 将构建产物与源码提交传到服务器。`scripts/cloud_release.py` 支持校验来源、组装三种应用镜像、上传自己的 ACR、完整备份及保留数据卷的版本切换。该工具由已登录 SSH 的控制端传入明确操作，不接受 SSH 或仓库密码。
5. 发布清单写入 `releases/版本.json`，保存镜像摘要、源码提交、迁移校验值及云端测试结果；连同同名 Git 标签推送 GitHub后，服务器检出该版本并运行验证。

当前自动构建只编译程序，不会自动切换网站，也不需要把服务器密码或 ACR 密码交给 GitHub Actions。构建产物保留 7 天；长期部署和回退使用已发布的 ACR 镜像及 Git 版本标签。

## 迁移到另一台 Ubuntu 服务器

安装 Docker、登录自己的 ACR 后，克隆仓库并检出需要的发布标签。按 [Ubuntu 安装说明](./UBUNTU.md) 使用 `--prebuilt-registry` 和 `--release`，即可拉取该版镜像运行，无需在新服务器编译 Maven 或 Node。

例如当前发布命令的形式为：

```bash
bash scripts/install-ubuntu.sh --lan --port 80 --image-source aliyun \
  --prebuilt-registry crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj \
  --release v0.3.2
```

程序发布和业务数据迁移是两步：GitHub 提供源码、构建配置及版本清单，ACR 提供应用镜像；班级、课程、题目、用户及题目文件通过私有完整备份恢复，见 [备份恢复说明](./BACKUP.md)。新服务器验证通过后再切换正式入口。

本次既有站点使用保留在数据卷里的自定义业务数据库，版本验证会读取实际运行后端的数据库名称。更新不会将其替换为默认空库，也不会将旧本地演示数据写回线上。
