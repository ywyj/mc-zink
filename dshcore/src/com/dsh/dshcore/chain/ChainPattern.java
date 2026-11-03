package com.dsh.dshcore.chain;

/** One selectable shape entry, mirroring the original mod's PatternConfig fields. */
public final class ChainPattern {
    public String id = "normal";
    public boolean enabled = true;
    public PatternType type = PatternType.NORMAL;
    public int color = 0xFFFFFF;
    public int width = 1;
    public int height = 1;
    public boolean stairsUp = false;

    public ChainPattern() {
    }

    public ChainPattern(String id, PatternType type) {
        this.id = id;
        this.type = type;
    }

    public void sanitize() {
        if (id == null || id.isBlank()) id = "normal";
        if (type == null) type = PatternType.NORMAL;
        width = Math.max(1, Math.min(9, width));
        height = Math.max(1, Math.min(9, height));
    }
}
