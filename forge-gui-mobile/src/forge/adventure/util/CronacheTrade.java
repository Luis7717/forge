package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.StaticData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.ShopScene;
import forge.adventure.stage.MapStage;
import forge.adventure.world.WorldSave;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Cronache del Velo: card trading between players (a code passed through any chat app) and the
 * export of the campaign deck for Wi-Fi duels in Forge's online mode.
 *
 * A trade code carries a random id, the sender's name and the cards; the cards leave the sender's
 * collection when the code is made, and a code can be received only once per player.
 */
public final class CronacheTrade {
    public static final int TRADE_FROM_CHALLENGE = 50;
    private static final String PREFIX = "CRONACHE-SCAMBIO:";
    private static final String SALT = "velo-campana";

    /** True while the sell editor is open as the "give cards" screen. */
    public static boolean giving = false;
    private static final CardPool pending = new CardPool();

    private CronacheTrade() {
    }

    public static boolean tradeUnlocked() {
        return CronacheRewards.currentChallenge() >= TRADE_FROM_CHALLENGE;
    }

    private static void info(String text) {
        Gdx.app.postRunnable(() -> MapStage.getInstance().showCronacheInfo(text));
    }

    // ---------------------------------------------------------------- giving

    public static void startGive() {
        if (!tradeUnlocked()) {
            info("Lo scambio di carte si sblocca dalla sfida " + TRADE_FROM_CHALLENGE + ".");
            return;
        }
        giving = true;
        pending.clear();
        Forge.switchScene(ShopScene.instance(null));
    }

    /** Moves copies from the collection into the trade package; returns how many were moved. */
    public static int addToTrade(PaperCard card, int amount) {
        AdventurePlayer p = Current.player();
        int n = Math.min(amount, p.getCards().count(card));
        if (n <= 0 || !p.getCards().remove(card, n))
            return 0;
        pending.add(card, n);
        return n;
    }

    public static int pendingCount() {
        return pending.countAll();
    }

    /** Called when the give screen closes: builds the code, copies it and tells the player. */
    public static void finishGive() {
        if (!giving)
            return;
        giving = false;
        if (pending.isEmpty())
            return;
        String code;
        try {
            code = encode(pending);
        } catch (Exception e) {
            //could not build the code: give the cards back
            Current.player().getCards().addAll(pending);
            pending.clear();
            info("Non sono riuscito a creare il codice dello scambio: le carte sono tornate nella tua collezione.");
            return;
        }
        int count = pending.countAll();
        pending.clear();
        boolean copied = false;
        try {
            Forge.getClipboard().setContents(code);
            copied = true;
        } catch (Exception ignored) {
        }
        appendLine("cronache_scambi_inviati_" + safeName() + ".txt", code);
        WorldSave.getCurrentSave().autoSaveAsync();
        info("{COLOR=ORANGE}Hai preparato " + count + (count == 1 ? " carta" : " carte") + " per lo scambio.{ENDCOLOR}\n"
                + (copied ? "Il codice è stato copiato negli appunti: incollalo in una chat (WhatsApp, ecc.) e mandalo al tuo amico. "
                          : "Non sono riuscito a copiare il codice negli appunti. ")
                + "Lui lo copia e sceglie \"(Ricevi delle carte)\" dal vecchio nella sua Sala.\n"
                + "Le carte non sono più nella tua collezione. Il codice resta salvato nel file " + "cronache_scambi_inviati_" + safeName() + ".txt.");
    }

    // ---------------------------------------------------------------- receiving

    public static void startReceive() {
        if (!tradeUnlocked()) {
            info("Lo scambio di carte si sblocca dalla sfida " + TRADE_FROM_CHALLENGE + ".");
            return;
        }
        String text = "";
        try {
            if (Forge.getClipboard().hasContents())
                text = Forge.getClipboard().getContents();
        } catch (Exception ignored) {
        }
        int at = text == null ? -1 : text.indexOf(PREFIX);
        if (at < 0) {
            info("Negli appunti non c'è nessun codice di scambio.\nTieni premuto il messaggio con il codice che ti hanno mandato, scegli \"Copia\", poi torna qui e scegli di nuovo \"(Ricevi delle carte)\".");
            return;
        }
        String code = text.substring(at).trim().split("\\s+")[0];
        Decoded d;
        try {
            d = decode(code);
        } catch (Exception e) {
            info("Il codice negli appunti non è valido o è incompleto. Fattelo rimandare e copialo per intero.");
            return;
        }
        String usedFile = "cronache_scambi_ricevuti_" + safeName() + ".txt";
        if (readLines(usedFile).contains(d.id)) {
            info("Hai già ricevuto le carte di questo codice.");
            return;
        }
        if (d.sender.equals(Current.player().getName()) && readLines("cronache_scambi_inviati_" + safeName() + ".txt").contains(code)) {
            //your own code: allowed, it simply gives the cards back (an undo)
        }
        List<String> names = new ArrayList<>();
        int total = 0;
        for (Map.Entry<PaperCard, Integer> e : d.cards) {
            Current.player().addCard(e.getKey(), e.getValue());
            total += e.getValue();
            if (names.size() < 12)
                names.add(e.getValue() + "x " + e.getKey().getName());
        }
        appendLine(usedFile, d.id);
        WorldSave.getCurrentSave().autoSaveAsync();
        info("{COLOR=ORANGE}Hai ricevuto " + total + (total == 1 ? " carta" : " carte") + " da " + d.sender + ".{ENDCOLOR}\n"
                + String.join(", ", names) + (d.cards.size() > names.size() ? ", ..." : "")
                + "\nLe trovi nella tua collezione.");
    }

    // ---------------------------------------------------------------- Wi-Fi duel

    /**
     * "Sfida un altro campanaro": copies the campaign deck to the constructed decks (preselected in the lobby),
     * saves, leaves the adventure and asks for the password that pairs the two phones.
     */
    public static void exportDeckForWifi() {
        try {
            Deck src = Current.player().getSelectedDeck();
            String name = "Cronache - " + Current.player().getName();
            Deck out = new Deck(name);
            out.getMain().addAll(src.getMain());
            if (src.has(DeckSection.Sideboard))
                out.getOrCreate(DeckSection.Sideboard).addAll(src.get(DeckSection.Sideboard));
            FModel.getDecks().getConstructed().add(out);
            forge.localinstance.properties.ForgePreferences prefs = FModel.getPreferences();
            String state = "CONSTRUCTED_DECK;" + name;
            for (forge.localinstance.properties.ForgePreferences.FPref pref : new forge.localinstance.properties.ForgePreferences.FPref[]{
                    forge.localinstance.properties.ForgePreferences.FPref.CONSTRUCTED_P1_DECK_STATE,
                    forge.localinstance.properties.ForgePreferences.FPref.CONSTRUCTED_P2_DECK_STATE})
                prefs.setPref(pref, state);
            prefs.setPref(forge.localinstance.properties.ForgePreferences.FPref.PLAYER_NAME, Current.player().getName());
            prefs.save();
            WorldSave.getCurrentSave().autoSaveAsync();
            Forge.afterClassicSwitch = forge.screens.online.CronacheLink::start;
            Forge.switchToClassic();
        } catch (Exception e) {
            info("Non sono riuscito a preparare la sfida: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- code format

    private static final class Decoded {
        String id, sender;
        List<Map.Entry<PaperCard, Integer>> cards = new ArrayList<>();
    }

    private static String encode(CardPool pool) throws Exception {
        StringBuilder sb = new StringBuilder();
        String id = Long.toHexString(new Random(System.nanoTime() ^ Double.doubleToLongBits(Math.random())).nextLong());
        sb.append("v1\n").append(id).append('\n').append(Current.player().getName().replace('\n', ' ')).append('\n');
        for (Map.Entry<PaperCard, Integer> e : pool) {
            PaperCard c = e.getKey();
            sb.append(e.getValue()).append('|').append(c.getName()).append('|').append(c.getEdition()).append('\n');
        }
        String body = sb.toString();
        CRC32 crc = new CRC32();
        crc.update((SALT + body).getBytes(StandardCharsets.UTF_8));
        body = body + "crc:" + Long.toHexString(crc.getValue());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return PREFIX + b64encode(bos.toByteArray());
    }

    private static Decoded decode(String code) throws Exception {
        byte[] raw = b64decode(code.substring(PREFIX.length()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = gz.read(buf)) > 0)
                out.write(buf, 0, r);
        }
        String body = out.toString("UTF-8");
        int c = body.lastIndexOf("crc:");
        if (c < 0)
            throw new IllegalArgumentException("no crc");
        String content = body.substring(0, c);
        CRC32 crc = new CRC32();
        crc.update((SALT + content).getBytes(StandardCharsets.UTF_8));
        if (!Long.toHexString(crc.getValue()).equals(body.substring(c + 4).trim()))
            throw new IllegalArgumentException("bad crc");
        String[] lines = content.split("\n");
        if (lines.length < 4 || !"v1".equals(lines[0]))
            throw new IllegalArgumentException("bad header");
        Decoded d = new Decoded();
        d.id = lines[1];
        d.sender = lines[2];
        for (int i = 3; i < lines.length; i++) {
            String[] p = lines[i].split("\\|", 3);
            if (p.length < 2)
                continue;
            int n = Integer.parseInt(p[0]);
            PaperCard pc = null;
            if (p.length > 2 && !p[2].isEmpty())
                pc = StaticData.instance().getCommonCards().getCard(p[1], p[2]);
            if (pc == null)
                pc = StaticData.instance().getCommonCards().getCard(p[1]);
            if (pc == null || n <= 0)
                continue;
            d.cards.add(new java.util.AbstractMap.SimpleEntry<>(pc, n));
        }
        if (d.cards.isEmpty())
            throw new IllegalArgumentException("no cards");
        return d;
    }

    // ---------------------------------------------------------------- small files

    private static String safeName() {
        return Current.player().getName().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static File file(String name) {
        return new File(ForgeConstants.USER_ADVENTURE_DIR, name);
    }

    private static void appendLine(String name, String line) {
        try (FileWriter w = new FileWriter(file(name), true)) {
            w.write(line + "\n");
        } catch (Exception ignored) {
        }
    }

    private static List<String> readLines(String name) {
        try {
            File f = file(name);
            if (f.exists()) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null)
                        lines.add(l.trim());
                }
                return lines;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------- base64 (url-safe, no padding; no java.util.Base64 on every runtime)

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static String b64encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length * 4 + 2) / 3);
        int i = 0;
        while (i + 2 < in.length) {
            int v = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8) | (in[i + 2] & 0xff);
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]).append(B64[v & 63]);
            i += 3;
        }
        int rest = in.length - i;
        if (rest == 1) {
            int v = (in[i] & 0xff) << 16;
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]);
        } else if (rest == 2) {
            int v = ((in[i] & 0xff) << 16) | ((in[i + 1] & 0xff) << 8);
            sb.append(B64[(v >> 18) & 63]).append(B64[(v >> 12) & 63]).append(B64[(v >> 6) & 63]);
        }
        return sb.toString();
    }

    private static byte[] b64decode(String s) {
        int[] map = new int[128];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < B64.length; i++)
            map[B64[i]] = i;
        map['+'] = 62; map['/'] = 63; //accept the standard alphabet too
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '=')
                break;
            if (ch >= 128 || map[ch] < 0)
                throw new IllegalArgumentException("bad char");
            buf = (buf << 6) | map[ch];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
