# MQTT 配置读写协议与命令

本文根据用户提供的 CX-8308W 抓包和对应参数推测。MQTT 字段布局不是 `docs/20.docx` 明确记载的内容；设备型号或固件变化时，应先只读验证，不要直接套用写入。

## 抓包确认的帧格式

UDP 设备端口为 `60000`，本机端口为 `60001`，使用广播地址 `255.255.255.255`。帧中的 MAC 是完整 MAC 地址的 12 个 ASCII 十六进制字符（大写，无冒号）。

读取请求：

```text
33 BB + 12 字节 ASCII MAC + BB 33
```

读取响应以 `33 BB + MAC` 开始，以 `BB 33` 结束。

保存请求：

```text
44 AA + 12 字节 ASCII MAC + MQTT 参数区 + AA 44
```

抓包中保存帧与读取响应的参数区相同，UDP 负载长度为 623 字节。

## 参数区推测

参数区由五个固定宽度字段按以下顺序组成：

| 顺序 | 字段 | 样本值长度 | 长度字节 | 字段槽宽度 | 可用值长度 |
| --- | --- | ---: | --- | ---: | ---: |
| 1 | 用户名 | 68 | `44` | 101 字节 | 100 字节 |
| 2 | 密码 | 75 | `4B` | 101 字节 | 100 字节 |
| 3 | 订阅主题 | 24 | `18` | 101 字节 | 100 字节 |
| 4 | 发布主题 | 22 | `16` | 101 字节 | 100 字节 |
| 5 | 设备 ID | 15 | `0F` | 102 字节 | 101 字节 |

每个槽由一个二进制长度字节、对应长度的 ASCII 字节、以及补足槽宽的 `00` 组成。五个槽后有一个 `FF` 字节和 100 个 `00` 保留/填充字节，再接帧尾。槽宽和填充长度由这份抓包中的固定偏移推算；其中可用长度是格式推算值，不代表设备界面必然允许填满。

抓包确认了长度前缀、字段顺序和样本长度。用户名、密码内容本身不写入本仓库文档。

## 读取

```sh
corxnet-config -i br0 mqtt-read 00:90:E2:D7:20:60
```

完整 MAC 必须提供。默认隐藏用户名和密码。确实需要查看时加 `--show-secrets`：

```sh
corxnet-config -i br0 mqtt-read 00:90:E2:D7:20:60 --show-secrets
```

此命令只发送读取广播，不改动设备参数。

## 保存

`mqtt-set` 要求一次提供全部五个字段，避免未提供的字段被意外清空。可选择标准输入或命令行参数提供用户名和密码，二者不能混用。

推荐通过标准输入提供凭据，避免它们出现在命令参数中：

```sh
read -r -s -p 'MQTT username: ' MQTT_USERNAME
printf '\n'
read -r -s -p 'MQTT password: ' MQTT_PASSWORD
printf '\n'
printf '%s\n%s\n' "$MQTT_USERNAME" "$MQTT_PASSWORD" | corxnet-config -i br0 mqtt-set 00:90:E2:D7:20:60 \
  --credentials-stdin \
  --subscribe-topic 'SFQ257YC4K/WH-01/control' \
  --publish-topic 'SFQ257YC4K/WH-01/event' \
  --device-id 'SFQ257YC4KWH-01'
unset MQTT_USERNAME MQTT_PASSWORD
```

这会广播 `44 AA ... AA 44` 保存帧。工具不会回显 MQTT 凭据。保存后重新运行 `mqtt-read` 核验。

也可以直接使用 `--username` 和 `--password`：

```sh
corxnet-config -i br0 mqtt-set 00:90:E2:D7:20:60 \
  --username '<MQTT用户名>' \
  --password '<MQTT密码>' \
  --subscribe-topic 'SFQ257YC4K/WH-01/control' \
  --publish-topic 'SFQ257YC4K/WH-01/event' \
  --device-id 'SFQ257YC4KWH-01'
```

命令行参数可能被 shell 历史或系统进程信息记录；有凭据暴露风险时使用标准输入方式。

## 修改前备份与恢复

写入会整体替换 MQTT 参数。先用读取命令记录当前五个字段（用户名和密码需显式加 `--show-secrets` 并安全保管），再执行写入。若结果不符合预期，使用备份的五个原值运行 `mqtt-set` 恢复，并再次读取核验。不要把含凭据的终端输出、命令历史或备份文件提交到仓库。

针对本项目抓包设备的现场写入回归测试会先读取并在进程内保留原始响应，确认本机编码与当前帧完全一致后，才重发当前配置。若读回值不同，会从原始响应构造恢复帧并再次读取核验。它会发送一条保存广播，执行前确认设备在线且 MAC 匹配：

```sh
cargo test mqtt_live_save_keeps_or_restores_original_config -- --ignored --nocapture
```

该现场测试当前针对 `br0` 和抓包中的 MAC；不修改测试代码中的目标前，不要在其他设备上执行。常规 `cargo test` 不会运行此现场写入测试。
