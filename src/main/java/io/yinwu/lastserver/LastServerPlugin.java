package io.yinwu.lastserver;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记住玩家上次所在的子服，进服时直接送回该子服。
 *
 * <p>回退规则：目标子服不在 [servers] 中、探活失败，或登录阶段仍被目标子服拒绝，
 * 一律改送 config.properties 里的 default-server（默认大厅）。
 *
 * <p>已经站在别的子服上时（例如子服重启那十几秒里玩家点了 /server），
 * 转服失败只提示、不把人踢出代理（stay-on-failed-transfer）。
 */
public final class LastServerPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private ScheduledExecutorService flushExecutor;
    private Path dataFile;

    // ---- 配置项（config.properties）----
    private String defaultServer = "lobby";
    private long pingTimeoutMillis = 1500L;
    private boolean rememberDefaultServer = false;
    private boolean stayOnFailedTransfer = true;
    private String dataFileName = "last-server.properties";

    @Inject
    public LastServerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.warn("[lastserver] 无法创建数据目录 {}：{}", dataDirectory, e.toString());
        }

        loadConfig();
        dataFile = dataDirectory.resolve(dataFileName);
        loadData();

        flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "yinwu-lastserver-flush");
            thread.setDaemon(true);
            return thread;
        });
        flushExecutor.scheduleWithFixedDelay(this::flush, 30L, 30L, TimeUnit.SECONDS);

        logger.info("[lastserver] 已启用：默认大厅={}，探活超时={}ms，记住默认大厅={}，转服失败留在原处={}，已载入 {} 条记录",
                defaultServer, pingTimeoutMillis, rememberDefaultServer, stayOnFailedTransfer, lastServer.size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
        }
        flush();
        logger.info("[lastserver] 已停用，记录已落盘到 {}", dataFile);
    }

    /** 决定玩家这次进服去哪个子服。 */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        String remembered = lastServer.get(player.getUniqueId());
        if (remembered == null) {
            // 没有记录（新玩家，或 remember-default-server=false 下只在大厅待过）：完全交给 Velocity
            return;
        }

        Optional<RegisteredServer> target = proxy.getServer(remembered);
        if (target.isEmpty()) {
            logger.info("[lastserver] {} 上次所在的 '{}' 已不在 [servers] 中，改送默认大厅 '{}'",
                    player.getUsername(), remembered, defaultServer);
            applyDefault(event);
            return;
        }

        if (!isReachable(target.get())) {
            logger.info("[lastserver] {} 上次所在的 '{}' 当前不可达，改送默认大厅 '{}'",
                    player.getUsername(), remembered, defaultServer);
            applyDefault(event);
            return;
        }

        event.setInitialServer(target.get());
        logger.info("[lastserver] {} 送回上次所在的 '{}'", player.getUsername(), remembered);
    }

    /** 记录玩家当前真正连上的子服。 */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isEmpty()) {
            return;
        }
        String name = current.get().getServerInfo().getName();
        if (!rememberDefaultServer && name.equalsIgnoreCase(defaultServer)) {
            // 不把"被回退到大厅"覆盖成玩家上次所在的服
            return;
        }
        String previous = lastServer.put(player.getUniqueId(), name);
        if (!name.equals(previous)) {
            dirty.set(true);
        }
    }

    /**
     * 登录/切服阶段被目标子服拒绝时的兜底。
     *
     * <p>Velocity 的默认行为是把人直接踢出整个代理（日志里的
     * "[connected player] xxx: unable to connect to server van"）。这里分两种情况：
     * 玩家已经站在别的子服上（典型场景：子服重启那十几秒里玩家点了 /server），
     * 就只提示、把人留在原处；玩家还在登录阶段（没有落脚点），才改送默认大厅。
     */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!event.kickedDuringServerConnect()) {
            return; // 会话中途被踢（子服崩了等）交给 Velocity 的 [servers] 回去列表处理
        }

        Player player = event.getPlayer();
        String kickedName = event.getServer().getServerInfo().getName();

        // 情况一：玩家本来就在别的子服上，只是这一次"转服"没成功。
        Optional<ServerConnection> current = player.getCurrentServer();
        if (current.isPresent()) {
            if (!stayOnFailedTransfer) {
                return;
            }
            String stayName = current.get().getServerInfo().getName();
            logger.info("[lastserver] {} 从 '{}' 转到 '{}' 失败，保留在 '{}'（不踢出）",
                    player.getUsername(), stayName, kickedName, stayName);
            event.setResult(KickedFromServerEvent.Notify.create(Component.text()
                    .append(Component.text("⚠ ", NamedTextColor.YELLOW))
                    .append(Component.text("无法连接到 ", NamedTextColor.GRAY))
                    .append(Component.text(kickedName, NamedTextColor.WHITE))
                    .append(Component.text("（可能正在重启），你仍留在 ", NamedTextColor.GRAY))
                    .append(Component.text(stayName, NamedTextColor.WHITE))
                    .append(Component.text("，稍后再试即可。", NamedTextColor.GRAY))
                    .build()));
            return;
        }

        // 情况二：登录阶段就被拒，玩家还没有任何落脚点，只能改送默认大厅。
        if (kickedName.equalsIgnoreCase(defaultServer)) {
            return; // 默认大厅自己都进不去，交回 Velocity，避免来回弹
        }
        Optional<RegisteredServer> fallback = proxy.getServer(defaultServer);
        if (fallback.isEmpty()) {
            logger.warn("[lastserver] 默认大厅 '{}' 不存在，无法回退", defaultServer);
            return;
        }
        logger.info("[lastserver] {} 登录时无法进入 '{}'，改送默认大厅 '{}'",
                player.getUsername(), kickedName, defaultServer);
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(fallback.get(), Component.text()
                .append(Component.text("⚠ ", NamedTextColor.YELLOW))
                .append(Component.text(kickedName + " 当前不可用", NamedTextColor.GRAY))
                .append(Component.text("，已把你送到大厅。", NamedTextColor.GRAY))
                .build()));
    }

    private void applyDefault(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> fallback = proxy.getServer(defaultServer);
        if (fallback.isEmpty()) {
            logger.warn("[lastserver] 默认大厅 '{}' 不存在于 [servers] 中，保持 Velocity 原有选择", defaultServer);
            return;
        }
        event.setInitialServer(fallback.get());
    }

    /** 预检目标子服是否可达；ping-timeout-millis <= 0 时关闭预检。 */
    private boolean isReachable(RegisteredServer server) {
        if (pingTimeoutMillis <= 0L) {
            return true;
        }
        try {
            server.ping().get(pingTimeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    private void loadConfig() {
        Path configPath = dataDirectory.resolve("config.properties");
        if (Files.notExists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.properties")) {
                if (in != null) {
                    Files.copy(in, configPath);
                }
            } catch (IOException e) {
                logger.warn("[lastserver] 写出默认 config.properties 失败：{}", e.toString());
            }
        }

        Properties props = new Properties();
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (IOException e) {
                logger.warn("[lastserver] 读取 config.properties 失败，改用内置默认值：{}", e.toString());
            }
        }

        defaultServer = props.getProperty("default-server", defaultServer).trim();
        dataFileName = props.getProperty("data-file", dataFileName).trim();
        rememberDefaultServer = Boolean.parseBoolean(
                props.getProperty("remember-default-server", String.valueOf(rememberDefaultServer)).trim());
        stayOnFailedTransfer = Boolean.parseBoolean(
                props.getProperty("stay-on-failed-transfer", String.valueOf(stayOnFailedTransfer)).trim());

        String ping = props.getProperty("ping-timeout-millis", String.valueOf(pingTimeoutMillis)).trim();
        try {
            pingTimeoutMillis = Long.parseLong(ping);
        } catch (NumberFormatException e) {
            logger.warn("[lastserver] ping-timeout-millis='{}' 不是数字，沿用 {}ms", ping, pingTimeoutMillis);
        }
    }

    private void loadData() {
        if (Files.notExists(dataFile)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(dataFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("[lastserver] 读取 {} 失败：{}", dataFile, e.toString());
            return;
        }

        for (String key : props.stringPropertyNames()) {
            try {
                UUID uuid = UUID.fromString(key);
                String server = props.getProperty(key, "").trim();
                if (!server.isEmpty()) {
                    lastServer.put(uuid, server);
                }
            } catch (IllegalArgumentException ignored) {
                // 不是 UUID 的键（例如 BungeeCord locations.yml 的 "名字;域名:端口"）直接跳过
            }
        }
    }

    private void flush() {
        if (dataFile == null || !dirty.getAndSet(false)) {
            return;
        }
        Properties props = new Properties();
        for (Map.Entry<UUID, String> entry : lastServer.entrySet()) {
            props.setProperty(entry.getKey().toString(), entry.getValue());
        }
        try (OutputStream out = Files.newOutputStream(dataFile)) {
            props.store(out, "YinwuLastServer: uuid = last server name");
        } catch (IOException e) {
            dirty.set(true);
            logger.warn("[lastserver] 写入 {} 失败：{}", dataFile, e.toString());
        }
    }
}
