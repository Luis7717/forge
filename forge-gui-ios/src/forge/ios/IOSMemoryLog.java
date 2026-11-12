package forge.ios;

import org.robovm.rt.VM;
import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.Library;
import org.tinylog.Logger;

/**
 * Diagnostics: once a minute writes to forge.log where the app's memory goes, to find what fills the 3GB iOS
 * per-process limit (JetsamEvent "per-process-limit"):
 *  - footprint: what iOS counts against the limit (task_info phys_footprint), and how far the limit is
 *    (os_proc_available_memory);
 *  - graphics: memory iOS accounts to the GPU / IOSurfaces (task_info "device");
 *  - java: the Boehm GC heap (capped by robovm.ini) and what is live in it;
 *  - textures: the libGDX managed textures (count and size).
 */
@Library(Library.INTERNAL)
public final class IOSMemoryLog {
    private static final int TASK_VM_INFO = 22;
    private static final int TASK_VM_INFO_COUNT = 38; //natural_t words up to and including phys_footprint
    private static boolean bound;

    static {
        try {
            Bro.bind(IOSMemoryLog.class);
            bound = true;
        } catch (Throwable t) {
            bound = false;
        }
    }

    @Bridge(symbol = "mach_task_self", optional = true)
    private static native int machTaskSelf();

    @Bridge(symbol = "task_info", optional = true)
    private static native int taskInfo(int task, int flavor, long info, long count);

    @Bridge(symbol = "os_proc_available_memory", optional = true)
    private static native long osProcAvailableMemory();

    private IOSMemoryLog() {
    }

    public static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60000);
                    Logger.info("[mem] " + line());
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    Logger.info("[mem] unavailable: " + t2);
                    return;
                }
            }
        }, "ios-mem-log");
        t.setDaemon(true);
        t.start();
    }

    private static long mb(long b) {
        return b >> 20;
    }

    static String line() {
        StringBuilder sb = new StringBuilder();
        if (bound) {
            try {
                long buf = VM.malloc(512);
                long cnt = VM.malloc(8);
                try {
                    VM.setInt(cnt, TASK_VM_INFO_COUNT);
                    if (taskInfo(machTaskSelf(), TASK_VM_INFO, buf, cnt) == 0) {
                        sb.append("footprint=").append(mb(VM.getLong(buf + 144))).append("MB")
                          .append(" graphics=").append(mb(VM.getLong(buf + 32))).append("MB")
                          .append(" internal=").append(mb(VM.getLong(buf + 48))).append("MB")
                          .append(" compressed=").append(mb(VM.getLong(buf + 120))).append("MB ");
                    }
                } finally {
                    VM.free(buf);
                    VM.free(cnt);
                }
            } catch (Throwable t) {
                sb.append("task_info failed (").append(t).append(") ");
            }
            try {
                sb.append("left_before_kill=").append(mb(osProcAvailableMemory())).append("MB ");
            } catch (Throwable t) {
                //older iOS
            }
        }
        Runtime r = Runtime.getRuntime();
        sb.append("java used=").append(mb(r.totalMemory() - r.freeMemory())).append("MB heap=")
          .append(mb(r.totalMemory())).append("MB max=").append(mb(r.maxMemory())).append("MB ");
        try {
            com.badlogic.gdx.utils.Array<?> all = null;
            java.lang.reflect.Field f = com.badlogic.gdx.graphics.Texture.class.getDeclaredField("managedTextures");
            f.setAccessible(true);
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) f.get(null);
            long px = 0;
            int n = 0;
            for (Object v : m.values()) {
                all = (com.badlogic.gdx.utils.Array<?>) v;
                for (Object o : all) {
                    com.badlogic.gdx.graphics.Texture tex = (com.badlogic.gdx.graphics.Texture) o;
                    px += (long) tex.getWidth() * tex.getHeight();
                    n++;
                }
            }
            sb.append("textures=").append(n).append(" (~").append(mb(px * 4)).append("MB)");
        } catch (Throwable t) {
            sb.append("textures=?");
        }
        return sb.toString();
    }
}
