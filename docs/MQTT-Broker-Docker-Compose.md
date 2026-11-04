# MQTT Broker 部署教程（Dockge / Docker）

> 目标：在你的服务器上用 Dockge 部署 MQTT broker，配合 MyMesag 使用。 推荐 **EMQX 5.x**（带网页管理后台，建账号、看连接、配 ACL 都方便）。

* * *

## 一、选型

| broker | 特点 | 适合 |
| --- | --- | --- |
| **EMQX 5.x**（推荐） | 功能全、有 Web 后台（端口 18083）、图形化建用户/ACL | 方便管理 |
| Mosquitto 2 | 极轻量、配置简单，但账号要用命令行 `mosquitto_passwd` 维护 | 轻量 |

下面以 EMQX 为例；文末附 Mosquitto 精简版。

* * *

## 二、Dockge 部署 EMQX

在 Dockge 里新建一个 Stack（Compose），粘贴下面的内容：

```yaml
services:
  emqx:
    image: emqx/emqx:5.8
    container_name: emqx
    restart: always
    environment:
      # 首次启动时的后台初始密码（用户名固定 admin）；只生效一次
      - EMQX_DASHBOARD__DEFAULT_PASSWORD=改成你自己的强密码
    ports:
      - "1883:1883"      # MQTT（App 当前用的明文端口）
      - "8883:8883"      # MQTTS（以后上 TLS 用）
      - "18083:18083"    # 管理后台
    volumes:
      - ./emqx-data:/opt/emqx/data
      - ./emqx-log:/opt/emqx/log
```

点 **Deploy** 启动。启动后：

1.  浏览器打开 `http://服务器IP:18083`（本机就是 `http://127.0.0.1:18083`）。
2.  用户名 `admin`，密码是你上面设的（没设就是默认 `public`，**登录后立刻改**）。

* * *

## 三、创建 MQTT 账号（App 连 broker 要用的账号）

EMQX 默认允许匿名连接，但你的 App 需要账号认证，建议创建专用账号：

1.  后台左侧 **访问控制 → 认证**。
2.  点 **创建**，认证方式选 **Password-Based（密码认证）**。
3.  后端选 **Built-in Database（内置数据库）**，下一步。
4.  添加用户，例如：
    -   用户名：`test`
    -   密码：`12345678mqtt`
5.  保存。

> 这样就完成了「账号认证」。App 里填的用户名/密码。

## 四、验证

**① 本机自测**（服务器上）：

```bash
# 用账号订阅一个主题，收到即说明 broker 正常
mosquitto_sub -h 127.0.0.1 -p 1883 -u test -P '12345678mqtt' -t test -C 1 -W 5
```

或浏览器打开后台 `18083`，看 **连接/客户端** 面板。

**② App 里填写**：

| 项 | 值 |
| --- | --- |
| Broker 地址 | 内网：`tcp://服务器内网IP:1883`；公网：`tcp://服务器外网IP:1883` |
| 用户名 | `test` |
| 密码 | 你设的密码 |

点「连接」，状态变「已连接」即成功。

## 五、（安全设置）连接配置上 TLS（Let’s Encrypt）

现在先用明文 `tcp://` 跑通。等要上加密时：

1.  用 certbot / [acme.sh](http://acme.sh) 给你的域名（如 `mqtt.你的域名.com`）签发 Let’s Encrypt 证书。
2.  把证书挂载进 EMQX，并配置 8883 监听器启用 SSL（EMQX 后台「配置 → 监听器 → 8883」填证书路径）。
3.  App 侧填 `ssl://mqtt.你的域名.com:8883`，**无需粘贴 CA**（Let’s Encrypt 走系统信任）。

> 或者更省事：让 frp/反向代理（如 Nginx stream）在公网 8883 端口直接转发到 EMQX 8883，证书由反代持有。

* * *

## 附：Mosquitto 精简版（可选）

```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    container_name: mosquitto
    restart: always
    ports:
      - "1883:1883"
    volumes:
      - ./mosquitto/config:/mosquitto/config
      - ./mosquitto/data:/mosquitto/data
      - ./mosquitto/log:/mosquitto/log
```

并在 `./mosquitto/config/mosquitto.conf` 写：

```conf
listener 1883
allow_anonymous false
password_file /mosquitto/config/passwd
```

账号生成（进入容器执行）：

```bash
docker exec -it mosquitto mosquitto_passwd -c /mosquitto/config/passwd cnhu
# 会提示输入密码两次
# 之后 docker restart mosquitto
```

* * *

*部署文档结束*