# aiwei-tools-service 生产部署

## 运行位置

- 主机：`118.145.144.238`（Ubuntu 云服务器）
- 部署目录：`/home/ubuntu/aiwei/deploy/aiwei-tools-service`
- Compose 服务与容器：`aiwei-tools-service`
- Docker 网络：`deploy_aiwei-network`
- 容器端口：`8095`，仅在 Docker 网络中暴露，不映射宿主机公网端口
- 公网入口：`https://nas.aiwei616.com/tools-api/`

## 安全与资源

- `/api/**` 必须携带 `X-Tools-Api-Key`
- API Key 仅保存在服务器 `aiwei-tools-service/.env.local`，文件权限为 `600`
- 健康检查 `/actuator/health` 不要求 API Key
- 内存上限 `640m`，JVM 堆上限 `512m`
- CPU 上限 `0.75`
- 状态目录挂载到 `aiwei-tools-service/data`

## 调用地址

AINAS 远程调用：

```text
AINAS_TOOLS_SERVICE_BASE_URL=https://nas.aiwei616.com/tools-api
```

aiweios-server 后续接入时优先走 Docker 内网：

```text
AIWEI_TOOLS_BASE_URL=http://aiwei-tools-service:8095
```

两个调用方都需要配置与服务器一致的 `AIWEI_TOOLS_API_KEY`。

## 当前验收结果

- HTTPS 健康检查返回 `UP`
- 未授权工具调用返回 HTTP `401`
- `stock.quote(hk01810)` 调用成功
- 股票数据源为 `tencent_quote`
- Nginx 与工具服务容器运行正常

本环境按要求不保留发布备份。更新时应先在本地完成测试和打包，再原子替换 JAR、
重建单个工具服务容器并执行健康、鉴权和代表性工具冒烟。
