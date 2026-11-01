# 139 服务器入口

对讲服务使用 `https://home.sunny-string.cn/ble-talkie/`。

2026-11-01 已在 139 部署入口预留配置，语音中继与 App 尚未部署：

| 请求 | 当前响应 |
| --- | --- |
| `/ble-talkie` | 308 跳转到 `/ble-talkie/` |
| `/ble-talkie/` | 200 JSON，`status=reserved`、`relayReady=false` |
| `/ble-talkie/healthz`、`/ble-talkie/api/…`、`/ble-talkie/ws` | 503 JSON，`error=relay_not_deployed` |

不能将入口的 200 响应视为语音服务已经可用。客户端应使用后续中继实现的健康与会话协议判断就绪状态。

## Nginx 接入

将本目录的 `nginx-ble-talkie-location.conf` 安装到服务器的
`/etc/nginx/snippets/ble-talkie-location.conf`，并在现有
`/etc/nginx/conf.d/sunny-string.conf` 的 HTTPS server 块中加入：

```nginx
include /etc/nginx/snippets/ble-talkie-location.conf;
```

修改前备份现有站点和同名片段。先运行 `nginx -t`，通过后 reload；失败时恢复备份并重新检查。不要新增冲突的 HTTPS server，也不要覆盖同一站点的其他路径。

`reload` 返回不等于新 worker 已就绪。需要有截止时间地检查新入口的响应类型与 JSON 内容，再判断部署成功；原站点的 HTML 回退也会返回 200，不能仅凭状态码判断。部署时已验证 Nginx 配置、HTTPS 入口、308 跳转、未部署接口的 503，以及原首页响应内容一致性。配置备份保存在服务器 `/var/backups/ble-talkie/` 下。

## 后续中继接入约定

- 公网基础路径固定为 `/ble-talkie`，应用路由、API、WebSocket、邀请链接均携带此前缀。
- 语音中继使用独立进程与本地监听端口；具体端口在部署中继时检查占用后确定。
- 代理保留完整前缀，正确传递 Host、转发来源、WebSocket Upgrade，并关闭语音连接的代理缓冲。
- 保持现有 HTTPS/TLS 入口及其他业务配置。
- 生产凭据放在服务器配置中，不提交到仓库。
- 后端上线后替换预留响应，并验证真实会话、组队和音频转发。
