package net.hytaledepot.templates.mod.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PersistenceModTemplate {
  private final Map<String, AtomicLong> actionCounters = new ConcurrentHashMap<>();
  private final Map<String, String> lastActionBySender = new ConcurrentHashMap<>();
  private final AtomicBoolean demoFlagEnabled = new AtomicBoolean(false);
  private final AtomicLong errorCount = new AtomicLong();
  Properties snapshots = new Properties();
  AtomicLong snapshotCount = new AtomicLong();
  private volatile Path dataDirectory;
  private volatile Path snapshotFile;

  public void onInitialize(Path dataDirectory) {
    this.dataDirectory = dataDirectory;
    this.snapshotFile = dataDirectory.resolve("persistence-template.properties");
    loadSnapshots();
  }

  public void onShutdown() {
    saveSnapshots();
  }

  public void onHeartbeat(long tick) {
    actionCounters.computeIfAbsent("heartbeat", key -> new AtomicLong()).incrementAndGet();

  }

  public String runAction(String sender, String action, long heartbeatTicks) {
    String normalizedSender = String.valueOf(sender == null ? "unknown" : sender);
    String normalizedAction = normalizeAction(action);

    actionCounters.computeIfAbsent(normalizedAction, key -> new AtomicLong()).incrementAndGet();
    lastActionBySender.put(normalizedSender, normalizedAction);

    if ("toggle".equals(normalizedAction)) {
      boolean enabled = toggleFlag(demoFlagEnabled);
      return "[PersistenceMod] demoFlag=" + enabled + ", heartbeatTicks=" + heartbeatTicks;
    }

    if ("info".equals(normalizedAction)) {
      return "[PersistenceMod] " + diagnostics(normalizedSender, heartbeatTicks);
    }

    String domainResult = handleDomainAction(normalizedSender, normalizedAction, heartbeatTicks);
    if (domainResult != null) {
      return "[PersistenceMod] " + domainResult;
    }

    return "[PersistenceMod] unknown action='" + normalizedAction + "' (try: info, toggle, sample, snapshot-demo, restore-demo, purge-snapshots)";
  }

  public String diagnostics(String sender, long heartbeatTicks) {
    String directory = dataDirectory == null ? "unset" : dataDirectory.toString();
    return "sender=" + sender
        + ", heartbeatTicks=" + heartbeatTicks
        + ", demoFlag=" + demoFlagEnabled.get()
        + ", ops=" + operationCount()
        + ", lastAction=" + lastActionBySender.getOrDefault(sender, "none")
        + ", errors=" + errorCount.get()
        + ", snapshots=" + snapshotCount.get() + ", snapshotFile=" + (snapshotFile == null ? "unset" : snapshotFile.getFileName()) + ", dataDirectory=" + directory;
  }

  public long operationCount() {
    long total = 0;
    for (AtomicLong value : actionCounters.values()) {
      total += value.get();
    }
    return total;
  }

  public void incrementErrorCount() {
    errorCount.incrementAndGet();
  }

  private String handleDomainAction(String sender, String action, long heartbeatTicks) {
    if ("sample".equals(action) || "snapshot-demo".equals(action)) {
      snapshots.setProperty("last.owner", sender);
      snapshots.setProperty("last.tick", String.valueOf(heartbeatTicks));
      snapshotCount.incrementAndGet();
      saveSnapshots();
      return "snapshot saved, count=" + snapshotCount.get();
    }
    if ("restore-demo".equals(action)) {
      loadSnapshots();
      return "restored snapshot owner=" + snapshots.getProperty("last.owner", "none") + ", tick=" + snapshots.getProperty("last.tick", "0");
    }
    if ("purge-snapshots".equals(action)) {
      snapshots.clear();
      snapshotCount.set(0);
      saveSnapshots();
      return "snapshot entries purged";
    }
    return null;
  }

  private void loadSnapshots() {
    snapshots.clear();
    snapshotCount.set(0);
    if (snapshotFile == null || !Files.exists(snapshotFile)) {
      return;
    }
    try (InputStream in = Files.newInputStream(snapshotFile)) {
      snapshots.load(in);
      if (snapshots.containsKey("last.owner")) {
        snapshotCount.set(1);
      }
    } catch (IOException ignored) {
    }
  }

  private void saveSnapshots() {
    if (snapshotFile == null) {
      return;
    }
    try {
      Files.createDirectories(snapshotFile.getParent());
      try (OutputStream out = Files.newOutputStream(snapshotFile)) {
        snapshots.store(out, "Persistence template state");
      }
    } catch (IOException ignored) {
    }
  }

  private static String normalizeAction(String action) {
    String normalized = String.valueOf(action == null ? "" : action).trim().toLowerCase();
    return normalized.isEmpty() ? "sample" : normalized;
  }

  private static boolean toggleFlag(AtomicBoolean flag) {
    while (true) {
      boolean current = flag.get();
      boolean next = !current;
      if (flag.compareAndSet(current, next)) {
        return next;
      }
    }
  }
}
