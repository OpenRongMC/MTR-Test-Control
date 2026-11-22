# MTR Test Control

这是一个只用于测试实例的 Fabric 辅助模组，目标为 Minecraft 1.21.11。它不修改 MTR 或任何附属模组代码，也不应放进正式生存服务器。

## 游戏内命令

需要管理员权限（权限等级 2）：

```text
/mtrtest help          # 默认命名空间 minecraft
/ngntest help          # 默认命名空间 neoguanniao
/aerotest help         # 默认命名空间 aeronautics；list/giveall/suite 无参数时覆盖 aeronautics + simulated
```

共有命令（各根命令默认命名空间不同，见上）：

```text
give <item> [count]
giveall [namespace]
summon <entity>        # ngntest 默认 neoguanniao 实体 id
place <block>
placeat <x> <y> <z> <block> [prop=val,...]
inspect
list [namespace]
listitems [namespace]
suite [namespace] [limit]
variants [namespace]
redstone [namespace]
clear [radius]
tp <x> <y> <z>
gamemode creative|survival|spectator|adventure
setstate <prop=val,...>   # 看向的方块
footprint [w] [d] [h]     # 前方铺石平台并清空体积
wait <ticks>              # 异步等待（聊天提示完成）
use                       # 主手对看向方块 useItemOn
http
```

`place` 和 `inspect` 使用玩家当前朝向的相邻方块。`/aerotest suite`（无 namespace）会：列出并统计 `aeronautics` 与 `simulated` 的全部方块/物品、执行 `giveall`、在玩家前方网格逐个放置可放置方块、对有方块实体的方块执行 `saveWithoutMetadata` 检查，并输出 pass/fail 摘要。单命名空间 `suite aeronautics` / `suite simulated` 使用同一套增强逻辑。

## 本机控制接口

服务器启动后只绑定 `127.0.0.1` 的随机端口，并在日志中打印一次随机 token。通过 `/mtrtest http` 可查看端点。所有请求必须带：

```text
X-MTR-Test-Token: <日志中的 token>
```

可用请求：

```sh
curl -H "X-MTR-Test-Token: TOKEN" http://127.0.0.1:PORT/health
curl -H "X-MTR-Test-Token: TOKEN" http://127.0.0.1:PORT/state
curl -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/blocks?namespace=aeronautics&limit=500'
curl -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/items?namespace=simulated&limit=500'
curl -H "X-MTR-Test-Token: TOKEN" http://127.0.0.1:PORT/summary
curl -X POST -H "X-MTR-Test-Token: TOKEN" --data 'aerotest inspect' http://127.0.0.1:PORT/command
curl -X POST -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/giveall?namespace=aero&count=1'
curl -X POST -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/suite?kind=aero&limit=4096'
curl -X POST -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/wait?ticks=40'
curl -H "X-MTR-Test-Token: TOKEN" 'http://127.0.0.1:PORT/inspect?x=0&y=64&z=0'
```

`POST /suite` 的 `kind`：`registry`（默认）、`enhanced`、`aero` / `aero-full`（双命名空间完整套件）、`variants`、`redstone`。响应 JSON 含 `ok`、`passed`、`failed`、`skipped`、`summary` 等字段，便于自动化判定。`commandResult` 为 Brigadier 返回值（失败为 0）。

`/command` 在服务器主线程执行，HTTP 请求会等待最多 120 秒；有在线玩家时使用第一个在线玩家作为执行者。

## 客户端接口

客户端启动后单独监听一个本机随机端口，并打印 `Client test control listening at ... with token ...`。不要混用服务端和客户端 token；请求头仍为 `X-MTR-Test-Token`。读取和游戏操作在客户端主线程执行，请求体读取在 HTTP 线程完成。

- `GET /state`：世界、位置、手持物品、当前界面及重载遮罩、可点击控件（含文本框值和复选框状态）、容器槽位、客户端专用实体 ID、FPS 和 tick 计数。
- `GET /models`：检查所有非原版、采用普通模型渲染的方块状态，报告 Minecraft 缺失模型。动态渲染和贴图正确性仍需截图确认。
- `POST /action`：JSON 请求，`action` 支持 `inventory`、`close`、`look`（yaw/pitch）、`use`（x/y/z/face，受距离限制）、`use_item`、`click`（GUI 坐标 x/y）、`key`（GLFW key/modifiers）、`text`、`screenshot`（简单 PNG 文件名 name）、`reload`、`disconnect`、`stop`。
- `test_options` 临时关闭失焦暂停、将非活动 FPS 策略改为仅最小化限帧，并把 FPS 上限设为 60；用于后台测试，不主动保存选项。
- `scroll` 使用 GUI 坐标 `x/y` 和滚轮量 `amount`；`connect_test_server` 仅连接 `127.0.0.1:25565`，必须先断开现有世界。

截图通过游戏本身的截图流程异步保存到隔离实例的 `screenshots/`。返回的是请求的文件路径，调用者必须等待文件生成，不能把请求成功当作渲染测试成功。`use`/`use_item` 走真实客户端交互及网络包，不在服务端代为打开 GUI；界面变化需随后查询 `/state` 确认。

核心项目 `tools/client_test_api.py` 提供本机测试调用助手，从当前日志读取临时端点，不把 token 写进脚本。只对 `fabric/run/` 的隔离测试世界和 `fabric/client-run/` 操作。`/command` 的 accepted 仅表示命令已提交，不保证命令执行或客户端界面已经成功。

## Agent：完整 Aeronautics 套件

1. 启动带 `mtr_test_control`、Create Aeronautics（`aeronautics` + `simulated`）的隔离 Fabric 1.21.11 服务器，一名玩家在线并位于可放置方块的平坦区域。
2. 从日志读取 `Local test control listening at http://127.0.0.1:PORT with token ...`。
3. `POST /suite?kind=aero&limit=4096`（或游戏内 `/aerotest gamemode creative` 后 `/aerotest suite`）。
4. 解析 JSON：`ok == true` 且 `failed == 0` 且 `blockEntityFailures == 0` 为通过；否则查看 `failures` 数组。
5. 可选：`GET /summary` 复查最后一次套件结果；`GET /items?namespace=aeronautics` 与 `simulated` 核对注册表数量。

## 测试边界

这个模组提供可重复的控制入口和通用诊断，不会自动证明所有 GUI、网络包、应力网络、飞艇物理和特定方块使用流程正确。针对具体模组的高级场景应继续增加明确的测试脚本，并在隔离测试世界运行。

---

## 发布说明

- 仅用于测试实例，**不要**放进正式生存服。
- Fabric id：`mtr_test_control`
- 许可：GPL-3.0（见 `LICENSE`）
