package net.hytaledepot.templates.mod.persistence;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class PersistenceModPlugin extends JavaPlugin {
  private enum Lifecycle {
    NEW,
    SETTING_UP,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
  }

  private final PersistenceModTemplate service = new PersistenceModTemplate();
  private final AtomicLong heartbeatTicks = new AtomicLong();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "hd-persistence-mod-worker");
            thread.setDaemon(true);
            return thread;
          });

  private volatile Lifecycle lifecycle = Lifecycle.NEW;
  private volatile ScheduledFuture<?> heartbeatTask;
  private volatile long startedAtEpochMillis;

  public PersistenceModPlugin(JavaPluginInit init) {
    super(init);
  }

  @Override
  protected void setup() {
    lifecycle = Lifecycle.SETTING_UP;

    service.onInitialize(getDataDirectory());

    getCommandRegistry().registerCommand(new PersistenceModStatusCommand());
    getCommandRegistry().registerCommand(new PersistenceModDemoCommand());

    lifecycle = Lifecycle.RUNNING;
  }

  @Override
  protected void start() {
    startedAtEpochMillis = System.currentTimeMillis();

    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                long tick = heartbeatTicks.incrementAndGet();
                service.onHeartbeat(tick);
                if (tick % 60 == 0) {
                  getLogger().atInfo().log("[PersistenceMod] heartbeat=%d", tick);
                }
              } catch (Exception exception) {
                lifecycle = Lifecycle.FAILED;
                service.incrementErrorCount();
                getLogger().atInfo().log("[PersistenceMod] heartbeat failed: %s", exception.getMessage());
              }
            },
            1,
            1,
            TimeUnit.SECONDS);

    getTaskRegistry().registerTask(CompletableFuture.completedFuture(null));
  }

  @Override
  protected void shutdown() {
    lifecycle = Lifecycle.STOPPING;

    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }

    scheduler.shutdownNow();
    service.onShutdown();
    lifecycle = Lifecycle.STOPPED;
  }

  private long uptimeSeconds() {
    if (startedAtEpochMillis <= 0L) {
      return 0L;
    }
    return Math.max(0L, (System.currentTimeMillis() - startedAtEpochMillis) / 1000L);
  }

  private final class PersistenceModStatusCommand extends CommandBase {
    private PersistenceModStatusCommand() {
      super("hdpersistencemodstatus", "Shows runtime status for PersistenceModPlugin.");
    setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(CommandContext ctx) {
      String sender = String.valueOf(ctx.sender().getDisplayName());
      String line =
          "[PersistenceMod] lifecycle="
              + lifecycle
              + ", uptime="
              + uptimeSeconds()
              + "s"
              + ", heartbeatTicks="
              + heartbeatTicks.get()
              + ", heartbeatActive="
              + (heartbeatTask != null && !heartbeatTask.isCancelled() && !heartbeatTask.isDone())
              + ", "
              + service.diagnostics(sender, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(line));
    }
  }

  private final class PersistenceModDemoCommand extends CommandBase {
    private PersistenceModDemoCommand() {
      super("hdpersistencemoddemo", "Runs a demo action for PersistenceModPlugin.");
    setAllowsExtraArguments(true);
      this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(CommandContext ctx) {
      String action = parseAction(ctx.getInputString(), "sample");
      String sender = String.valueOf(ctx.sender().getDisplayName());

      String response = service.runAction(sender, action, heartbeatTicks.get());
      ctx.sendMessage(Message.raw(response));
    }
  }

  private static String parseAction(String input, String fallback) {
    String normalized = String.valueOf(input == null ? "" : input).trim();
    if (normalized.isEmpty()) {
      return fallback;
    }

    String[] parts = normalized.split("\\s+");
    String first = parts[0].toLowerCase();
    if (first.startsWith("/")) {
      first = first.substring(1);
    }

    if (parts.length > 1 && first.startsWith("hd")) {
      return parts[1].toLowerCase();
    }
    return first;
  }
}
