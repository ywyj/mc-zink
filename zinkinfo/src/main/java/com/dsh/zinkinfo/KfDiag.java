package com.dsh.zinkinfo;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/** Probes SHGetKnownFolderPath(FOLDERID_ProgramData) exactly like NGX core does. */
public final class KfDiag {
    private static final byte[] GUID_PROGRAMDATA = new byte[]{
            (byte) 0x82, 0x5d, (byte) 0xab, 0x62, (byte) 0xc1, (byte) 0xfd, (byte) 0xc3, 0x4d,
            (byte) 0xa9, (byte) 0xdd, 0x07, 0x0d, 0x1d, 0x49, 0x5d, (byte) 0x97};

    public static String test(String tag) {
        StringBuilder sb = new StringBuilder(tag);
        try {
            NativeLibrary sh = NativeLibrary.getInstance("shell32");
            Function f = sh.getFunction("SHGetKnownFolderPath");
            Memory guid = new Memory(16);
            guid.write(0, GUID_PROGRAMDATA, 0, 16);
            PointerByReference out = new PointerByReference();
            int hr = f.invokeInt(new Object[]{guid, 0, Pointer.NULL, out});
            Pointer p = out.getValue();
            sb.append(String.format(" SHGetKnownFolderPath(ProgramData) hr=0x%08X path=%s", hr, p == null ? "null" : p.getWideString(0)));
            Function gfp = sh.getFunction("SHGetFolderPathW");
            Memory buf = new Memory(520);
            int hr2 = gfp.invokeInt(new Object[]{Pointer.NULL, 0x23, Pointer.NULL, 0, buf});
            sb.append(String.format(" | SHGetFolderPathW(COMMON_APPDATA) hr=0x%08X path=%s", hr2, buf.getWideString(0)));
            sb.append(" | thread=").append(Thread.currentThread().getName());
            sb.append(" | ProgramData_env=").append(System.getenv("ProgramData"));
        } catch (Throwable t) {
            sb.append(" EXCEPTION ").append(t);
        }
        return sb.toString();
    }
}