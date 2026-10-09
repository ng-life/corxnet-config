# mqtt-init 项目说明

## 项目目标

本项目是面向科星 CX-8308W 等兼容控制器的 Rust 命令行网络配置工具。UDP 配置协议以 `docs/20.docx` 为准。用户手册没有定义的命令不得加入工具，也不得以试探未知命令的方式探索设备。

## 项目结构

- `src/main.rs`：CLI 定义、UDP 广播和响应处理、配置解析与设置报文编码。
- `Cargo.toml` / `Cargo.lock`：Rust 包和依赖版本；CLI 使用 `clap`，Shell 补全使用 `clap_complete`。
- `README.md`：中文构建、使用、协议与补全指南。功能或命令变化时同步更新。
- `docs/20.docx`：原始设备协议资料。变更报文或参数映射前先核对本文档。

## 命令与协议边界

只实现 `docs/20.docx` 明确列出的操作，以及 `docs/MQTT.md` 根据用户提供抓包记录的 MQTT 读写操作：

- `scan`：向 `255.255.255.255:60000` 发送五个 `00` 字节，在 UDP `60001` 接收响应。
- `read`：固定使用读取命令 `0A`，请求并解析 256 字节配置；`--raw` 仅影响本地显示，不改变设备报文。
- `set`：只设置文档列出的单项网络参数；每次配置后按协议发送保存命令。
- `mqtt-read` / `mqtt-set`：按 `docs/MQTT.md` 中分析的帧格式读取或保存完整 MQTT 配置；写入前必须读取并留好恢复值。
- `completions`：通过 `clap_complete` 生成 Bash、Zsh、Fish 补全，不发送网络报文。

不得保留任意命令字、内层指令或未知报文探测接口。设备是磁保持继电器控制器；不要加入继电器控制报文。测试 MQTT 写入时必须先保留原配置，若值发生变化则恢复并复读确认。

协议细节：

- 设备 UDP 端口为 `60000`，本机接收端口为 `60001`。
- 配置寻址字段使用设备 MAC 后五字节；扫描响应也只提供这五字节。
- 读取报文为 `0A + MAC后5字节 + 0A FF FF FF FF`。
- 保存报文为 `FF + MAC后5字节 + FF FF FF FF`。
- MQTT 报文以 `docs/MQTT.md` 中的抓包分析为准；该格式来自用户提供的设备抓包，不属于 `20.docx` 明确协议内容。
- 端口值低字节先发；DHCP、模式、文本长度和保留字节按 `docs/20.docx` 编码。
- Linux 上指定网卡使用 `SO_BINDTODEVICE`；Windows 和 macOS 上按指定网卡的 IPv4 地址绑定 UDP socket。修改跨平台网卡处理时应明确说明各平台的实现方式。

## 开发约定

- 使用中文维护本文件和 README；CLI 帮助面向中文用户。
- 参数校验应在构造 UDP 报文之前完成，错误信息指出具体格式或范围。
- 修改设备报文时优先用纯函数完成编码，再由 UDP 层发送；不要将网络副作用藏在解析逻辑中。
- 默认保持扫描和读取只读。除非任务明确要求，不要在开发过程中向真实控制器发送 `set` 报文。
- 不提交、记录或打印用户凭据；不要推断未定义原始数据字段的含义。
- 修改 CLI 参数、可选值或子命令后，以 `clap` 定义为准，由 `clap_complete` 生成各 Shell 脚本；不要另行维护手写补全脚本。
- Cargo 依赖变化时更新并保留 `Cargo.lock`。

## 常用命令

```sh
cargo fmt
cargo check
cargo build --release
cargo run -- --help
cargo run -- completions bash
cargo run -- completions zsh
cargo run -- completions fish
```

README 应继续说明网卡参数、MAC 后五字节、协议端口、各设置项范围、保存行为和 Shell 补全安装方法。
