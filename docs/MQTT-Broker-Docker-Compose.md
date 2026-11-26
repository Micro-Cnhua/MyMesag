# MQTT Broker 部署教程（Dockge / Docker / EMQX，TLS 版）

> 目标：在你的服务器上用 Dockge 部署一个 **只开 8883（MQTTS）** 的 MQTT broker，配合 MyMesag 使用。
> 推荐 **EMQX 5.x**（带网页管理后台，建账号、看连接、配 ACL 都方便）。
> 全文只用 `ssl://`。**不要**再用 `tcp://1883` + 账号密码：MyMesag 检测到这种组合会直接拒绝连接，因为凭据会在链路上裸奔。

* * *

## 一、选型

| broker | 特点 | 适合 |
| --- | --- | --- |
| **EMQX 5.x**（推荐） | 功能全、有 Web 后台、图形化建用户/ACL、TLS 监听器可粘贴证书 | 方便管理 |
| Mosquitto 2 | 极轻量、配置简单，账号要用命令行 `mosquitto_passwd` 维护 | 轻量/VPS 内存小 |

本文以 EMQX 为例。Mosquitto 的完整部署（含无公网的樱花内网穿透方案）见
[`docs/MQTT-Broker-Self-Hosted.md`](./MQTT-Broker-Self-Hosted.md)。

* * *

## 二、准备证书（二选一）

### 方式 1：Let's Encrypt（有域名，推荐）

App 端 **CA 栏留空**即可，因为系统信任链里已包含 Let's Encrypt 根证书，证书还能自动续期。

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d mqtt.你的域名.com   # 申请瞬间 80 端口不能被占用
```

证书位于 `/etc/letsencrypt/live/mqtt.你的域名.com/{fullchain.pem,privkey.pem}`。

### 方式 2：自签 CA（无域名 / 用 IP 连接）

按 [`MQTT-Broker-Self-Hosted.md` 的 2.2 方式 1](./MQTT-Broker-Self-Hosted.md) 生成 `ca.crt` / `server.crt` / `server.key`。
关键约束：**证书 SAN 必须包含你将来在 App 里填的那个地址**（域名或 IP 都要写对，端口不用写）。自签场景 App 需粘贴 `ca.crt` 全文。

* * *

## 三、Dockge 部署 EMQX（只开 8883 + 后台）

在 Dockge 里新建 Stack，粘贴：

```yaml
services:
  emqx:
    image: emqx/emqx:5.8
    container_name: emqx
    restart: unless-stopped
    environment:
      # 首次启动的后台初始密码（用户名固定 admin），只生效一次
      - EMQX_DASHBOARD__DEFAULT_PASSWORD=改成你自己的强密码

      # 关闭明文 1883 监听器
      - EMQX_LISTENERS__TCP__DEFAULT__ENABLE=false

      # 开启 TLS 监听器 8883
      - EMQX_LISTENERS__SSL__DEFAULT__BIND=8883
      - EMQX_LISTENERS__SSL__DEFAULT__SSL_OPTIONS__CERTFILE=/opt/emqx/certs/server.crt
      - EMQX_LISTENERS__SSL__DEFAULT__SSL_OPTIONS__KEYFILE=/opt/emqx/certs/server.key
      - EMQX_LISTENERS__SSL__DEFAULT__SSL_OPTIONS__CAFILE=/opt/emqx/certs/ca.crt   # Let's Encrypt 也指向 fullchain.pem
    ports:
      - "8883:8883"      # MQTTS，唯一需要对外开放的 MQTT 端口
      - "127.0.0.1:18083:18083"   # 管理后台：只绑本机，用 SSH 隧道或内网访问
    volumes:
      - ./emqx-data:/opt/emqx/data
      - ./emqx-log:/opt/emqx/log
      - /opt/emqx/certs:/opt/emqx/certs:ro   # 证书目录（自建后 chown 1000:1000）
```

点 **Deploy** 启动。证书目录请在启动前先准备好（EMQX 挂载到不存在的目录会自动建空目录，容器起不来）：

```bash
sudo mkdir -p /opt/emqx/certs
sudo cp 你的server.crt 你的server.key 你的ca.crt /opt/emqx/certs/   # LE 时 ca.crt 用 fullchain.pem
sudo chown -R 1000:1000 /opt/emqx/certs && sudo chmod 600 /opt/emqx/certs/*.key
```

启动后看日志确认监听器：`sudo docker logs --tail=50 emqx`。

启动后：

1.  本机或 SSH 隧道打开 `http://127.0.0.1:18083`。
2.  用户名 `admin`，密码是你上面设的值（没设则默认 `public`，**登录后立刻改**）。
3.  **不要**把 18083 开到公网——它是带账号密码数据库的管理界面。云主机安全组只放行 8883。

* * *

> 说明：EMQX 5 镜像自带 `listeners.ssl.default`（8883）和一份内置自签证书，上面的环境变量只是把证书换成你自己的、并把明文 1883 关掉。
> 若你的后台版本入口不同，也可以在「接入 → 监听器 → SSL/TCP → 编辑 → TLS 认证证书」里直接粘贴 PEM 内容，效果等价。

* * *

## 四、创建 MQTT 账号（App 连接用）

EMQX 默认允许匿名连接。**开了 TLS 后建议关闭匿名并给每台设备建独立账号**，便于单独吊销和排查：

1.  「访问控制 → 认证」→ **创建** → **Password-Based（密码认证）** → 后端选 **Built-in Database**。
2.  添加用户，两台设备各一个：
    -   `deviceA` / 随机强密码
    -   `deviceB` / 随机强密码
3.  配好认证器后，未带正确账号密码的连接会被拒绝（EMQX 5.5+ 已把 `allow_anonymous` 交给认证/授权体系管理，无需再设环境变量；若你的版本仍显示该开关，把它置为 `false`）。

密码只在 TLS 通道内传输，不会在链路明文出现。

* * *

## 五、验证

**① 服务器本机自测**（需装 `mosquitto-clients`）：

```bash
# 终端 1：订阅（自签场景 --cafile 指向 ca.crt；Let's Encrypt 用 --capath /etc/ssl/certs）
mosquitto_sub -h 127.0.0.1 -p 8883 --cafile /opt/emqx/certs/ca.crt \
  -u deviceA -P '你的密码' -t 'test/#' -v

# 终端 2：发布
mosquitto_pub -h 127.0.0.1 -p 8883 --cafile /opt/emqx/certs/ca.crt \
  -u deviceA -P '你的密码' -t 'test/hello' -m 'ok'
```

终端 1 打印 `test/hello ok` 即 TLS + 认证都通。

**② 后台确认**：「监控 → 客户端」应能看到刚连接的客户端、监听器为 `ssl:default`。

**③ App 里填写**：

| 项 | 值 |
| --- | --- |
| 服务器地址 | `ssl://mqtt.你的域名.com:8883`（无域名写 `ssl://公网IP:8883`，注意证书 SAN 要含它） |
| 用户名 | 该设备的账号，如 `deviceA` |
| 密码 | 对应密码 |
| CA 证书 | 自签：粘贴 `ca.crt` 全文；Let's Encrypt：留空 |
| 心跳间隔 | 默认即可 |

点「连接」，状态变「已连接」即成功。两台设备都改完后，在通知页测一次收发即可。

* * *

## 六、无公网 IP 的情况

家里 NAS/电脑没有公网时，用 TCP 隧道把 `8883` 映射出去（樱花内网穿透等），步骤与证书 SAN 注意事项见
[`MQTT-Broker-Self-Hosted.md` 第三节](./MQTT-Broker-Self-Hosted.md)。要点复述：

-   隧道类型必须选 **TCP 隧道**，本地端口填 `8883`。
-   服务端证书 SAN 要签成**隧道的对外地址**；换节点＝地址变＝要重签服务端证书（CA 与 App 的 CA 栏不用改）。

* * *

## 七、常见坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 连上立刻断开、日志报握手失败 | 证书 SAN 与 App 填的地址不一致 | 用实际地址重签 `server.crt` |
| App 提示明文连接相关警告/被拒绝 | 地址写成 `tcp://` 且填了账密 | 改 `ssl://…:8883` |
| 报 `bad user name or password` | 账号未建、或 `allow_anonymous` 关了却没配认证 | 检查「访问控制 → 认证」与用户列表 |
| 8883 不通但本机 curl 正常 | 云主机安全组未放行 / 端口映射写成了 1883 | 只放行 TCP 8883 |
| 容器起不来、日志说找不到证书 | 证书目录没挂进去或权限不足 | `chown -R 1000:1000`、`chmod 600 *.key`、确认 `:ro` 路径 |
| 后台打不开 | 18083 故意只绑了 127.0.0.1 | 用 `ssh -L 18083:127.0.0.1:18083 用户@服务器` 转发 |
| 换 broker 后以为要重新配对 | 不需要 | groupToken 与会话密钥在本机 Keystore，两端连上新服务器即可继续互通；只有主动「全量重置」才需重新扫码 |

* * *

## 八、维护清单

- 证书剩余天数：`openssl x509 -in server.crt -noout -enddate`；Let's Encrypt 加续期任务并重启容器。
- 备份：`emqx-data`（账号、ACL）、`/opt/emqx/certs`（私钥加密保存）。
- 每次 EMQX 升级前先看它的 TLS 默认项变化（最低协议版本、密码套件），MyMesag 侧使用 TLS 1.2 及以上。

* * *

*部署文档结束（TLS 版，对应 MyMesag v0.3.3-beta2 的连接策略）*
