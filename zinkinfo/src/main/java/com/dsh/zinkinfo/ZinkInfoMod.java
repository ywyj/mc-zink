package com.dsh.zinkinfo;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

@Mod("zinkinfo")
public final class ZinkInfoMod {
    private static final Logger LOG = LoggerFactory.getLogger("ZinkInfo");

    public ZinkInfoMod(IEventBus modBus) {
        String r = KfDiag.test("modctor");
        LOG.info("[ZinkInfo] {}", r);
        try {
            Path dir = FMLPaths.GAMEDIR.get();
            Files.writeString(dir.resolve("zinkinfo-kf.txt"), r + System.lineSeparator());
        } catch (Throwable ignored) {
        }
    }
}