# corxnet-config

用于科星控制器的 Rust UDP 网络配置命令行工具。设备扫描和读取不会更改参数；`set` 每次下发一项配置，并按协议发送保存命令。

## 构建和安装

需要 Rust 工具链。在项目目录构建：

```sh
cargo build --release
./target/release/corxnet-config --help
```

也可以安装到 Cargo 的可执行文件目录：

```sh
cargo install --path .
```

## 命令

```text
corxnet-config [-i|--interface <网卡>] scan
corxnet-config [-i|--interface <网卡>] read <完整MAC> [--raw]
corxnet-config [-i|--interface <网卡>] set <完整MAC> <参数> <值>
corxnet-config [-i|--interface <网卡>] mqtt-read <完整MAC>
corxnet-config [-i|--interface <网卡>] mqtt-set <完整MAC> --username <用户名> --password <密码> --subscribe-topic <主题> --publish-topic <主题> --device-id <ID>
corxnet-config [-i|--interface <网卡>] serve [--listen <IP:端口>]
corxnet-config completions <bash|zsh|fish>
```

MQTT 配置的协议推测、读写命令和字段限制见 [docs/MQTT.md](docs/MQTT.md)。`mqtt-set` 会整体保存五个 MQTT 字段；测试和修改前应先读取并保留现有配置，以便必要时恢复。

`-i/--interface` 可放在命令前或后。Linux 可用 `ip link`、macOS 可用 `networksetup -listallhardwareports`、Windows PowerShell 可用 `Get-NetAdapter` 查看网卡名。未指定时使用系统路由选择的出口网卡。

程序支持 Linux x86_64、Windows x86_64 和 macOS Apple Silicon（arm64）。Linux 上 `-i` 按网卡名绑定 socket；Windows 和 macOS 上会查找该网卡的 IPv4 地址并绑定 socket。示例：

```powershell
.\corxnet-config.exe -i "以太网" scan
```

```sh
./corxnet-config -i en0 scan
```

## 网页配置服务

```sh
corxnet-config -i en0 serve
# 需要同一局域网内其他电脑访问时：
corxnet-config -i en0 serve --listen 0.0.0.0:8080
```

启动后使用浏览器访问 `http://127.0.0.1:8080`。监听全部地址时，其他电脑使用运行程序的电脑 IP 访问，例如 `http://192.168.0.10:8080`。网页通过服务所在电脑的网卡收发 UDP；`--interface` 在启动时确定。按 Ctrl+C 停止服务。

HTML、CSS 和 JavaScript 通过 `include_str!` 内嵌到可执行文件，运行时无需外部网页文件、Node.js 或 CDN。页面采用 Ant Design Pro 风格布局，提供设备扫描、网络参数读取与单项保存、MQTT 参数读取与整体保存；也可手动输入完整 MAC。原始读取响应可在网页展开查看。DNS 地址的读取偏移未在协议中定义，页面只提供 DNS 设置，不推断读取值。

设备操作按顺序执行，避免并发争用 UDP `60001`。网页写入前显示确认弹窗。网络参数保存结果表示报文已发送，应重新读取核对；更改 IP、DHCP 或 MAC 后请重新扫描，并使用新的 MAC。

MQTT 编辑必须先读取。服务为每个 MAC 保留本次服务首次读取的五个原值（最多 128 台设备），仅存于内存；后续读取不覆盖此恢复基线。写入前再次读取，成功后才发送保存帧，并自动复读比对。发送后超时或不一致会提示核验失败，不会自动宣称保存成功；可点击“恢复首次读取的原值”并复读核验。重启服务会清除原值。网页明文展示 MQTT 用户名和密码，不写入浏览器存储或服务日志。

服务不提供登录认证，只应在可信网络使用。默认仅本机访问；扩大监听范围会允许可访问该端口的用户配置设备。HTTP 不加密，凭据会以明文传输。设备接口不启用跨域访问，校验 Origin 与请求标记，并只接受 IP 或 `localhost` 作为访问地址。

HTTP 接口返回 JSON。设备操作使用 POST，请求需携带 `X-Corxnet-Request: 1`；浏览器 Origin 必须与服务地址一致：

| 接口 | 请求内容 |
| --- | --- |
| `GET /api/info` | 查看启动时选定的网卡 |
| `POST /api/scan` | `{}` |
| `POST /api/read` | `mac` |
| `POST /api/set` | `mac`、`setting`、`value`，参数名和取值与 CLI 一致 |
| `POST /api/mqtt/read` | `mac`，并保留首次原值 |
| `POST /api/mqtt/set` | `mac`、`config`，后者包含 `username`、`password`、`subscribe_topic`、`publish_topic`、`device_id` |
| `POST /api/mqtt/restore` | `mac`，恢复首次原值并核验 |

## 扫描设备

```sh
corxnet-config -i br0 scan
```

工具向 `255.255.255.255:60000` 广播五个 `00` 字节，并在 UDP `60001` 接收响应。协议响应只携带 MAC 后五字节；工具按该设备格式补出首字节 `00`，扫描结果显示完整 MAC。所有命令参数都要求完整 MAC。

## 读取配置

```sh
corxnet-config -i br0 read 00:90:E2:D7:20:60
corxnet-config read 00:90:E2:D7:20:60 --interface br0 --raw
```

`read` 使用 `0A + MAC后5字节 + 0A FF FF FF FF` 请求设备返回 256 字节配置。目标必须填写扫描结果中的完整 MAC。`--raw` 会在解析结果后打印原始响应的十六进制和 ASCII 数据。

## 设置参数

```sh
corxnet-config -i br0 set 00:90:E2:D7:20:60 ip 192.168.0.18
corxnet-config set 00:90:E2:D7:20:60 mode 0
corxnet-config set 00:90:E2:D7:20:60 port 50000
corxnet-config set 00:90:E2:D7:20:60 dhcp 0
corxnet-config set 00:90:E2:D7:20:60 id sensor-01
corxnet-config set 00:90:E2:D7:20:60 hostname broker.example.net
```

每条 `set` 只设置一个参数。字符串值含空格时用引号括起，例如 `id "sensor west"`。工具发送配置后尝试接收设备响应，再发送 `FF + MAC后5字节 + FF FF FF FF` 保存。更改 IP、DHCP 或 MAC 后设备地址可能改变；更改 MAC 后，后续命令应使用新 MAC。

支持的参数和协议命令：

| 参数 | 值 | 命令字 |
| --- | --- | --- |
| `ip` | IPv4 地址 | `01` |
| `target` | 目标 IPv4 地址 | `02` |
| `gateway` | 网关 IPv4 地址 | `03` |
| `mode` | `0`–`9` | `04` |
| `port` | `1`–`65535` | `05` |
| `netmask` | 子网掩码 | `07` |
| `mac` | 完整 MAC 地址 | `F8` |
| `dhcp` | `0` 静态，`1` DHCP | `09` |
| `id` | 最多 12 字节 | `12` |
| `dns` | DNS IPv4 地址 | `19` |
| `hostname` | DNS 网址，最多 56 字节 | `20` |
| `heartbeat` | `0` 关闭，`1` 开启 | `0C` |

模式编号：

| 值 | 模式 |
| --- | --- |
| `0` | TCP 单连接服务器 |
| `1` | TCP 客户端 |
| `2` | UDP 服务器 |
| `3` | MODBUS 服务器 |
| `4` | UDP 客户端 |
| `5` | MODBUS 客户端 |
| `6` | TCP 多连接服务器 |
| `7` | 自建 MQTT 服务器 |
| `8` | 蚂蚁云开平台 |
| `9` | 第三方云平台 |

端口字段低字节在前，例如 50000 编码为 `50 C3`。UDP 客户端模式的目标端口和本机端口须相同。服务器模式的端口是本地端口；客户端模式的端口是远程服务器端口。启用 DHCP 后无需单独配置 DNS。

## Shell 补全

补全脚本由 `clap_complete` 根据命令定义生成。以下命令假设已安装 `corxnet-config` 并且它位于 `PATH` 中；修改 CLI 后重新运行命令即可更新脚本。

### Bash

临时加载：

```sh
source <(corxnet-config completions bash)
```

持久安装（需要启用 `bash-completion`）：

```sh
mkdir -p ~/.local/share/bash-completion/completions
corxnet-config completions bash > ~/.local/share/bash-completion/completions/corxnet-config
```

### Zsh

```sh
mkdir -p ~/.zfunc
corxnet-config completions zsh > ~/.zfunc/_corxnet-config
```

在 `~/.zshrc` 加入以下内容，然后重新打开终端或执行 `source ~/.zshrc`：

```zsh
fpath=(~/.zfunc $fpath)
autoload -Uz compinit && compinit
```

### Fish

```fish
mkdir -p ~/.config/fish/completions
corxnet-config completions fish > ~/.config/fish/completions/corxnet-config.fish
```

## 网络与协议

程序使用 UDP 广播地址 `255.255.255.255`、设备端口 `60000` 和本机接收端口 `60001`。Linux 上 `-i/--interface` 将 socket 绑定到指定网卡；Windows 和 macOS 则绑定指定网卡上的 IPv4 地址。各系统防火墙需允许 UDP `60001` 入站。

扫描请求为五个 `00` 字节。网络配置请求格式为 `命令字 + MAC后5字节 + 配置指令`；MQTT 帧格式见 [docs/MQTT.md](docs/MQTT.md)。网络参数变更需发送保存命令；MQTT 写入使用其独立的 `44 AA ... AA 44` 保存帧。
