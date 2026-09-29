# YinwuLastServer

Velocity 代理插件：**记住玩家上次所在的子服，进服时直接送回；目标子服进不去就回退到默认大厅。**

零第三方依赖：只用 Velocity 代理 API。运行时不解析任何游戏数据包，因此与玩家客户端版本（26.1 / 26.2 / 26.3 …）无关。

## 当前状态（2026-09-27）

| 项 | 值 |
|---|---|
| 线上版本 | `1.0.0`（`velocity\plugins\velocity-lastserver-1.0.0.jar`，正在运行） |
| 新版本 | `1.1.0` 已构建，见 `velocity-lastserver-1.1.0.jar` |
| 1.1.0 变更 | 修掉"子服重启那十几秒里 /server 过去 → **被踢出整个代理**"的漏洞（见第 10 节） |
| 安装 1.1.0 | ⏳ 需要先停代理（运行中的代理锁住旧 jar），换 jar 后重启 |
| 1.1.0 SHA-256 | `69009C9DAB82797C6BC85F3BA4AE3AE4D6C3CD1BDE30D2B7BB94EE0B85FE0255`（8,572 字节） |
| 1.0.0 SHA-256 | `589C6F3B1F8D78C19F20829E3F51B995070E031D90BAD221FFC4D16297119972`（7,462 字节） |
| 加载测试 | ✅ 1.0.0 已在隔离实例实测通过；1.1.0 编译+字节码核对通过（用到 `KickedFromServerEvent.Notify`） |

---

## 1. 行为规则（决策表）

| 情况 | 行为 |
|---|---|
| 玩家没有记录（首次进服，或只在默认大厅待过） | **完全不干预**，走 Velocity 自己的 `try` 顺序 / `[forced-hosts]` |
| 有记录，目标服存在且探活通过 | 直接送该服 |
| 有记录，但目标服已从 `[servers]` 移除 | 改送 `default-server` |
| 有记录，但探活失败（超时 / 连接被拒 / 无法握手） | 改送 `default-server` |
| 已经送过去了，却在登录阶段被拒（白名单、满员、子服插件踢） | 兜底拦截 → 改送 `default-server` |
| 连 `default-server` 都连不上 | 不插手，交回 Velocity（避免两个服之间来回弹） |
| **玩家已在某个子服上，转服目标却连不上**（子服重启中点了 `/server van`） | **只提示、留在原处**，不踢出代理（1.1.0 新增，`stay-on-failed-transfer`） |
| 玩家已经在某个子服上、会话中途被踢 | 不插手，保留 Velocity 原生的 `failover-on-unexpected-server-disconnect` 行为 |

"没有记录就不干预"这条很重要：域名分流（`[forced-hosts]`）、首次进服策略都不会被本插件破坏。

## 2. 文件清单

```
velocity-lastserver/
├─ build-javac.bat                  # 一键构建（需要 JDK 25 + 代理 jar，无需 Maven/联网）
├─ pom.xml                          # 可选：Maven 构建（Maven 也必须跑在 JDK 25 上）
├─ README.md
└─ src/main/
   ├─ java/io/yinwu/lastserver/LastServerPlugin.java
   └─ resources/
      ├─ velocity-plugin.json       # 插件描述符（id/name/version/main）
      └─ config.properties          # 默认配置（首次启动释放到插件数据目录）
```

## 3. 构建（⚠️ 必须用 JDK 25）

**关键坑**：`velocity-4.2.0-30.jar` 里的类都是 **class file 69（Java 25）**，JDK 17 / 21 的 javac **连读都读不了它**，会报：

- `invalid flag: -release`（拿旧 javac 当 17 用时）
- `无法访问 com.velocitypowered.api.event.Subscribe` / `class file has wrong version 69.0, should be 61.0`

所以构建必须用 **JDK 25+**；脚本内部用 `--release 17` 让产物仍是 Java 17 字节码（更保守，虽然代理跑在 Java 25 上）。我实测过：javac 25 通过，javac 17 和 javac 21 都失败。

**方式 A：一键脚本（推荐）**

双击 `build-javac.bat`。它会自动探测代理 jar 和 JDK（顺序：`JAVA_HOME` → `Eclipse Adoptium\jdk-25*` → `Program Files\Java\jdk-25` → PATH），产物为 `velocity-lastserver-1.1.0.jar`。

**方式 B：手工命令**（不想用 bat 时，在 cmd 里逐行粘贴）

```bat
cd /d <本项目目录>
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 --release 17 -proc:none -cp "<代理根>\velocity-4.2.0-30.jar" -d out src\main\java\io\yinwu\lastserver\LastServerPlugin.java
jar --create --file velocity-lastserver-1.1.0.jar -C out . -C src\main\resources .
```
（这里的 `javac` 必须是 JDK 25 的；`javac -version` 先确认。）

**方式 C：Maven**（Maven 自身也要跑在 JDK 25 上）

```bat
cd velocity-lastserver
mvn -q package
```
`pom.xml` 的 `<velocity.version>` 默认 `3.4.0`；想对线上 jar 精确编译就先 `mvn install:install-file -Dfile="..\velocity\velocity-4.2.0-30.jar" -DgroupId=com.velocitypowered -DartifactId=velocity-api -Dversion=4.2.0-local -Dpackaging=jar`，再把版本改成 `4.2.0-local`。

## 4. 安装与生效

1. jar 已在 `velocity\plugins\`（要重新复制就再拷一次）
2. **先确认 authbridge（MirrorBridge）在运行** —— 它是独立进程，重启代理前必须已在跑
3. 重启代理：`velocity\start.bat`（跑 `velocity-4.2.0-30.jar`）
4. 首次启动会在 `velocity\plugins\yinwu-lastserver\` 生成 `config.properties` 与 `last-server.properties`

## 5. 配置（`velocity\plugins\yinwu-lastserver\config.properties`）

| 键 | 默认 | 说明 |
|---|---|---|
| `default-server` | `lobby` | 默认大厅，必须与 `velocity.toml` 的 `[servers]` 键名一致 |
| `ping-timeout-millis` | `1500` | 送回前对目标服的探活超时；`<=0` 关闭预检，只靠登录阶段的兜底 |
| `remember-default-server` | `false` | 是否把"默认大厅"也记成上次所在服。`false` 时：玩家在大厅退出、或子服崩了被弹回大厅后再退出，记录仍留在原子服 |
| `stay-on-failed-transfer` | `true` | 玩家已在某子服上、只是这次转服失败时：`true`＝提示并留在原处；`false`＝Velocity 默认行为（踢出代理）。1.1.0 新增 |
| `data-file` | `last-server.properties` | 记录文件名（存于插件数据目录） |

改完配置需重启代理（本插件无 reload 命令）。

## 6. 验收（4 个场景）

**日志关键字**（`velocity\logs\latest.log`）：

- 启动成功：`[lastserver] 已启用：默认大厅=lobby，探活超时=1500ms，记住默认大厅=false，转服失败留在原处=true，已载入 N 条记录`
- 正常送回：`[lastserver] <玩家> 送回上次所在的 'sur'`
- 探活失败：`[lastserver] <玩家> 上次所在的 'sur' 当前不可达，改送默认大厅 'lobby'`
- 配置里没有：`[lastserver] <玩家> 上次所在的 'sur' 已不在 [servers] 中，改送默认大厅 'lobby'`
- 登录被拒：`[lastserver] <玩家> 登录时无法进入 'sur'，改送默认大厅 'lobby'`
- 转服失败但不踢人（1.1.0）：`[lastserver] <玩家> 从 'lobby' 转到 'van' 失败，保留在 'lobby'（不踢出）`

**场景**：

1. 进服 → `/server sur` → 退出 → 重进 ⇒ 落在 `sur`
2. 停掉 `sur`（**别**从 `[servers]` 删）→ 重进 ⇒ 进 `lobby`，日志出现"当前不可达"
3. 从 `[servers]` 删掉 `sur` 并重启代理 → 重进 ⇒ 进 `lobby`，日志出现"已不在 [servers] 中"
4. 场景 2 里被弹到 lobby 后再退出 → 重进 ⇒ **仍回 `sur`**（`remember-default-server=false` 的用途）

**记录文件**：`velocity\plugins\yinwu-lastserver\last-server.properties`，`UUID=子服名`，每 30 秒或有变更时落盘，代理关闭时也会写一次。

## 7. 已做 / 未做的验证

- ✅ 编译：javac 25 + `--release 17 -proc:none`，对 `velocity-4.2.0-30.jar` 编译通过，产物 class 主版本 61（Java 17）
- ✅ 加载：隔离实例（`127.0.0.1:25599`、`online-mode=false`、只有本插件）实测 `Loaded plugin yinwu-lastserver 1.0.0 by Yinwu`，`Done (0.74s)!`，无异常，配置正常释放
- ❌ 未做：真实玩家进服/退出/回退的端到端测试（需要真人客户端，且会打扰线上）—— 按第 6 节四个场景验收即可

## 8. 已知边界

- 探活用 `RegisteredServer#ping()`，有记录时会在该玩家的登录处理线程上最多阻塞 `ping-timeout-millis` 毫秒（无记录的玩家不受影响）。不想有任何阻塞就设 `0`。
- 玩家"主动"跑去大厅再退出时，若 `remember-default-server=false`，记录不会改成 lobby，下次仍回原服；想要字面意义的"上次所在服"就设 `true`。
- 只负责"选首跳"，不做跨服平衡，也不做"子服重启后把玩家自动塞回去"的重连队列。

## 9. 回滚

删除 `velocity\plugins\velocity-lastserver-1.*.jar`（`velocity\plugins\yinwu-lastserver\` 数据目录可留可删）→ 重启代理。行为立刻恢复成"所有人按 `try` 顺序进 lobby"。

## 10. 1.1.0：修掉"子服重启中过去会被踢出代理"

**现场**（2026-09-27 09:51，`velocity\logs\latest.log`）：

```
[09:51:20] C_Lin / qumingjam  van has disconnected → lobby has connected     ← Van 重启，玩家被疏散回大厅
[09:51:25] [connected player] qumingjam: unable to connect to server van
           io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection refused: /127.0.0.1:20003
[09:52:02] qumingjam -> van has connected                                    ← 14 秒后重进就正常了
```

Van 的 JVM 是 `09:51:25` 创建的、`09:51:39` 才 `Done`：玩家在端口还没绑定的那 14 秒里从 lobby 发起了 `/server van`。

**为什么 1.0.0 没兜住**：1.0.0 的踢人兜底里有一句 `if (player.getCurrentServer().isPresent()) return;`（"玩家已经在别的子服上就不插手"），而这次玩家正站在 lobby 上 —— 于是交回 Velocity，而 **Velocity 的默认行为是直接把人踢出整个代理**（不是留在 lobby）。

**1.1.0 怎么改**：分两种情况（源码 `onKickedFromServer`）：

| 情况 | 结果类型 | 效果 |
|---|---|---|
| 玩家已有落脚子服（转服失败） | `KickedFromServerEvent.Notify.create(Component)` | Velocity 发一条提示后**什么都不做**，玩家留在原服 |
| 玩家在登录阶段（没有落脚点） | `KickedFromServerEvent.RedirectPlayer.create(server, Component)` | 送到默认大厅，并附一句说明 |

`Notify` 的语义是从线上 jar 反编译确认的（`ConnectedPlayer.lambda$handleKickEvent$0`）：

```java
if (result instanceof KickedFromServerEvent.Notify) {
    if (event.kickedDuringServerConnect()) sendMessage(notify.getMessageComponent());  // 只提示，不断开
    else                                    disconnect(notify.getMessageComponent());  // 其它情况才断开
}
```

所以本插件只在 `kickedDuringServerConnect() == true`（正是"转服/登录被拒"）时使用 `Notify`；会话中途被子服踢（子服崩溃等）仍交回 Velocity 的 `try` 列表处理，不会和原生行为打架。

**换装步骤**（旧 jar 被运行中的代理锁着，必须先停代理）：

```bat
:: 1) 关掉代理窗口（在线玩家会被断开）
:: 2) 换 jar
cd /d <代理根目录>\plugins
move velocity-lastserver-1.0.0.jar _disabled-20260927\
copy <本项目目录>\velocity-lastserver-1.1.0.jar .
:: 3) 重新启动代理
cd /d <代理根目录>
start.bat
```

**验收**：起服后看 `latest.log` 的 `[lastserver] 已启用：…转服失败留在原处=true…`；然后在某个子服重启的那十几秒里用 `/server <该子服>`，应看到聊天栏提示"⚠ 无法连接到 xxx（可能正在重启），你仍留在 yyy"，并且**不掉线**。
