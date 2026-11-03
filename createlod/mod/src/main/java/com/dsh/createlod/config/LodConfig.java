package com.dsh.createlod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * config/createlod.json. Hand-rolled Gson config (no ModConfigSpec) so it can be edited outside the game
 * and stays forward/backward compatible when fields are added or removed.
 */
public final class LodConfig {
    private static final Logger LOG = LoggerFactory.getLogger("CreateLOD");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile LodConfig INSTANCE;

    // --- master switches ---
    public boolean enabled = true;
    public boolean debug = false;
    public boolean logChanges = false;
    /** How often (client ticks) hidden/visible state is re-evaluated. */
    public int rescanTicks = 10;
    /** How often (client ticks) config/createlod-stats.txt is rewritten. */
    public int statsIntervalTicks = 40;
    /** Chunk radius to scan. -1 = effective render distance + 2. */
    public int scanRadiusChunks = -1;

    // --- L1: block sealed by its own opaque casing model (zero visual difference) ---
    public boolean selfCasing = true;
    public List<String> selfCasingBlocks = new ArrayList<>(List.of(
            "create:andesite_encased_shaft",
            "create:brass_encased_shaft"));

    // --- L2: encased cogwheel whose 4 side windows are fully covered (zero visual difference) ---
    // Only the small 1x1x1 encased cogwheels: their casing model is one block and the 4 window strips are exactly
    // the 4 side faces. The LARGE variant is a 1x1x1 casing around a ~1.875-block wheel that sticks out of the
    // block, so it is intentionally not listed here (it remains cullable by L4/L5 only).
    public boolean windowCovered = true;
    public List<String> encasedCogwheelBlocks = new ArrayList<>(List.of(
            "create:andesite_encased_cogwheel",
            "create:brass_encased_cogwheel"));

    // --- L3: distance LOD for encased cogwheels (user-authorized resolution reduction) ---
    public boolean encasedFar = true;
    public double encasedLodDistance = 32.0;
    public double encasedLodHysteresis = 0.85;

    // --- L4: fully buried kinetic part (all opaque neighbours, model AABB included) ---
    public boolean buried = true;
    public List<String> buriedBlocks = new ArrayList<>(List.of(
            "create:shaft", "create:cogwheel", "create:large_cogwheel",
            "create:andesite_encased_shaft", "create:brass_encased_shaft",
            "create:andesite_encased_cogwheel", "create:brass_encased_cogwheel",
            "create:andesite_encased_large_cogwheel", "create:brass_encased_large_cogwheel",
            "create:metal_girder_encased_shaft", "create:encased_chain_drive",
            "create:gearbox", "create:brass_gearbox",
            "create:clutch", "create:gearshift", "create:creative_motor",
            "create:encased_fan", "create:gantry_shaft", "create:turntable",
            "create:mechanical_pump"));

    // --- L5: blanket namespace distance LOD (off by default; benchmark ceiling knob) ---
    public boolean farLod = false;
    public double farLodDistance = 128.0;
    public List<String> farLodNamespaces = new ArrayList<>(List.of("create"));

    // --- compatibility ---
    /** If true, only Flywheel 1.0.x is accepted; anything else disables the feature instead of guessing. */
    public boolean strictFlywheelVersion = true;

    private LodConfig() {
    }

    public static LodConfig get() {
        LodConfig c = INSTANCE;
        return c == null ? load() : c;
    }

    public static synchronized LodConfig load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("createlod.json");
        LodConfig cfg = null;
        try {
            if (Files.isRegularFile(path)) {
                cfg = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), LodConfig.class);
            }
        } catch (Throwable t) {
            LOG.warn("[createlod] failed to read {}: {}", path, t.toString());
        }
        if (cfg == null) {
            cfg = new LodConfig();
        }
        cfg.sanitize();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            LOG.warn("[createlod] failed to write {}: {}", path, t.toString());
        }
        INSTANCE = cfg;
        LOG.info("[createlod] config file {}", path);
        return cfg;
    }

    private void sanitize() {
        if (rescanTicks < 1) rescanTicks = 1;
        if (rescanTicks > 200) rescanTicks = 200;
        if (statsIntervalTicks < 10) statsIntervalTicks = 10;
        if (scanRadiusChunks < -1) scanRadiusChunks = -1;
        if (scanRadiusChunks > 32) scanRadiusChunks = 32;
        if (encasedLodDistance < 0) encasedLodDistance = 0;
        if (farLodDistance < 0) farLodDistance = 0;
        if (encasedLodHysteresis < 0.1 || encasedLodHysteresis > 1.0) encasedLodHysteresis = 0.85;
        selfCasingBlocks = clean(selfCasingBlocks);
        encasedCogwheelBlocks = clean(encasedCogwheelBlocks);
        buriedBlocks = clean(buriedBlocks);
        farLodNamespaces = clean(farLodNamespaces);
    }

    private static List<String> clean(List<String> in) {
        if (in == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
