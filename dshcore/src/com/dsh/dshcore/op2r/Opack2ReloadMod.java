package com.dsh.dshcore.op2r;

import net.neoforged.fml.common.Mod;

/**
 * DSH Core: clean-room reimplementation of the 0Pack2Reload feature
 * (prevent a redundant resource-pack reload when the player did not change the selection).
 *
 * The original mod is licensed "Nova Relay License 1.0"; this implementation was written
 * from observed public behaviour only (no original source or bytecode was copied).
 */
@Mod("opack2reload")
public final class Opack2ReloadMod {
    public static final String MOD_ID = "opack2reload";

    public Opack2ReloadMod() {
        // No setup needed: the work is done by OptionsMixin.
    }
}
