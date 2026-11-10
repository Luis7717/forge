package forge.ai.simulation;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Diagnostic only (off unless -Dforge.mcprobe.every=N is set): measures how much heap one Monte Carlo rollout
 * keeps alive, first right after the game copy and then after the copy has been played forward, with a class
 * histogram diff of what fills it. Used to find out why a rollout costs hundreds of MB on Android.
 */
final class McMemProbe {
    private static final int EVERY = Integer.getInteger("forge.mcprobe.every", 0);
    private static final int MAX = Integer.getInteger("forge.mcprobe.max", 20);
    private static final int TOP = 30;
    private static int calls, reports;

    private Map<String, long[]> base;
    private long baseUsed;
    private final int id;

    private McMemProbe(int id) {
        this.id = id;
    }

    /** A probe for this rollout, or null (almost always). */
    static synchronized McMemProbe start(int turn) {
        if (EVERY <= 0 || reports >= MAX || ++calls % EVERY != 0)
            return null;
        McMemProbe p = new McMemProbe(++reports);
        p.base = histogram();
        p.baseUsed = used();
        System.out.println("[mcprobe " + p.id + "] turn=" + turn + " rollout#" + calls + " baseline used=" + mb(p.baseUsed) + "MB");
        return p;
    }

    private Object held;

    /** Measures with the given objects (the copy) kept alive. */
    void after(String stage, Object keepAlive) {
        held = keepAlive;
        Map<String, long[]> now = histogram();
        long u = used();
        System.out.println("[mcprobe " + id + "] " + stage + ": used=" + mb(u) + "MB (+" + mb(u - baseUsed) + "MB over baseline)");
        List<Object[]> rows = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> e : now.entrySet()) {
            long[] b = base.get(e.getKey());
            long dBytes = e.getValue()[1] - (b == null ? 0 : b[1]);
            long dCount = e.getValue()[0] - (b == null ? 0 : b[0]);
            if (dBytes > 0) {
                rows.add(new Object[]{e.getKey(), dCount, dBytes});
                total += dBytes;
            }
        }
        rows.sort((a, c) -> Long.compare((Long) c[2], (Long) a[2]));
        System.out.println("[mcprobe " + id + "] " + stage + " histogram growth total=" + mb(total) + "MB, top classes:");
        for (int i = 0; i < Math.min(TOP, rows.size()); i++) {
            Object[] r = rows.get(i);
            System.out.println(String.format("[mcprobe %d]   %8.2fMB %9d  %s", id, (Long) r[2] / 1048576.0, (Long) r[1], r[0]));
        }
        System.out.println("[mcprobe " + id + "] (held " + (held == null ? "-" : held.getClass().getSimpleName()) + ")");
    }

    void end() {
        held = null;
    }

    private static long used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static long mb(long b) {
        return b >> 20;
    }

    /** Live-object histogram (forces a full GC), class name -> {instances, bytes}. */
    private static Map<String, long[]> histogram() {
        Map<String, long[]> out = new HashMap<>();
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            String text = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram", new Object[]{null}, new String[]{String[].class.getName()});
            for (String line : text.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && f[0].endsWith(":")) {
                    try {
                        out.put(f[3], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[mcprobe] histogram unavailable: " + t);
        }
        return out;
    }
}
