package com.dsh.createlod;

import com.dsh.createlod.config.LodConfig;
import com.dsh.createlod.policy.PolicyEngine;
import com.dsh.createlod.scan.ScanController;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CreateLOD (DSH) - client-only render LOD for Create 6.x kinetic blocks rendered by Flywheel 1.0.x.
 *
 * <p>Design invariants:
 * <ul>
 *   <li>Never touches kinetic logic (tick / rotation / networks). Only visual creation is skipped.</li>
 *   <li>Every hook is guarded: any Throwable permanently disables the feature for the session.</li>
 *   <li>Deleting the jar is a complete rollback (no world / save data is written).</li>
 * </ul>
 */
@Mod(value = CreateLod.MOD_ID, dist = Dist.CLIENT)
public final class CreateLod {
    public static final String MOD_ID = "createlod";
    public static final Logger LOG = LoggerFactory.getLogger("CreateLOD");

    public CreateLod(IEventBus modEventBus) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        LodConfig cfg = LodConfig.load();
        LOG.info("[createlod] loaded v1.0.0 (client-only)");
        LOG.info("[createlod] effective config: enabled={} rescanTicks={} scanRadiusChunks={} selfCasing={} windowCovered={} encasedFar={} encasedLodDistance={} buried={} farLod={} farLodDistance={} debug={}",
                cfg.enabled, cfg.rescanTicks, cfg.scanRadiusChunks, cfg.selfCasing, cfg.windowCovered,
                cfg.encasedFar, cfg.encasedLodDistance, cfg.buried, cfg.farLod, cfg.farLodDistance, cfg.debug);
        LOG.info("[createlod] rule sets: selfCasing={} encasedCogwheels={} buried={} farLodNamespaces={}",
                cfg.selfCasingBlocks, cfg.encasedCogwheelBlocks, cfg.buriedBlocks, cfg.farLodNamespaces);
        if (!ModList.get().isLoaded("flywheel")) {
            LOG.warn("[createlod] flywheel is not loaded -> mod stays inert");
            PolicyEngine.disable("flywheel not loaded");
            return;
        }
        PolicyEngine.checkFlywheelVersion();
        if (PolicyEngine.isDisabled()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ScanController::onClientTick);
        LOG.info("[createlod] scanner registered (ClientTickEvent.Post)");
    }
}
