package forge.screens.online;

import forge.gui.FThreads;
import forge.toolbox.FOptionPane;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Cronache del Velo: two players on the same Wi-Fi find each other with a shared password, then one hosts
 * Forge's online lobby and the other joins it, with no address to type.
 *
 * Every device listens on a small TCP port and also knocks on every address of its own /24 network.
 * Whoever answers with the same password hash is the opponent; the side with the larger random number hosts.
 * (Plain TCP rather than UDP broadcast, which iOS only allows to apps with a special entitlement.)
 */
public final class CronacheLink {
    private static final int PORT = 36788;
    private static final int SEARCH_SECONDS = 120;
    private static final String MAGIC = "CRONACHE1";

    private static volatile boolean running = false;
    private static ServerSocket server;
    private static FOptionPane waitPane;

    private CronacheLink() {
    }

    /** Entry point, called in the classic menus right after leaving the adventure. */
    public static void start() {
        FOptionPane.showInputDialog(
                "Scegli una parola d'ordine e dilla al tuo amico: lui deve scrivere la stessa. Dovete essere sulla stessa rete Wi-Fi.",
                "Sfida un altro campanaro", "", null, (String pwd) -> {
                    if (pwd == null || pwd.trim().isEmpty())
                        return;
                    search(pwd.trim().toLowerCase());
                }, false);
    }

    private static void search(String password) {
        cancel();
        running = true;
        final String token = token(password);
        final long myNonce = new Random(System.nanoTime() ^ Double.doubleToLongBits(Math.random())).nextLong() & Long.MAX_VALUE;
        final AtomicBoolean done = new AtomicBoolean(false);

        List<String> options = new ArrayList<>();
        options.add("Annulla");
        waitPane = new FOptionPane("Cerco l'altro campanaro sulla rete Wi-Fi...\nAnche lui deve scegliere la sfida e scrivere la stessa parola d'ordine.",
                null, "Sfida un altro campanaro", null, null, options, 0, result -> cancel());
        waitPane.show();

        //1. answer the knocks of the other device
        Thread listener = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket()) {
                server = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                ss.setSoTimeout(1000);
                while (running && !done.get()) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(2000);
                        String[] hello = readLine(s).split(" ");
                        if (hello.length == 3 && MAGIC.equals(hello[0]) && token.equals(hello[1])) {
                            writeLine(s, MAGIC + " " + token + " " + myNonce);
                            long peer = Long.parseLong(hello[2]);
                            found(done, myNonce, peer, s.getInetAddress().getHostAddress());
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (!running)
                            break;
                    }
                }
            } catch (Exception e) {
                //port busy: we can still find the other side by knocking
            }
        }, "cronache-link-listen");
        listener.setDaemon(true);
        listener.start();

        //2. knock on every address of the local network until someone answers
        Thread scanner = new Thread(() -> {
            long end = System.currentTimeMillis() + SEARCH_SECONDS * 1000L;
            List<String> own = localAddresses();
            while (running && !done.get() && System.currentTimeMillis() < end) {
                ExecutorService pool = Executors.newFixedThreadPool(32);
                for (String ip : own) {
                    String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                    for (int i = 1; i < 255; i++) {
                        final String target = prefix + i;
                        if (own.contains(target))
                            continue;
                        pool.execute(() -> {
                            if (!running || done.get())
                                return;
                            try (Socket s = new Socket()) {
                                s.connect(new InetSocketAddress(target, PORT), 350);
                                s.setSoTimeout(2000);
                                writeLine(s, MAGIC + " " + token + " " + myNonce);
                                String[] reply = readLine(s).split(" ");
                                if (reply.length == 3 && MAGIC.equals(reply[0]) && token.equals(reply[1]))
                                    found(done, myNonce, Long.parseLong(reply[2]), target);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(20, TimeUnit.SECONDS);
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }
            if (running && !done.get()) {
                cancel();
                FThreads.invokeInEdtLater(() -> FOptionPane.showMessageDialog(
                        "Nessun campanaro trovato con questa parola d'ordine. Controlla di essere sulla stessa rete Wi-Fi e che lui abbia scritto la stessa parola, poi riprova.",
                        "Sfida un altro campanaro"));
            }
        }, "cronache-link-scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    private static void found(AtomicBoolean done, long myNonce, long peerNonce, String peerIp) {
        if (!done.compareAndSet(false, true))
            return;
        final boolean host = myNonce > peerNonce;
        running = false;
        closeServer();
        FThreads.invokeInEdtLater(() -> {
            if (waitPane != null) {
                waitPane.hide();
                waitPane = null;
            }
            OnlineMenu.OnlineScreen.Lobby.open();
            OnlineLobbyScreen lobby = (OnlineLobbyScreen) OnlineMenu.OnlineScreen.Lobby.getScreen();
            if (host) {
                lobby.cronacheHost();
                autoReadyAndStart(lobby, true);
            } else {
                //give the host a moment to open its server
                FThreads.invokeInBackgroundThread(() -> {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                    FThreads.invokeInEdtLater(() -> {
                        lobby.cronacheJoin(peerIp);
                        autoReadyAndStart(lobby, false);
                    });
                });
            }
        });
    }

    /**
     * Once connected, each side marks itself ready with its Cronache deck; the host starts the match
     * as soon as both are ready. Nobody has to touch the lobby.
     */
    private static void autoReadyAndStart(OnlineLobbyScreen lobby, boolean host) {
        FThreads.invokeInBackgroundThread(() -> {
            final boolean[] readied = {false};
            final boolean[] started = {false};
            for (int t = 0; t < 180 && !started[0]; t++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                FThreads.invokeInEdtAndWait(() -> {
                    try {
                        if (lobby.getLobby() == null)
                            return;
                        if (!readied[0])
                            readied[0] = lobby.cronacheReadyLocal();
                        if (host && readied[0] && !started[0] && lobby.cronacheAllReady()) {
                            started[0] = true;
                            lobby.cronacheStart();
                        }
                        if (!host && readied[0])
                            started[0] = true; //the client only has to be ready, the host starts
                    } catch (Exception ignored) {
                    }
                });
            }
        });
    }

    public static void cancel() {
        running = false;
        closeServer();
        if (waitPane != null) {
            FOptionPane p = waitPane;
            waitPane = null;
            FThreads.invokeInEdtLater(p::hide);
        }
    }

    private static void closeServer() {
        try {
            if (server != null)
                server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private static String token(String password) {
        CRC32 a = new CRC32();
        a.update(("campana:" + password).getBytes(StandardCharsets.UTF_8));
        CRC32 b = new CRC32();
        b.update((password + ":velo").getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(a.getValue()) + Long.toHexString(b.getValue());
    }

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback())
                        continue;
                } catch (Exception ignored) {
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress())
                        out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String readLine(Socket s) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String l = r.readLine();
        return l == null ? "" : l.trim();
    }

    private static void writeLine(Socket s, String line) throws Exception {
        OutputStream o = s.getOutputStream();
        o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        o.flush();
    }
}
