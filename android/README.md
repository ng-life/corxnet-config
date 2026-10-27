# Android 设备配置应用

原生 Android 应用，网络及 MQTT 协议与 Go CLI 一致。最低 Android 8.0（API 26），目标 Android 15（API 35）。二维码通过 Camera2 和 ZXing 在本机识别，不需要 Google Play 服务或互联网。

## 使用

1. 手机连接控制器所在的 Wi-Fi。应用将 UDP socket 绑定到实际 Wi-Fi 网络，避免移动数据或 VPN 默认路由截走广播；本机监听 `0.0.0.0:60001`，兼容设备的广播和单播回复，向 `255.255.255.255:60000` 发送请求。网络须允许设备间通信，访客隔离可能导致读取超时。
2. 启动进入「选择设备」页面（`MainActivity`），只负责选择设备，不展示配置表单。可扫描设备二维码 `{"mac":"0090E2D72060"}`，也可点击「扫描局域网设备」。局域网扫描发送五个 `00` 字节，解析 MAC 后五字节并补首字节 `00`，去重显示 MAC 与响应地址，点击设备进入配置。也支持手动输入完整 MAC 或设备码 JSON。
3. 获取 MAC 后进入独立的「设备配置」页面（`DeviceConfigActivity`），自动读取网络和 MQTT 参数。两个 Tab 分别展示「网络配置」和「MQTT 配置」，切换保留未保存的编辑内容；每个 Tab 独立滚动，保存按钮固定在底部。
4. 在当前 Tab 修改参数，点击对应保存按钮，预览后分别保存。两组读取结果分别处理；一组失败不会隐藏另一组成功结果。可点击「重新读取参数」重试。
5. 配置页点击「扫码一键配置」，扫描包含 `network` / `mqtt` 的配置码，预览本次修改后，一键保存。无法使用相机时可点击「粘贴配置码 JSON」。配置页不再负责切换设备，返回选择页可继续扫码或选择局域网设备。

扫码界面使用独立的 `ScannerActivity`，设备选择页仅处理设备码，配置页仅处理配置码；相机权限只在扫码时请求。

网络模式取值与 CLI 一致：`0` TCP 单连接服务器、`1` TCP 客户端、`2` UDP 服务器、`3` MODBUS 服务器、`4` UDP 客户端、`5` MODBUS 客户端、`6` TCP 多连接服务器、`7` 自建 MQTT 服务器、`8` 蚂蚁云开平台、`9` 第三方云平台。

DNS 的读取偏移未定义，界面只提供设置；留空保持不变。网络参数 `heartbeat` 是 `0/1` 开关，与 MQTT 的心跳主题、内容及间隔不是同一字段。

## 快捷配置二维码

顶层只允许 `network` 和 `mqtt` 两个对象，可以只包含其中一个，也可以同时包含。设备选择由第一张设备码确定，配置码不接受顶层 `mac` 或任意命令字。

```json
{
  "network": {
    "mode": 7,
    "port": 1883,
    "target": "192.168.0.100"
  },
  "mqtt": {
    "subscribe_topic": "example/device/control",
    "publish_topic": "example/device/event",
    "device_id": "example-device",
    "heartbeat_topic": "example/device/heartbeat",
    "heartbeat_content": "alive",
    "heartbeat_interval": 30
  }
}
```

未提供的字段保持原值；显式空字符串表示清空文本，`null` 和未知字段会被拒绝。数值字段允许 JSON 整数或整数字符串，拒绝小数与布尔值。设备码及配置码示例见 [examples](examples/)。示例不包含真实用户名和密码。

- `network`：`ip`、`target`、`gateway`、`netmask`、`mode`、`port`、`mac`、`dhcp`、`id`、`hostname`、`dns`、`heartbeat`，取值及字节限制见 [CLI 文档](../README.md)。`network.mac` 表示修改设备 MAC，不用于选择目标设备。
- `mqtt`：`username`、`password`、`subscribe_topic`、`publish_topic`、`device_id`、`heartbeat_topic`、`heartbeat_content`、`heartbeat_interval`，格式见 [MQTT 协议](../docs/MQTT.md)。文本限不含 NUL 的 ASCII；前五项各最多 100 字节，心跳主题与内容合计最多 100 字节，间隔为 `0–255` 秒。

## 保存顺序和恢复

应用使用单线程队列串行执行设备操作。每次一键保存会先校验全部请求字段，读取要修改的两组原值，合并并校验完整 MQTT 配置，再开始写入。

1. MQTT 先保存，等待目标地址发来的 `44 AA AA 44`，复读核验全部八项。保存应答丢失时仍复读确认。复读失败或不一致时，尝试恢复写前完整 MQTT 原值并再次复读，明确提示恢复是否确认。
2. 网络设置按 `dns → hostname → id → heartbeat → target → gateway → netmask → mode → port → dhcp → ip → mac` 的顺序逐项发送，并在每项后发送保存命令。DHCP、IP 和 MAC 最后处理，避免提前改变寻址。MAC 保存后，后续读取使用新 MAC。
3. 最后复读网络参数并比对本次修改项。DNS 无定义的读取字段，仅报告保存帧已发送，不宣称 DNS 已核验。

一键保存不是原子事务。失败时停止后续步骤，并列出已经完成或已发送的项目；不会将已保存的 MQTT 或网络参数整体回滚。MQTT 自身核验失败时，仍会按上述规则尝试恢复。若网络已部分生效但复读失败，编辑基线会清空，需重新读取。IP、DHCP 或 MAC 变化后可能需要重新扫码或调整手机网络。

参数、二维码和 MQTT 恢复值只在进程内保留；旋转屏幕保留当前会话，进程结束后清除。凭据不写入日志、偏好设置或磁盘，字段不进入系统自动填充和视图状态。涉及凭据的页面禁止系统截屏。应用退出或被系统终止时，无法保证仍能完成正在进行的设备写入或恢复；保存期间应保持应用与 Wi-Fi 可用。

## 构建

需要 Java 17、Android SDK Platform 35 和 Build Tools 35.0.0。Gradle Wrapper 固定为 8.9，Android Gradle Plugin 为 8.7.3。可通过 mise 提供 Java 17，Android SDK 可由 Android Studio 管理。

```sh
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk" # macOS 默认路径；按本机实际位置调整
mise exec -- ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

也可以在 Android Studio 中打开 `android/` 目录，选择 Java 17 和本机 SDK。不要提交 `local.properties`、签名密钥或密码。

调试 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。此 APK 使用开发机的调试签名，可供本机或测试设备安装；正式分发前需自行配置 release 签名。`versionName` 独立于 CLI，当前为 `0.1.1`（`versionCode=2`）。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试与验证边界

```sh
# Android JVM 单元测试及 Lint
mise exec -- ./gradlew :app:testDebugUnitTest :app:lintDebug
# 连接模拟器或测试设备后运行界面流程测试
mise exec -- ./gradlew :app:connectedDebugAndroidTest
# 仓库根目录：保证共享报文样本继续与 Go CLI 一致
go test -race ./...
```

`app/src/test/resources/go-protocol-fixtures.json` 只含合成数据，由 Go 编解码生成；Go 和 Android 测试共用该样本，逐字节验证网络/MQTT 报文及响应。Android 单元测试覆盖局域网发现和去重、配置码解析、部分字段合并、MQTT 核验与恢复、应答丢失、参数保存顺序和部分失败提示。仪器测试覆盖设备选择到配置页的跳转、局域网扫描选择、Tab 切换保留草稿、分别保存、一键预览保存及相机开启关闭。模拟设备在测试 APK 内，不打开 UDP socket，不访问真实控制器。

这些检查不能替代 Android 真机相机、厂商 Wi-Fi 广播行为和真实控制器读写验证。真实写入测试应先备份，发生变更后恢复并复读；只使用协议已有命令。

## 扫描不到设备时

- 确认电脑 CLI 在同一 Wi-Fi 下也能扫描到，排除控制器供电、网段和访客网络隔离问题。
- Wi-Fi 选路使用 `Network.bindSocket`，UDP 接收监听 `0.0.0.0:60001`，不绑定手机的具体 IP；具体地址绑定可能漏收广播回复。`MulticastLock` 只在会话期间持有。
- 应用已声明 `INTERNET`、网络/Wi-Fi 状态及 `CHANGE_WIFI_MULTICAST_STATE`，广播发送已启用 `SO_BROADCAST`。普通 UDP 设备发现不是扫描周围 Wi-Fi 热点，不为此请求定位权限。
- Android 16 的局域网保护是开发者选择启用的测试功能；当前应用目标 SDK 35，默认不需要额外请求 `NEARBY_WIFI_DEVICES`。如果通过兼容性配置显式启用了 `RESTRICT_LOCAL_NETWORK`，才需按系统要求处理附近设备权限；不要仅因收包超时就判断为权限不足。
- Android 17 的 `ACCESS_LOCAL_NETWORK` 要求针对目标 SDK 37 及以上；当前应用不申请该权限。系统版本或厂商网络管理策略变化时需重新核对。

平台行为依据：[DatagramSocket 广播接收说明](https://developer.android.com/reference/java/net/DatagramSocket)、[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。

## GitHub Release

Android APK 与桌面平台包在项目的 `vX.Y.Z` Release 中一起提供，Android 应用版本独立维护。当前分发文件为 `corxnet-config-android-debug.apk`，使用维护者本机已有的调试签名，便于覆盖此前测试安装；它不是面向应用商店的正式签名包。

标签工作流先构建桌面三平台并创建 Release 草稿，Android 工作流完成构建、单元测试及 Lint。维护者本机验证 Android APK 与此前测试版签名一致后，将 APK 上传到草稿，重新生成包含三个桌面包和 APK 的 `SHA256SUMS`，核对全部资产后发布草稿。CI 生成的调试 APK 可能使用不同证书，不应直接替代维护者签名的分发包。

签名密钥不进入仓库或 Release。更换签名将影响覆盖安装；发布前必须检查签名连续性。
