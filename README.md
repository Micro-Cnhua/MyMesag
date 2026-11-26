# MyMesag - 多设备通知同步

跨设备通知同步工具，支持端到端加密，通过 MQTT 协议实现手机间通知实时同步。

## 功能特性

-   **通知同步** - 实时同步手机通知到另一台设备
-   **端到端加密** - AES-256-GCM + X25519 密钥交换，服务器无法解密
-   **多设备支持** - 支持多台设备配对，两两独立会话密钥
-   **应用过滤** - 自由选择需要同步的应用
-   **验证码同步** - 可选同步验证码短信（需确认风险）

## 技术栈

-   **语言**: Kotlin
-   **UI**: Jetpack Compose + Material 3
-   **网络**: Eclipse Paho MQTT (TCP/SSL)
-   **加密**: Google Tink (AES-256-GCM) + X25519
-   **存储**: Room Database + DataStore + Android Keystore
-   **架构**: MVVM + Clean Architecture (多模块)

## 项目结构

```
MyMesag/
├── app/                    # 主应用模块
│   ├── src/main/
│   │   ├── java/com/mymesag/
│   │   │   ├── ui/         # Compose UI 界面
│   │   │   ├── service/    # 后台服务（通知监听、MQTT）
│   │   │   ├── data/       # 数据层（加密、存储）
│   │   │   ├── model/      # 数据模型
│   │   │   ├── network/    # 网络层
│   │   │   ├── notification/ # 通知处理
│   │   │   ├── pairing/    # 设备配对
│   │   │   └── reply/      # 回复功能
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── core-model/             # 数据模型模块
├── core-network/           # 网络模块（MQTT）
├── core-crypto/            # 加密模块
└── build.gradle.kts        # 根构建配置
```

## 编译构建

### 环境要求

-   JDK 17+
-   Android SDK 35
-   Gradle 8.12+

### 编译步骤

```bash
# 克隆项目
git clone https://github.com/Micro-Cnhua/MyMesag.git
cd MyMesag

# 编译 Release APK
./gradlew assembleRelease

# APK 输出位置
# app/build/outputs/apk/release/app-release.apk
```

### 签名配置

项目使用自定义签名，需要在 `app/build.gradle.kts` 配置：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("your-keystore.jks")
        storePassword = "your-password"
        keyAlias = "your-alias"
        keyPassword = "your-password"
    }
}
```

## 使用指南

### 首次使用

1.  **开启通知使用权** - 安装后授予通知监听权限
2.  **配置 MQTT 服务器** - 设置 MQTT 连接（支持 TCP/SSL）
3.  **设备配对** - 两台设备通过扫描二维码配对
4.  **选择同步应用** - 在「应用选择」开启需要同步的应用
5.  **开始同步** - 通知会自动同步到配对设备
6.  **应用保活** - 在「系统设置与保活」为应用开启「后台运行」「自启动」「电池白名单」权限

### 详细文档

应用内置「额外帮助」页面提供完整使用教程，内容从 GitHub 自动同步。

## 断网重连与补推延迟说明

长时间断网（整晚关飞行模式、路由器断电、地库/电梯无信号等）后网络恢复时，**自动重连和断线补推都会有延迟，这是预期行为，不是 Bug**。

### 重连会延迟多久

程序断线后不会放弃重连：先按 2、4、8、16、32、64、128 秒指数退避，之后固定每 30 秒继续尝试。长时间断网时早已进入 30 秒阶段，所以从系统层面看，代码本身的重连上限约 30 秒。实际耗时取决于系统省电策略：

-   **约 5～10 秒** - 后台网络回调能正常送达（已加入电池优化白名单、允许后台运行）
-   **约 30 秒内** - 回调被系统抑制，但进程和定时器仍在运行，靠 30 秒重试循环命中
-   **不确定，可能到下次亮屏** - 系统进入 Doze 或 ROM 冻结后台定时器/网络，此时应用侧无法自解，只能等系统维护窗口或手动打开 App

自 v0.3.3-beta5 起，保活前台服务改用 `remoteMessaging` 类型，不再受原生 Android 15+ 对 `dataSync` 的「每 24 小时累计 6 小时」限制——此前配额耗尽后服务会被系统强停且无法再启动，长时间息屏挂机必然掉线，只能手动打开应用恢复。该限制已消除，但 Doze 与厂商后台策略仍可能造成上表第三档的延迟。

想尽快恢复，请把 MyMesag 设为**不受电池优化限制 + 允许自启动 + 保留常驻通知**。原生 Android（尤其 14 以上）和国产 ROM（MIUI/HarmonyOS 4.3及以下/ColorOS 等）的后台限制较严，未授权时确实可能需要手动打开一次 App 才重连——打开的瞬间程序会立即自检重连。

### 补推会延迟

断连期间对方发出的通知不会丢：本机重连成功后，会向对方请求补发这段时间漏掉的通知，对方从本地缓存（保留最近 7 天、最多 500 条）按顺序重放。因此：

-   这些通知是在**重连成功之后**才开始推送的，到达时间必然晚于原始产生时间
-   通知标题前置的时间是远端实际收到的时间，与本机事件时间不同属正常
-   补发是逐条进行的，漏得越多整体耗时越长
-   超出 7 天或超过 500 条的历史通知不再补发

## 安全说明

### 加密机制

-   **传输加密**: MQTT 支持 SSL/TLS（推荐）
-   **端到端加密**: 通知内容使用 AES-256-GCM 加密
-   **密钥交换**: X25519 ECDH 密钥协商
-   **密钥存储**: Android Keystore 硬件级保护
-   **抗重放**: 消息序号 + 时间戳校验

### 注意事项

-   ⚠️ 验证码同步功能会通过网络传输验证码，请确保 MQTT 服务器可信
-   ⚠️ 不要将签名密钥（.jks 文件）上传到公开仓库
-   ⚠️ 建议使用自建 MQTT 服务器，避免使用公共服务器

## MQTT 服务器配置

### 自建服务器（推荐）

#### 使用 Mosquitto

```bash
docker run -d --name mosquitto \
  -p 1883:1883 \
  -p 8883:8883 \
  -v /path/to/config:/mosquitto/config \
  eclipse-mosquitto
```

#### 使用 EMQX

详见docs目录中的MQTT-Broker-Docker-Compose.md文档： [点击查看](docs/MQTT-Broker-Docker-Compose.md)

### 公用服务器（不推荐，有安全风险，仅建议测试）

-   tcp://broker.emqx.io:1883（无账号密码的明文连接）
-   ssl://broker.emqx.io:8883（无账号密码的加密传输）

### 连接格式

-   **TCP**: `tcp://192.168.1.100:1883`（内网）
-   **SSL**: `ssl://your-domain.com:8883`（外网，推荐）

## 系统要求

-   Android 8.0 (API 26) 及以上
-   需要通知使用权权限
-   可选权限：电话、短信、联系人（用于未接来电回复）

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

本项目采用 Apache License 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件

## 致谢

-   [Eclipse Paho](https://www.eclipse.org/paho/) - MQTT 客户端
-   [Google Tink](https://github.com/google/tink) - 加密库
-   [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代 UI 工具包

* * *

**免责声明**: 本工具仅供学习和研究使用。使用端到端加密通信时，请确保遵守当地法律法规。