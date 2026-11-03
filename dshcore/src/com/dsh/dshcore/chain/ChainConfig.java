package com.dsh.dshcore.chain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persistent config: <gameDir>/config/dshcore-chain.json. */
public final class ChainConfig {
    private static final Logger LOG = LoggerFactory.getLogger("dshcore/chain");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int configVersion = 1;
    public ChainSettings settings = new ChainSettings();
    public List<ChainPattern> patterns = new ArrayList<>();
    public List<BlockGroup> groups = new ArrayList<>();

    public ChainConfig() {
    }

    public static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("dshcore-chain.json");
    }

    public static ChainConfig load() {
        ChainConfig cfg = new ChainConfig();
        Path p = path();
        if (Files.isRegularFile(p)) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                ChainConfig parsed = GSON.fromJson(text, ChainConfig.class);
                if (parsed != null) cfg = parsed;
            } catch (Exception e) {
                LOG.warn("[dshcore] failed to read {}, using defaults: {}", p, e.toString());
            }
        }
        cfg.sanitizeAndFillDefaults();
        return cfg;
    }

    public void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[dshcore] failed to save chain config: {}", e.toString());
        }
    }

    public void sanitizeAndFillDefaults() {
        if (settings == null) settings = new ChainSettings();
        settings.sanitize();
        if (patterns == null) patterns = new ArrayList<>();
        if (patterns.isEmpty()) {
            patterns.add(new ChainPattern("normal", PatternType.NORMAL));
            patterns.add(disabled("flat", PatternType.FLAT));
            patterns.add(disabled("tunnel_1x1", PatternType.TUNNEL));
            patterns.add(disabled("tunnel_2x2", PatternType.TUNNEL));
            patterns.add(disabled("tunnel_3x3", PatternType.TUNNEL));
            patterns.add(disabled("stairs", PatternType.STAIRS));
        }
        for (ChainPattern pattern : patterns) pattern.sanitize();
        if (groups == null) groups = new ArrayList<>();
        if (groups.isEmpty()) {
            List<String> oreBlocks = new ArrayList<>();
            oreBlocks.add("#c:ores");
            List<String> pickaxes = new ArrayList<>();
            pickaxes.add("#minecraft:pickaxes");
            groups.add(new BlockGroup("Ores", oreBlocks, pickaxes));
        }
    }

    private static ChainPattern disabled(String id, PatternType type) {
        ChainPattern p = new ChainPattern(id, type);
        p.enabled = false;
        return p;
    }
}
