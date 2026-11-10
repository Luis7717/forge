package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Diagnostic only (off unless -Dforge.mcprobe.every=N is set): measures how much heap one Monte Carlo rollout
 * keeps alive, first right after the game copy and then after the copy has been played forward, with a class
 * histogram diff of what fills it. Used to find out why a rollout costs hundreds of MB on Android.
 */
public final class McMemProbe {
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

    /**
     * -Dforge.memwatch=0.7: a daemon thread that, whenever the heap is fuller than that fraction of -Xmx, prints a
     * live-object histogram (after a full GC) and the stack of every thread, at most forge.memwatch.max times.
     */
    public static void startWatchdog() {
        final double frac;
        try {
            frac = Double.parseDouble(System.getProperty("forge.memwatch", "0"));
        } catch (NumberFormatException e) {
            return;
        }
        if (frac <= 0)
            return;
        final int max = Integer.getInteger("forge.memwatch.max", 6);
        Thread t = new Thread(() -> {
            int dumps = 0;
            long maxHeap = Runtime.getRuntime().maxMemory();
            long peak = 0;
            while (dumps < max) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                long u = used();
                if (u > peak + (16 << 20)) {
                    peak = u;
                    System.out.println("[memwatch] new peak (incl. garbage) " + mb(u) + "MB of " + mb(maxHeap) + "MB");
                }
                if (u < maxHeap * frac)
                    continue;
                Map<String, long[]> h = histogram(); //full GC first: what is left is live
                long live = used();
                if (live < maxHeap * frac * 0.8) {
                    continue; //it was only garbage
                }
                dumps++;
                System.out.println("[memwatch " + dumps + "] LIVE heap " + mb(live) + "MB of " + mb(maxHeap) + "MB, top classes:");
                List<Map.Entry<String, long[]>> rows = new ArrayList<>(h.entrySet());
                rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
                for (int i = 0; i < Math.min(40, rows.size()); i++)
                    System.out.println(String.format("[memwatch %d]   %8.2fMB %9d  %s", dumps, rows.get(i).getValue()[1] / 1048576.0,
                            rows.get(i).getValue()[0], rows.get(i).getKey()));
                for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    StackTraceElement[] st = e.getValue();
                    if (st.length < 8)
                        continue;
                    System.out.println("[memwatch " + dumps + "] thread " + e.getKey().getName() + " (" + st.length + " frames)");
                    for (int i = 0; i < Math.min(45, st.length); i++)
                        System.out.println("[memwatch " + dumps + "]     at " + st[i]);
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }, "memwatch");
        t.setDaemon(true);
        t.start();
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
            //by reflection: javax.management does not exist on every runtime (MobiVM on iOS), the probe is desktop-only
            Object server = Class.forName("java.lang.management.ManagementFactory").getMethod("getPlatformMBeanServer").invoke(null);
            Object name = Class.forName("javax.management.ObjectName").getConstructor(String.class)
                    .newInstance("com.sun.management:type=DiagnosticCommand");
            String text = (String) Class.forName("javax.management.MBeanServer")
                    .getMethod("invoke", Class.forName("javax.management.ObjectName"), String.class, Object[].class, String[].class)
                    .invoke(server, name, "gcClassHistogram", new Object[]{null}, new String[]{String[].class.getName()});
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
