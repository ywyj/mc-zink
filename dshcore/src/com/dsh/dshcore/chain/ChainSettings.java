package com.dsh.dshcore.chain;

/**
 * Global chain-mining settings. Field names are kept 1:1 with the original mod's
 * VeinminerSettings so that config migration / UI parity is trivial.
 */
public final class ChainSettings {
    public boolean enabled = true;
    public int cooldown = 0;
    public boolean mustSneak = false;
    public int delay = 1;
    public int maxChain = 64;
    public boolean needCorrectTool = true;
    public int searchRadius = 6;
    public boolean permissionRestricted = false;
    public boolean mergeItemDrops = false;
    public boolean autoUpdate = true;
    public boolean decreaseDurability = true;
    public double hungerPerBlock = 0.0D;
    public double miningSpeedModifier = 1.0D;
    public boolean debug = false;
    /** Active shape id (our command/UI selection; the original mod has a similar client pattern selection). */
    public String pattern = "normal";

    public void sanitize() {
        cooldown = Math.max(0, Math.min(300, cooldown));
        delay = Math.max(1, Math.min(200, delay));
        maxChain = Math.max(2, Math.min(4096, maxChain));
        searchRadius = Math.max(1, Math.min(32, searchRadius));
        hungerPerBlock = Math.max(0.0D, Math.min(100.0D, hungerPerBlock));
        miningSpeedModifier = Math.max(0.01D, Math.min(100.0D, miningSpeedModifier));
        if (pattern == null || pattern.isBlank()) pattern = "normal";
    }
}
