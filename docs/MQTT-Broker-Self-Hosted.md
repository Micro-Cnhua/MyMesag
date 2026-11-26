# 自建 MQTT 服务器教程（MyMesag 长期同步用）

> 面向完全没接触过服务器的小白。三步走：选路线 → 装 broker → App 里填地址。
> 全文命令都可以直接复制。**只需要放行一个端口 8883，全程用 `ssl://`，不要用 `tcp://`。**

* * *

## 〇、为什么建议自建

| 项目 | 公共 broker | 自建 broker |
| --- | --- | --- |
| 消息内容 | 安全（端到端加密） | 安全（同上，与服务器无关） |
| 收发双方 deviceId、发送时刻、消息条数/长度 | 服务器运营方可见 | 只有你能看到 |
| 可用性（限流、踢线、重启丢消息、政策变更） | 不受你控制 | 你自己控制，可开持久化 |
| 成本 | 0 | VPS 约 ¥20–40/月，或家里旧电脑/NAS 几乎 0 |
| 维护 | 0 | 需要 occasional 续证书、备份 |

端到端加密保证了「内容」不被看到，但**「谁在什么时候给谁发了多长的消息」这层元数据只有自建才能收回自己手里**。长期同步短信/来电建议自建。

* * *

## 一、先选一条路线

| 你的情况 | 推荐路线 | App 里填 |
| --- | --- | --- |
| 有 VPS + 域名 | [路线 A：Docker + Let's Encrypt](#路线-adocker--lets-encrypt有域名) | `ssl://mqtt.你的域名.com:8883`，CA 留空 |
| 有 VPS，无域名 | 路线 A，但用自签 CA | `ssl://VPS公网IP:8883` + 粘贴 `ca.crt` |
| **没有公网 IP**（家里 NAS/电脑） | [路线 B：Docker + 自签 CA + 樱花内网穿透](#路线-b无公网用户樱花内网穿透) | `ssl://隧道地址:远程端口` + 粘贴 `ca.crt` |
| 不想装 Docker | [路线 C：物理机 apt 安装](#路线-c物理机直接安装不用-docker) | 同上，只是安装方式不同 |

> 另一条更省心的无公网方案是 **Tailscale/ZeroTier 组网**（不暴露任何公网端口）。缺点：两台手机都要装并常驻后台，容易被国产 ROM 杀进程，对「通知同步」这类常驻场景不如 frp 稳。

* * *

## 二、路线 A：Docker + Mosquitto

适用：有 Linux 服务器（Ubuntu 22/24 均可）。有域名走 2.2 的 certbot；没域名走 2.2 的自签。

### 2.1 装 Docker

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
```

### 2.2 建目录 + 准备证书

```bash
sudo mkdir -p /opt/mosquitto/{config,certs,data,log}
sudo chown -R 1000:1000 /opt/mosquitto
cd /opt/mosquitto
```

**方式 1：自签 CA（无域名 / 用 IP 连接）**

```bash
cd /opt/mosquitto/certs

# ① CA 根证书，十年有效
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout ca.key -out ca.crt -subj "/CN=MyMesag MQTT CA"

# ② 服务端证书请求，CN 填你将来在 App 里填的那个地址（域名或 IP）
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/CN=1.2.3.4"

# ③ 用 CA 签发。SAN 必须包含 App 里填的那个地址，否则连接会因主机名校验失败
cat > san.cnf <<'EOF'
subjectAltName = IP:1.2.3.4
extendedKeyUsage = serverAuth
EOF
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 825 -sha256 -out server.crt -extfile san.cnf

chmod 600 ca.key server.key
```

- `ca.crt`：待会儿要粘贴进 App 的那段 `-----BEGIN CERTIFICATE-----…`
- `ca.key` / `server.key`：**绝对不要**发给别人，也不要明文传网盘
- 用域名时把两处 `1.2.3.4` 换成 `mqtt.example.com`，并把 `IP:` 改成 `DNS:`

**方式 2：Let's Encrypt（有域名，推荐）**

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d mqtt.你的域名.com   # 申请时 80 端口不能被占用
sudo mkdir -p /opt/mosquitto/certs
sudo cp /etc/letsencrypt/live/mqtt.你的域名.com/fullchain.pem /opt/mosquitto/certs/server.crt
sudo cp /etc/letsencrypt/live/mqtt.你的域名.com/privkey.pem  /opt/mosquitto/certs/server.key
sudo chown -R 1000:1000 /opt/mosquitto/certs
```

此时 App 的 CA 栏**留空**（系统信任链里已有 Let's Encrypt 根证书）。

### 2.3 配置文件 `/opt/mosquitto/config/mosquitto.conf`

```conf
listener 8883 0.0.0.0
protocol mqtt

cafile   /mosquitto/config/certs/ca.crt
certfile /mosquitto/config/certs/server.crt
keyfile  /mosquitto/config/certs/server.key
require_certificate false

# 关闭匿名连接，改用密码文件（每台设备一个账号，方便单独吊销）
allow_anonymous false
password_file /mosquitto/config/passwd

persistence true
persistence_location /mosquitto/data/
log_dest file /mosquitto/log/mosquitto.log
log_type error
log_type warning
```

> Let's Encrypt 场景下没有自己的 `ca.crt`：把 `cafile` 那行改成 `capath /mosquitto/config/certs/` 会报错，正确做法是**直接删掉 `cafile` 这一行**（服务端不需要 CA 就能出示证书），只保留 `certfile` 与 `keyfile`。

### 2.4 建账号（每台设备一个）

```bash
cd /opt/mosquitto/config

# 第一个账号（-c 创建文件，会提示输入两次密码）
sudo docker run --rm -v /opt/mosquitto/config:/mosquitto/config eclipse-mosquitto:2 \
  mosquitto_passwd -c /mosquitto/config/passwd deviceA

# 追加第二个账号（不要再用 -c，那会清空文件）
sudo docker run --rm -v /opt/mosquitto/config:/mosquitto/config eclipse-mosquitto:2 \
  mosquitto_passwd -b /mosquitto/config/passwd deviceB '一个随机强密码'

sudo chown 1000:1000 /opt/mosquitto/config/passwd
```

### 2.5 启动 `/opt/mosquitto/docker-compose.yml`

```yaml
services:
  mosquitto:
    image: eclipse-mosquitto:2
    restart: unless-stopped
    ports:
      - "8883:8883"
    volumes:
      - ./config/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro
      - ./config/passwd:/mosquitto/config/passwd:ro
      - ./certs:/mosquitto/config/certs:ro
      - ./data:/mosquitto/data
      - ./log:/mosquitto/log
```

```bash
cd /opt/mosquitto && sudo docker compose up -d
sudo docker compose logs --tail=50      # 出现 "mosquitto version 2.x starting" 且无 error 即成功
```

### 2.6 放行端口

云厂商控制台安全组放行 **TCP 8883**（1883 不要开）。机器内若启用了 ufw：

```bash
sudo ufw allow 8883/tcp
```

### 2.7 服务器自检

```bash
# 终端 1：订阅
sudo docker exec -it mosquitto mosquitto_sub \
  -h 127.0.0.1 -p 8883 --cafile /mosquitto/config/certs/ca.crt \
  -u deviceA -P '你的密码' -t 'test/#' -v

# 终端 2：发布
sudo docker exec -it mosquitto mosquitto_pub \
  -h 127.0.0.1 -p 8883 --cafile /mosquitto/config/certs/ca.crt \
  -u deviceA -P '你的密码' -t 'test/hello' -m 'ok'
```

终端 1 打印出 `test/hello ok` 就说明 TLS + 账号认证都通了。（用 Let's Encrypt 时把 `--cafile` 换成 `--capath /etc/ssl/certs` 或省略。）

* * *

## 三、路线 B：无公网用户（樱花内网穿透）

思路：broker 跑在家里的电脑/NAS/树莓派上（**完全照抄路线 A 的 2.1–2.5**），再用樱花 frp 把 `127.0.0.1:8883` 映射到公网的一个「隧道地址 + 远程端口」。手机连的是隧道地址，TLS 仍然是**手机 ↔ 家里 broker 端到端**，樱花节点只搬运加密字节。

### 3.1 注册与实名认证

1. 官网注册账号（免费额度：约 2 条隧道、10 Mbps 带宽、5 GB/月流量，每日签到可补充）。
2. 必须完成**实名认证**（1 元资格 + 支付宝扫码刷脸）才能创建隧道，请自行评估是否接受。
3. 在「用户信息」页复制**访问密钥**，客户端登录要用。

### 3.2 创建隧道

面板 → 服务 → 隧道列表 → 创建隧道：

| 项 | 填什么 |
| --- | --- |
| 节点 | 选离你（和家庭宽带）**最近的运营商**，负载低的 |
| 隧道类型 | **TCP 隧道**（不要选 HTTP/HTTPS/Web 隧道，MQTT 不是 HTTP） |
| 本地 IP | `127.0.0.1`（frpc 跑在 broker 同一台机器上时） |
| 本地端口 | `8883` |
| 远程端口 | 自选，范围一般 10240–65535；被占用就换一个 |

创建完成后，隧道列表会显示这条隧道的**对外地址和端口**（形如「节点域名:远程端口」，以面板显示为准）。

### 3.3 启动 frpc

- **Windows**：下载官方 Sakura Launcher（启动器），用访问密钥登录，勾选这条隧道 → 启动。想开机就在登录前连接，安装时勾「安装为系统服务」。
- **Linux（NAS/树莓派推荐）**：按官网面板给出的 Linux 启动命令执行（面板里会直接给带密钥的 `frpc` 命令）。做成 systemd 开机自启的通用写法：

```bash
sudo tee /etc/systemd/system/frpc@.service >/dev/null <<'EOF'
[Unit]
Description=SakuraFrp Tunnel %i
After=network-online.target

[Service]
Type=idle
User=nobody
Restart=on-failure
RestartSec=60s
ExecStart=/usr/local/bin/frpc -f %i

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
# 把 访问密钥 和 隧道ID 换成你自己的
sudo systemctl enable --now frpc@你的访问密钥:你的隧道ID
```

### 3.4 证书要用隧道的对外地址签

frp 给你的往往是一个**域名**（也可能直连节点 IP）。所以路线 A 的 2.2 里两处地址与 `san.cnf` 都要写成这个隧道地址，例如：

```bash
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/CN=nn-xxxxx.frpsakura.com"          # 换成面板显示的实际隧道域名
cat > san.cnf <<'EOF'
subjectAltName = DNS:nn-xxxxx.frpsakura.com
extendedKeyUsage = serverAuth
EOF
```

> ⚠️ 换节点＝换域名＝要重签 `server.crt`（**CA 不用换，App 的 CA 栏也不用改**，只需把新的 `server.crt`/`server.key` 放好并重启 broker）。所以第一次就选一个稳定的线路。远程端口变了不影响证书（端口不参与主机名校验）。
> ⚠️ 隧道断了 App 会表现为掉线并自动重连；恢复后积压的消息靠 broker 持久化 + App 的重连补同步。

### 3.5 家里机器还需注意

- 光猫拨号/路由 NAT 不影响樱花（frpc 主动外连），**不需要端口映射**，这是它相对自建 frp 的最大好处。
- 家里的机器要常开。建议给跑 broker 的机器设固定内网 IP，并把 frpc + mosquitto 都设成开机自启。
- 免费线路带宽/流量有限：通知同步本身流量很小（每条几百字节到几 KB），一般够用，但别在同一隧道上跑下载业务。

* * *

## 四、路线 C：物理机直接安装（不用 Docker）

适合：不想折腾容器，机器就一台 Linux。与路线 A 的区别只有「配置文件路径、证书路径、账号命令、重启方式」。

```bash
sudo apt update && sudo apt install -y mosquitto mosquitto-clients
sudo mkdir -p /etc/mosquitto/certs
# 证书做法与路线 A 的 2.2 完全相同，产出放到 /etc/mosquitto/certs/ 下
```

编辑 `/etc/mosquitto/mosquitto.conf`（末尾追加或替换原有 listener 段）：

```conf
listener 8883 0.0.0.0
protocol mqtt

certfile /etc/mosquitto/certs/server.crt
keyfile  /etc/mosquitto/certs/server.key
cafile   /etc/mosquitto/certs/ca.crt        # 仅自签场景需要；Let's Encrypt 请删除本行
require_certificate false

allow_anonymous false
password_file /etc/mosquitto/passwd

persistence true
persistence_location /var/lib/mosquitto/
log_dest file /var/log/mosquitto/mosquitto.log
log_type error
log_type warning
```

```bash
# 建账号（物理机自带 mosquitto_passwd，不需要 docker）
sudo mosquitto_passwd -c /etc/mosquitto/passwd deviceA
sudo mosquitto_passwd -b /etc/mosquitto/passwd deviceB '随机强密码'
sudo chown root:mosquitto /etc/mosquitto/passwd
sudo chmod 640 /etc/mosquitto/passwd
sudo chmod 600 /etc/mosquitto/certs/*.key        # 私钥权限收紧，否则启动失败

sudo systemctl restart mosquitto && sudo systemctl status mosquitto --no-pager
```

自检同 2.7，去掉 `docker exec`：

```bash
mosquitto_sub -h 127.0.0.1 -p 8883 --cafile /etc/mosquitto/certs/ca.crt -u deviceA -P '密码' -t 'test/#' -v
```

* * *

## 五、MyMesag 两端怎么填

同步设置 → MQTT 配置，两台设备分别：

| 项 | 值 |
| --- | --- |
| 服务器地址 | `ssl://<你的域名或 IP 或隧道地址>:8883`（樱花写隧道给的远程端口） |
| 用户名 / 密码 | 该设备专属账号，如 deviceA / deviceB |
| CA 证书 | 自签场景粘贴 `ca.crt` 全文；Let's Encrypt **留空** |
| 心跳间隔 | 默认即可 |

点「连接」，状态变「已连接」即成功。

- 地址只能写成 `协议://主机[:端口]`，不要带路径、不要带空格。
- App 会**拒绝**「`tcp://` 或 `ws://` + 填了账号密码」的组合（凭据会在链路上裸奔），所以别退回明文端口。
- **换 broker 不需要重新配对**：groupToken 与会话密钥存在本机 Keystore 里，两端只要都连上新服务器就能继续互通。只有你想彻底断掉与旧服务器的关联（比如旧服务器是公共实例）时，才在配对页做「全量重置」后重新扫码。
- 旧的 retained 消息留在旧服务器上不会自动消失，需要去旧 broker 清理或干脆忽略（App 已不再订阅旧主题格式）。

## 六、常见坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 连上就断 / 报证书错误 | 证书 SAN 不包含 App 里填的地址 | 按实际地址重签 `server.crt`（CA 不用换） |
| `bad user name or password` | passwd 权限或挂载路径不对 | `chown 1000:1000`（容器）或 `root:mosquitto`+`640`（物理机） |
| 粘了 CA 仍报错 | 粘成了 `server.crt`，或多了空行/缺 BEGIN/END | 只粘 `ca.crt` |
| 外网一直连不上 | mosquitto 2 默认只监听 127.0.0.1 | 确认写了 `listener 8883 0.0.0.0`；云主机检查安全组 |
| 私钥权限报错启动失败 | `*.key` 权限过宽 | `chmod 600 *.key` |
| 樱花隧道在线但连不上 | 选成了 HTTP 隧道 / 本地端口写错 | 必须是 **TCP 隧道**，本地端口 `8883` |
| 换节点后突然全断 | 隧道域名变了，证书 SAN 过期 | 用新隧道域名重签服务端证书并重启 |
| 重启后积压消息丢失 | 没开持久化 | `persistence true` + data 目录已挂载 |
| App 里填了地址提示格式不正确 | 带了 `http://`、路径或空格 | 改成 `ssl://主机:8883` |

## 七、备份与长期维护

- 备份三样：配置目录（含 `passwd`）、`certs`（`ca.key` 要加密存放）、`data`（retained 消息）。
- 自签：CA 十年、服务端证书 825 天。到期只重跑「签发服务端证书」那两步，然后重启 broker，App 侧不用改。
- Let's Encrypt：加自动续期，例如 crontab：
  ```bash
  0 3 * * 1 certbot renew --quiet --deploy-hook "systemctl restart mosquitto"
  ```
  （Docker 部署时把 hook 换成 `docker compose -f /opt/mosquitto/docker-compose.yml restart`。）
- 可选：`fail2ban` 盯 `/var/log/mosquitto/mosquitto.log` 里的认证失败；公网只开 8883，别开 1883。
- 建议每季度检查一次：`docker compose ps` / `systemctl is-active mosquitto`、隧道面板是否在线、证书剩余天数 `openssl x509 -in server.crt -noout -enddate`。

## 八、自建之后仍然存在的两点

1. **信封外层元数据仍是明文**（发送/接收 deviceId、时间戳、序号）与主题通道名。自建只是把「能看到的人」从公共运营方缩小到你自己，不消除这些字段。彻底消除需要把路由信息移进密文，属协议级改动。
2. **配对二维码是长期静态的**，截图外泄会同时泄露 groupToken 与公钥（解不开消息，但足以推导主题、观察流量）。配对页已有红字提醒，请用图片扫码的设备在配对成功后删除截图。

* * *

*本教程对应 MyMesag v0.3.3-beta2 的行为：所有通道信封加密 + AAD 绑定通道名、明文连接带账密会被拒绝、presence/LWT 已停用。*
