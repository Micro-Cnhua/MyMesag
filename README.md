# MyMesag - 多设备通知同步

跨设备通知同步工具，支持端到端加密，通过 MQTT 协议实现手机间通知实时同步。

## 功能特性

-   **通知同步** - 实时同步手机通知到另一台设备
-   **端到端加密** - AES-256-GCM + X25519 密钥交换，服务器无法解密
-   **多设备支持** - 支持多台设备配对，两两独立会话密钥
-   **未接来电回复(修复中)** - 收到未接来电同步后，可直接回复短信
-   **应用过滤** - 自由选择需要同步的应用
-   **验证码同步** - 可选同步验证码短信（需确认风险）
## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **网络**: Eclipse Paho MQTT (TCP/SSL)
- **加密**: Google Tink (AES-256-GCM) + X25519
- **存储**: Room Database + DataStore + Android Keystore
- **架构**: MVVM + Clean Architecture (多模块)

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

- JDK 17+
- Android SDK 35
- Gradle 8.12+

### 编译步骤

```bash
# 克隆项目
git clone https://github.com/你的用户名/MyMesag.git
cd MyMesag/Develop

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

1. **开启通知使用权** - 安装后授予通知监听权限
2. **配置 MQTT 服务器** - 设置 MQTT 连接（支持 TCP/SSL）
3. **设备配对** - 两台设备通过扫描二维码配对
4. **选择同步应用** - 在「应用选择」开启需要同步的应用
5. **开始同步** - 通知会自动同步到配对设备

### 详细文档

应用内置「额外帮助」页面提供完整使用教程，内容从 GitHub 自动同步。

## 安全说明

### 加密机制

- **传输加密**: MQTT 支持 SSL/TLS（推荐）
- **端到端加密**: 通知内容使用 AES-256-GCM 加密
- **密钥交换**: X25519 ECDH 密钥协商
- **密钥存储**: Android Keystore 硬件级保护
- **抗重放**: 消息序号 + 时间戳校验

### 注意事项

- ⚠️ 验证码同步功能会通过网络传输验证码，请确保 MQTT 服务器可信
- ⚠️ 不要将签名密钥（.jks 文件）上传到公开仓库
- ⚠️ 建议使用自建 MQTT 服务器，避免使用公共服务器

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
详见docs目录中的MQTT-Broker-Docker-Compose.md文档：
[点击查看](docs/MQTT-Broker-Docker-Compose.md)

### 公用服务器（不推荐，有安全风险，仅建议测试）

- tcp://broker.emqx.io:1883
- ssl://broker.emqx.io:8883

### 连接格式

- **TCP**: `tcp://192.168.1.100:1883`（内网）
- **SSL**: `ssl://your-domain.com:8883`（外网，推荐）

## 系统要求

- Android 8.0 (API 26) 及以上
- 需要通知使用权权限
- 可选权限：电话、短信、联系人（用于未接来电回复）

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

本项目采用 Apache License 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件

## 致谢

- [Eclipse Paho](https://www.eclipse.org/paho/) - MQTT 客户端
- [Google Tink](https://github.com/google/tink) - 加密库
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代 UI 工具包

---

**免责声明**: 本工具仅供学习和研究使用。使用端到端加密通信时，请确保遵守当地法律法规。
