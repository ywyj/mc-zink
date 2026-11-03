package com.dsh.dshcore.chain;

import java.util.ArrayList;
import java.util.List;

/** Block/tool group, mirroring the original mod's BlockGroup (name, blocks, tools). */
public final class BlockGroup {
    public String name = "Ores";
    public boolean enabled = true;
    public List<String> blocks = new ArrayList<>();
    public List<String> tools = new ArrayList<>();

    public BlockGroup() {
    }

    public BlockGroup(String name, List<String> blocks, List<String> tools) {
        this.name = name;
        this.blocks = blocks;
        this.tools = tools;
    }
}
