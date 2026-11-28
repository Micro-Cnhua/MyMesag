# MyMesag 能耗测试与优化指南

本教程面向"用 adb 在真机上量化 MyMesag 的后台耗电，并据此决定改什么"的场景。全部命令在 Windows（Git Bash）+ Xperia 原生 Android 上编写，其他厂商 ROM 命令相同，只是后台策略不同。

配套版本：v0.3.3-beta2 起前台服务类型为 `remoteMessaging`。

> **嫌手敲命令麻烦？** 用分步脚本 `bash D:/tools/battery-test.sh`（放在仓库外，避开中文路径；Git Bash 或 PowerShell 都能跑）。它会一步一停：执行命令 → 提示你要亲手做什么（拔线、息屏、改心跳档位）→ 等你回车 → 倒计时 → 自动采集并汇总，结果写在脚本旁边的 `D:\tools\battery-test\`。手动命令仍值得读一遍，脚本覆盖不到的场景（如 `--history` 逐行分析）按本教程来。
>
> 脚本里 `adb` 的取值顺序：环境变量 `ADB` → PATH 里的 `adb`。你机器上有两个 adb（platform-tools 37.0.1 与 scrcpy 自带 36.0.0），版本不一致会让 adb server 被反复重启、打断长时段 `logcat`，测功耗期间别开 scrcpy。

---

## 0. 前置准备

### 0.1 确认 adb 与设备

```bash
adb version
adb devices -l
```

`adb devices` 为空时的排查顺序：

1.  手机 **开发者选项 → USB 调试** 已开启
2.  连接模式选「文件传输」（部分 Sony 机型「仅充电」模式下不授权）
3.  手机上弹出的「允许 USB 调试」勾选「一律允许」
4.  `adb kill-server && adb devices` 重试

### 0.2 固定测试条件（否则数据不可比）

每轮测试必须保持一致的只有这些：

| 条件 | 建议固定值 |
| --- | --- |
| APK 版本 | 同一 versionCode，测试期间不要覆盖安装 |
| 网络 | Wi‑Fi 开、蜂窝关（或反之，另开一组） |
| 充电状态 | 拔掉数据线后静置；若必须挂线，全程挂线并记录 |
| 屏幕 | 息屏，期间不操作手机 |
| 时长 | 60 分钟（短时压测另说） |
| 通知量 | 静置期间不要收新通知，否则测的是业务能耗不是底噪 |
| 配对设备 | 保持另一台设备在线（避免补发逻辑被触发） |

### 0.3 建议的目录结构

```bash
mkdir -p battery-test && cd battery-test
```

每轮产物命名：`<组号>-<变量>-<日期>/{bs.txt,checkin.txt,history.txt,br.zip,log.txt}`

---

## 1. 这个 App 的能耗来自哪里（速查）

| # | 来源 | 代码位置 | 触发条件 | 频次 |
| --- | --- | --- | --- | --- |
| 1 | MQTT 心跳 PING | `MqttManager.kt:140`（`keepAliveInterval`） | 已连接 | 默认 60 秒一次 |
| 2 | 断线重连重试 | `MqttManager.kt:221-248` | 已断线 | 退避后固定每 30 秒 |
| 3 | 网络回调触发重连 | `MqttForegroundService.kt:107-142` | 网络变化 | 事件驱动，5 秒去抖 |
| 4 | 补发/ACK 定时器 | `NotificationReceiver.kt:430,581`、`MessageRetryManager.kt:55` | 有未确认消息 | 有限次重试 |
| 5 | 每条通知落盘 | DataStore / Room | 收到或发出通知 | 事件驱动 |
| 6 | 常驻通知 + 进程驻留 | `MqttForegroundService.kt:36` | 一直 | 自身几乎不耗电，算基线 |

**结论先行**：待机态只有第 1、6 项在跑。所以待机能耗主要由**唤醒次数**决定，优化优先级是心跳 > 其他一切。

---

## 2. 测法 A：1 小时待机基线（主用）

### 2.1 采集

```bash
# 1) 清零统计（务必在拔线前做）
adb shell dumpsys batterystats --reset

# 2) 模拟离电，让系统按「未充电」口径统计
adb shell dumpsys battery unplug

# 3) 拔线 → 息屏 → 静置 60 分钟，不要碰手机

# 4) 采数据
adb shell dumpsys batterystats > bs.txt
adb shell dumpsys batterystats --checkin > checkin.txt
adb shell dumpsys batterystats --history > history.txt
adb logcat -d -v time -s MqttManager MqttFgService MqttAutoConnect > log.txt

# 5) 还原电池状态
adb shell dumpsys battery reset
```

### 2.2 读哪几个数

在 `bs.txt` 中搜索这些关键字（不同版本字段略有差异，按关键字找）：

| 搜索关键字 | 得到什么 | 用途 |
| --- | --- | --- |
| `Estimated power use` | 每个包的 `wake` / `jobs` / 估算 mAh | **A/B 主指标** |
| `Package: com.mymesag` | 本 App 的完整统计段 | 看 `wakelocks` 次数与总时长 |
| `Network` | Wi‑Fi / 移动网络 rx/tx 字节 | 判断心跳字节量与重连次数 |
| `Alarms` / `Jobs` | 闹钟与 job 计数 | 当前版本应为 0（无 AlarmManager/JobScheduler） |
| `Device idle` / `Doze` | 是否真的进入过 idle | 没进 Doze 的轮次不能和进了 Doze 的比 |
| `Screen` | 息屏总时长 | 确认这 1 小时确实没亮屏 |

快速提取：

```bash
grep -A25 "Estimated power use" bs.txt
grep -n "com.mymesag" bs.txt | head -30
```

### 2.3 记录表模板（每轮填一行）

| 组 | 变量 | 时长 | MyMesag mAh | wakelock 次数 | wakelock 总时长 | 网络 tx/rx | 进 Doze | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | App 停用（对照） | 60min |  |  |  |  |  | 整机底噪 |
| 1 | 心跳 60s | 60min |  |  |  |  |  |  |
| 2 | 心跳 300s | 60min |  |  |  |  |  |  |
| 3 | 心跳 600s | 60min |  |  |  |  |  |  |

**净增量 = 组1 − 组0**。只看 App 自己的 mAh 容易把整机底噪算进来，对照组是必须的。

---

## 3. 测法 B：唤醒时间线（定位"谁在叫醒手机"）

### 3.1 原始 history

```bash
adb shell dumpsys batterystats --history > history.txt
grep -E "wake_reason=|\+running=|-running=" history.txt | head -80
```

-   `+running=` / `-running=` 成对出现，就是 CPU 被拉起的区间；相邻两个 `+running=` 的间隔 ≈ 一次唤醒周期
-   心跳 60s 时，理论上应看到约每 60 秒一对 `running`（若间隔明显更长，说明深睡期间根本没被叫醒，见第 6 节的 ping 假设）

### 3.2 Battery Historian（图形化）

```bash
adb bugreport br.zip
```

两种看法，任选：

```bash
# 方式一：pip 安装（工具已停止维护，Android 15+ 可能解析不完整，失败就回退到 3.1）
pip install battery-historian
historian br.zip > historian.html

# 方式二：Docker 官方镜像
docker run -d -p 9999:9999 gcr.io/android-battery-historian/stable:3.0 --port 9999
# 浏览器打开 http://localhost:9999 上传 br.zip
```

Historian 的价值：一条时间轴上同时看 Doze 区间、wakelock、网络活动、CPU running，一眼看出「息屏后第几分钟开始没有心跳」。

---

## 4. 测法 C：A/B 变量矩阵（优化决策依据）

一次只改一个变量，其余条件与 2.1 完全相同。

### 4.1 对照组：彻底停用 App

```bash
adb shell pm disable-user --user 0 com.mymesag
# 测完必须恢复
adb shell pm enable com.mymesag
```

> 注意：停用会同时停掉通知监听，得到的是「完全没有本 App」的整机底噪。

### 4.2 心跳间隔（最重要的一组）

不改代码，直接在 App 内改：**同步设置 → 系统设置与保活 → MQTT 心跳间隔**，可选 60 / 120 / 300 / 600 秒，修改后会自动重连生效。

| 心跳 | 每小时唤醒次数（理论） | 断线发现延迟（理论 ≈1.5×） |
| --- | --- | --- |
| 60s | 60 | ~90 秒 |
| 300s | 12 | ~7.5 分钟 |
| 600s | 6 | ~15 分钟 |

理论值是上界，实测若明显低于理论值，说明深睡期间心跳没唤醒设备（见第 6 节）。

### 4.3 Doze 白名单开关

```bash
# 加入白名单（等价于"不受电池优化限制"，不用去设置里点）
adb shell dumpsys deviceidle whitelist +com.mymesag

# 查看当前白名单
adb shell dumpsys deviceidle whitelist

# 移出
adb shell dumpsys deviceidle whitelist -com.mymesag
```

对比：白名单开/关时，同一心跳间隔下的唤醒次数、断网恢复延迟差异。**这一组同时回答"白名单到底值不值得引导用户开"。**

### 4.4 断网态（测重连循环的成本）

飞行模式或关 Wi‑Fi，静置 60 分钟，此时重连循环处于 30 秒平台期。

```bash
adb shell cmd connectivity airplane-mode enable    # 部分系统支持，失败就手动开飞行模式
adb logcat -c
# 静置 60 分钟
adb logcat -d -v time -s MqttManager > reconnect.txt
grep -c "reconnect attempt .* in 30000ms" reconnect.txt
grep -c "network unavailable, skip reconnect attempt" reconnect.txt
```

两个计数含义不同，务必分开看（日志前缀分别是 `reconnect attempt` 与 `network unavailable, skip`）：

| 计数 | 含义 | 能耗影响 |
| --- | --- | --- |
| `in 30000ms` 出现次数 | 排定了多少次 30 秒后的重试（理论上限 120 次/小时） | 每次只是一次协程唤醒，**不代表发了网络包** |
| `skip reconnect attempt` 次数 | 其中因本地网络判定不可用而**跳过**的次数 | 跳过时不唤醒射频、不消耗失败配额，成本极低 |

两者相减才是真正发起 TCP/TLS 连接的次数。若第二个数接近第一个数，说明断网期间的重试成本可忽略，无需为省电而拉长退避。

### 4.5 蜂窝 vs Wi‑Fi

同参数下分别测一轮。蜂窝的射频「拖尾能耗」（发完小包后射频仍高功率 10 秒级）远高于 Wi‑Fi，通常能解释「外出挂机比在家掉电快很多」。

### 4.6 最坏情况（可选）

```bash
adb shell cmd appops set com.mymesag RUN_ANY_IN_BACKGROUND ignore   # 模拟 ROM 严格管控
adb shell cmd appops set com.mymesag RUN_ANY_IN_BACKGROUND allow    # 还原
```

用来预测「用户什么都不授权」时的行为。

---

## 5. 测法 D：短时强制 Doze 压测（10 分钟出结果）

不想每次等 1 小时时：

```bash
adb shell dumpsys batterystats --reset
adb shell dumpsys deviceidle force-idle          # 立刻进入 idle
adb shell dumpsys deviceidle step                # 单步推进 idle 子状态，可多执行几次
adb logcat -c

# 静置 10 分钟
adb logcat -d -v time -s MqttManager MqttFgService > idle_test.txt
adb shell dumpsys batterystats > bs_idle.txt

adb shell dumpsys deviceidle unforce             # 测完必须还原
```

观察点：`force-idle` 之后日志里还出现多少次心跳/重试。一次都不出现 → 深睡期间进程被完全冻结（节能好，但连接必被 broker 超时踢掉）。

---

## 6. 两个专项验证

### 6.1 验证 dataSync 6 小时墙已在 beta2 消除

原生 Android 15+ 对 `dataSync` 类型的前台服务限制为每 24 小时累计 6 小时，耗尽后系统强停服务，且再次启动会抛异常，只有把 App 调到前台才重置计时。beta2 已改用无时长上限的 `remoteMessaging`。

```bash
# 决定性证据：isForeground + types 掩码（0x00000001=dataSync，0x00000200=remoteMessaging）
adb shell dumpsys activity services com.mymesag | grep -oE "isForeground=[a-z]+ foregroundId=[0-9]+ types=0x[0-9a-f]+"

# 挂机 6 小时以上后检查这两条错误是否出现（旧版必现，新版不应出现）
adb logcat -d | grep -iE "did not stop within its timeout|Time limit already exhausted"
```

> 确认设备上跑的是哪个构建：先看 `adb shell dumpsys package com.mymesag | grep -E "versionCode|versionName"`，再和 `adb shell pm path` 指向的 apk 字节数对一遍。第三方改机 ROM 上两者偶有不一致，此时以前台服务 `types=` 掩码这类行为特征为准。

判读：

| 现象 | 结论 |
| --- | --- |
| 6 小时后服务仍 `isForeground=true`，无上述两条日志 | 墙已消除，问题解决 |
| 6 小时后服务被停，且 `types=0x00000001` | 装的仍是 `dataSync` 旧包，先核对设备上实际安装的构建 |
| 服务正常但连接仍断 | 不是配额问题，转 6.2 或第 4.3 的白名单方向 |

### 6.2 息屏心跳漏发的两个叠加成因（已定位并修复）

长时息屏掉线不是单一原因，实测确认下面两处必须同时修，只改一处无效：

| 成因 | 表现 | 修复 |
| --- | --- | --- |
| Paho 默认 `TimerPingSender` 基于 `java.util.Timer`，CPU 深睡时不触发 | 深睡期间完全没有发心跳的尝试 | `AlarmPingSender`：用 `setExactAndAllowWhileIdle` 唤醒后执行 `checkForActivity` |
| `ClientState` 的存活判定取 `System.nanoTime()`（`CLOCK_MONOTONIC`，内核 suspend 期间不推进） | 闹钟按时唤醒了，但 Paho 按单调时钟认为「还没到点」，只重排闹钟、不发实包 | `MqttManager.BootTimeTimer`：向 `MqttAsyncClient` 6 参构造注入基于 `SystemClock.elapsedRealtimeNanos()`（`CLOCK_BOOTTIME`）的计时器 |

证据链（2026-11-27，EMQX 5.8 `emqx ctl log set-level debug` 抓 broker 侧报文）：

-   修复前：同一连接内 PINGREQ 间隔约 112 秒（约定 60 秒），下一条连接 120 秒内一个都没发；每条连接都以 `socket_force_closed, reason: keepalive_timeout` 收尾
-   broker 侧 `mqtt.keepalive_multiplier = 1.5`、`keepalive_check_interval = 30s` → 允许静默 90 秒、按 30 秒粒度检查，实际踢线落在 90～120 秒，与客户端 `connectionLost` 的时刻吻合
-   手机侧 `dumpsys alarm` 里 `MQTT_PING.n` 为 `window=0 exactAllowReason=allow-listed`，同一 PendingIntent 已 `3 wakes` → ROM 按时投递了闹钟，问题不在系统调度侧
-   同一份 APK 在 MagicOS 荣耀与 Xperia 上几乎同步掉线 → 与厂商 ROM 无关，是应用层共因

修复后验收：broker 侧每个 clientid 约 60～70 秒一条 `PINGREQ`，且 `docker compose logs | grep -c keepalive_timeout` 为 0。

> **加大 `keepAlive` 治不了这个问题**：Paho 按冻结的单调时钟发心跳，实际间隔总是大于设定值（修复前约 2×），而 broker 只容忍 1.5×，这个比例不随时长档位改变。
>
> **代价**：60 秒心跳意味着每小时约 60 次强制唤醒，是待机功耗里最大的可控项——具体档位由 4.2 的量化结果决定。

---

## 7. 数据判读 → 优化动作对照表

| 测到的现象 | 根因方向 | 候选动作 |
| --- | --- | --- |
| 心跳 60s 组比 300s 组 wakelock 次数多 5 倍，mAh 也明显高 | 心跳是待机大头 | 提高默认心跳间隔；或按息屏/前台动态调档 |
| 断网态 mAh 明显高于联网态 | 30 秒重试循环 | 断网时把重试退避拉长；或让重连交给网络回调而非定时器 |
| 蜂窝组 mAh 远高于 Wi‑Fi 组 | 射频拖尾 | 延长心跳、合并发布（减少小包次数） |
| App 停用组与运行组几乎无差 | 常驻通知/进程驻留不耗电 | 不必在这上面优化，精力放在唤醒次数 |
| 单次同步一条通知的 wakelock 时长 >1 秒 | 落盘/加解密路径 | 检查 DataStore 写入次数、数据库批量化 |
| 日志出现大量 `Time limit already exhausted` | 又用回 `dataSync` 了 | 检查 manifest 类型与权限 |

---

## 8. 测试结束：环境还原清单

**每一项都要执行，漏掉会影响后续正常使用**：

```bash
adb shell dumpsys battery reset                              # 还原真实电池上报
adb shell dumpsys deviceidle unforce                         # 取消强制 Doze
adb shell dumpsys deviceidle whitelist -com.mymesag          # 若加过白名单
adb shell pm enable com.mymesag                              # 若停用过
adb shell cmd appops set com.mymesag RUN_ANY_IN_BACKGROUND allow   # 若设置过 ignore
adb shell cmd connectivity airplane-mode disable             # 若用命令开过飞行模式
```

`force-idle` 不还原的话，手机会一直处于人为 idle，通知和后台都异常，很容易误判成新 Bug。

---

## 9. 常见问题

-   **`--reset` 后还有历史数据？** `batterystats` 有两层：全局累计与本轮统计。以 `Estimated power use` 段为准，它按最近一次 reset 起算。
-   **两轮数据差 20% 以上？** 先检查：是否进了 Doze（`Device idle` 段）、是否亮过屏、另一台配对设备是否在线、是否有新通知。
-   **`bugreport` 几十 MB？** 正常，Historian 只解析其中的 batterystats 文本部分。
-   **Historian 打不开或解析失败？** 该工具已停止维护，新系统上可能失败。直接读 `history.txt` 的 `+running=` 行同样能算出唤醒周期。
-   **测完才发现 logcat 缺了前半段？** `logcat -d` 读的是环形缓冲区，长时段（尤其通知量大时）早期日志会被挤掉。规避：脚本在开始时执行 `logcat -c` 清零，并在测试期间另开一个终端挂着 `adb logcat -v time -s MqttManager > stream.txt` 持续落盘；或者把时长拆成两个 30 分钟轮次。
-   **想测"点一次按钮/同步一条通知"的精确能耗？** 用 Android Studio → Profiler → Energy 连机看逐 tick 曲线，比 `batterystats` 更适合单事件；`batterystats` 适合长时间挂机统计。
-   **手机装 AccuBattery 行不行？** 可以作长期趋势参考，但它是整机估算，精度不足以分辨 60s 与 300s 心跳的差别，A/B 仍以 adb 为准。

---

## 10. 建议执行顺序

1.  跑组 0（停用对照）+ 组 1（60s）+ 组 3（600s）→ 得到净增量与心跳占比
2.  跑 6.1 → 确认 beta2 的 `remoteMessaging` 生效
3.  跑 4.3 → 量化白名单收益，决定是否在产品里加强引导
4.  按 6.2 的验收项 → 确认 broker 侧每个 clientid 每 60～70 秒一条 `PINGREQ`、无 `keepalive_timeout`
5.  拿数据回来，再决定改默认心跳档位 / 加 WorkManager 兜底中的哪一个
