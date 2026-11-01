# 139 上的对讲服务

公网入口为 `https://home.sunny-string.cn/ble-talkie/`。当前已由独立 Go 进程提供服务，替换了最初的路径预留响应。

| 路径 | 用途 |
| --- | --- |
| `/ble-talkie/` | 服务信息与入口链接 |
| `/ble-talkie/healthz` | 就绪检查，正常时 `200 {"status":"ready"}` |
| `/ble-talkie/download` | 当前测试 APK |
| `/ble-talkie/invite?code=…` | 打开 App 的邀请入口与安装链接 |
| `/ble-talkie/api/…` | 队伍和入队审批 API |
| `/ble-talkie/ws` | 原生客户端语音、成员路由与管理消息 |

## 进程和数据

- systemd：`ble-talkie.service`，模板为 [ble-talkie.service](ble-talkie.service)。
- 监听：`127.0.0.1:18110`，仅由现有 HTTPS 站点代理。
- 发布：`/opt/ble-talkie/releases/`；`/opt/ble-talkie/current` 为原子切换的当前发布软链接。
- 发布目录中包含 `relay` 和 `ble-talkie.apk`，文件按 SHA-256 核对后安装。
- 使用 systemd `DynamicUser`，持久目录 `/var/lib/ble-talkie/` 权限 0700，状态文件权限 0600。
- `BLE_TALKIE_STATE=/var/lib/ble-talkie/state.json` 保存队伍、有效期和成员凭据；路由、待审批请求和语音不持久化。
- `BLE_TALKIE_APK=/opt/ble-talkie/current/ble-talkie.apk` 提供下载，不通过仓库分发生产凭据。

服务重启会恢复已接受的成员及组队码，成员初始离线后重新连接。队伍固定在创建 24 小时后过期。未完成申请及其领取凭据在重启后需要重新提交。

## 现有 Nginx 接入

将 [nginx-ble-talkie-location.conf](nginx-ble-talkie-location.conf) 安装到
`/etc/nginx/snippets/ble-talkie-location.conf`。现有
`/etc/nginx/conf.d/sunny-string.conf` 的 HTTPS server 中已经加入：

```nginx
include /etc/nginx/snippets/ble-talkie-location.conf;
```

代理保留完整 `/ble-talkie` 前缀，传递 WebSocket Upgrade，并关闭连接缓冲。原生 Android 客户端没有 Origin 请求头；服务端不会为了浏览器信任任意转发的来源头。

发布前备份现有片段、systemd 单元及当前软链接，备份位于 `/var/backups/ble-talkie/`。先校验文件摘要，再检查本地服务健康、执行 `nginx -t`，最后 reload 并检查公网响应。`reload` 返回后还需等待新配置生效；不能仅凭 HTTP 200 判断成功，因为站点 HTML 回退也会返回 200。

回退时恢复上一次软链接及对应配置，重启本服务并重新检查 Nginx 和公网健康。无需覆盖其他站点、证书或其他业务进程。

```sh
systemctl status ble-talkie.service
journalctl -u ble-talkie.service --since '10 minutes ago'
curl -fsS http://127.0.0.1:18110/ble-talkie/healthz
curl -fsS https://home.sunny-string.cn/ble-talkie/healthz
```

已验证公网健康、原生客户端 WSS 代理来源与混音、在线退队撤权、APK 下载摘要，以及原首页响应内容一致。
