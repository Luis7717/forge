package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.ShopActor;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ShopData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import com.github.tommyettinger.textra.TypingLabel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules of the "Cronache del Velo" plane (personal build):
 * - after a won duel the player picks ONE card from the opponent's deck, gold is paid automatically;
 * - a travelling merchant can be opened from a dialog action ("openMerchant": "shop name").
 */
public final class CronacheRewards {
    public static final String PLANE = "Cronache_del_Velo";
    //iOS keeps only 48-120 card textures resident: 15 choices after a duel got evicted and rendered black
    private static final int MAX_CHOICES = 8;
    private static final int MERCHANT_OBJECT_ID = 900000;

    private CronacheRewards() { }

    public static boolean isActive() {
        String plane = Config.instance().getPlane();
        return plane != null && plane.equalsIgnoreCase(PLANE);
    }

    // ---- campaign state kept in quest flags (set by dialogs, read here) ----
    public static final String FLAG_CURSE = "cr_maled";          //number of upcoming challengers with bonus life
    public static final String FLAG_CURSE_LIFE = "cr_maled_vita"; //bonus life of cursed challengers
    public static final String FLAG_DEBT = "cr_debito";           //1 = owes Tobia
    public static final String FLAG_DEBT_LEFT = "cr_scadenza";    //challenges left before the bounty hunter
    public static final String FLAG_BOUNTY = "cr_taglia";         //1 = bounty hunter is coming
    public static final String FLAG_TRUST_TOBIA = "fid_tobia";
    public static final String HUNTER = "Cronache Cacciatore";
    public static final String BRANCH_PREFIX = "Ramo_";           //enemy quest tag Ramo_<flag>_<value>

    private static int flag(String key) {
        return Current.player().getQuestFlag(key);
    }

    private static void setFlag(String key, int value) {
        Current.player().setQuestFlag(key, value);
    }

    public static boolean isHunter(EnemyData data) {
        return data != null && HUNTER.equals(data.name);
    }

    public static boolean bountyPending() {
        return isActive() && flag(FLAG_BOUNTY) > 0;
    }

    /** Picks the challenger to send, honouring branch tags (Ramo_flag_value) set by earlier choices. */
    public static EnemyData pickChallenger(List<EnemyData> candidates) {
        if (candidates == null || candidates.isEmpty())
            return null;
        EnemyData fallback = null;
        for (EnemyData e : candidates) {
            boolean hasBranch = false;
            boolean branchOk = true;
            for (String tag : e.questTags) {
                if (tag == null || !tag.startsWith(BRANCH_PREFIX))
                    continue;
                hasBranch = true;
                int sep = tag.lastIndexOf('_');
                String key = tag.substring(BRANCH_PREFIX.length(), sep);
                int value;
                try {
                    value = Integer.parseInt(tag.substring(sep + 1));
                } catch (NumberFormatException ex) {
                    branchOk = false;
                    break;
                }
                if (flag(key) != value) {
                    branchOk = false;
                    break;
                }
            }
            if (hasBranch && branchOk)
                return e;
            if (!hasBranch && fallback == null)
                fallback = e;
        }
        return fallback != null ? fallback : candidates.get(0);
    }

    // ---- difficulty (chosen in the intro): 1 Pellegrino, 2 Campanaro (default), 3 Penitente, 4 Dannato ----
    public static final String FLAG_DIFF = "cr_diff";
    public static final String FLAG_LOST = "cr_persa";            //1 = the current challenger already beat the player once
    private static final String[] DIFF_NAMES = {"Campanaro", "Pellegrino", "Campanaro", "Penitente", "Dannato"};
    private static final int[] DIFF_ENEMY_LIFE = {0, -2, 0, 2, 4};
    private static final float[] DIFF_GOLD = {1f, 1.25f, 1f, 0.85f, 0.75f};
    private static final float[] DIFF_DEFEAT_GOLD = {0f, 0f, 0f, 0.10f, 0.25f};
    private static final float[] DIFF_PRICES = {1f, 0.9f, 1f, 1.1f, 1.25f};

    private static int difficulty() {
        int d = flag(FLAG_DIFF);
        return d >= 1 && d <= 4 ? d : 2;
    }

    /** Special challenges every 10 (bosses excluded): number -> rule. */
    public static boolean isSpecial(int n) {
        return n == 20 || n == 30 || n == 40 || n == 60 || n == 70 || n == 80 || n == 90;
    }

    private static EffectData opp(EffectData e) {
        if (e.opponent == null) {
            e.opponent = new EffectData();
            e.opponent.name = "Regola della Sala";
        }
        return e.opponent;
    }

    /** Battle effect for a newly spawned challenger: difficulty, the Bell's curse and the special rules. */
    public static EffectData challengerEffect() {
        if (!isActive())
            return null;
        int n = currentChallenge();
        EffectData effect = new EffectData();
        effect.name = "Regola della Sala";
        int life = DIFF_ENEMY_LIFE[difficulty()];
        if (flag(FLAG_CURSE) > 0)
            life += Math.max(1, flag(FLAG_CURSE_LIFE));
        switch (n) {
            case 20: opp(effect).lifeModifier = -3; break;                        //Sangue scarso: you start with 3 less life
            case 30: opp(effect).changeStartCards = -2; break;                    //Mano vuota: two cards less in hand
            case 40: effect.startBattleWithCard = new String[]{"Propaganda"}; break; //Il pedaggio
            case 60: life += 6; break;                                           //La marea
            case 70: effect.changeStartCards = 3; break;                          //Doppio rintocco
            case 80: effect.startBattleWithCard = new String[]{"Glorious Anthem"}; break; //Le catene
            case 90: opp(effect).lifeModifier = -5; life += 5; break;             //Cenere
            default: break;
        }
        effect.lifeModifier = life;
        boolean empty = life == 0 && effect.opponent == null && effect.changeStartCards == 0 && effect.startBattleWithCard == null;
        return empty ? null : effect;
    }

    /** Called when the player loses in the Sala: remembers it (no first-try bonus) and applies the difficulty penalty. */
    public static void onChallengeLost(EnemySprite mob) {
        if (!isActive() || mob == null || isHunter(mob.getData()))
            return;
        setFlag(FLAG_LOST, 1);
        float pct = DIFF_DEFEAT_GOLD[difficulty()];
        int loss = (int) (Current.player().getGold() * pct);
        if (loss > 0)
            Current.player().takeGold(loss);
    }

    /** Number of the current challenge (1-100) of the Cronaca I quest, 0 if not running. */
    public static int currentChallenge() {
        for (forge.adventure.data.AdventureQuestData q : Current.player().getQuests()) {
            if (q.getID() == 1001)
                return Math.min(100, q.getCompletedStageIDs().size() + 1);
        }
        return 0;
    }

    /** Minimum deck size for the player: same rule as the challengers, 40 cards for 1-20 then +5 every 20. */
    public static int playerMinDeckSize(int defaultSize) {
        if (!isActive())
            return defaultSize;
        int n = currentChallenge();
        if (n <= 0)
            return defaultSize;
        return 40 + 5 * ((n - 1) / 20);
    }

    /** Starting life of both players: 10 for challenges 1-20, then 13, 15, 18 and 20 (grows with the deck size). */
    public static int duelLife(int n) {
        if (n <= 20) return 10;
        if (n <= 40) return 13;
        if (n <= 60) return 15;
        if (n <= 80) return 18;
        return 20;
    }

    public static int playerDuelLife(int defaultLife) {
        if (!isActive() || currentChallenge() <= 0)
            return defaultLife;
        return duelLife(currentChallenge());
    }

    public static int enemyDuelLife(EnemyData enemy, int defaultLife) {
        if (!isActive() || enemy == null || enemy.name == null || !enemy.name.startsWith("Cronache") || currentChallenge() <= 0)
            return defaultLife;
        int life = duelLife(currentChallenge());
        if (enemy.boss) life += 3;          //bosses are a little tougher
        if (isHunter(enemy)) life += 2;
        return life;
    }

    /** Price modifier of Tobia's cart, depending on how much he trusts the player. */
    private static String openShop = "";

    public static float merchantPriceModifier() {
        if (openShop.startsWith("Cronache Bustine"))
            return 0.25f; //Mirta's packs: 250 gold instead of the standard 1000
        int trust = flag(FLAG_TRUST_TOBIA);
        float base = 0.5f * DIFF_PRICES[difficulty()]; //Tobia sells at half the standard Adventure prices
        if (trust >= 15) return 0.75f * base;
        if (trust >= 12) return 0.9f * base;
        if (trust > 0 && trust <= 6) return 1.3f * base;
        return base;
    }

    /** Bookkeeping after every won duel: curses wear off, debts come due, the hunter is paid off. */
    private static void onChallengeWon(EnemySprite mob) {
        if (isHunter(mob.getData())) {
            setFlag(FLAG_BOUNTY, 0);
            setFlag(FLAG_DEBT, 0);
            setFlag(FLAG_DEBT_LEFT, 0);
            return;
        }
        if (flag(FLAG_CURSE) > 0)
            setFlag(FLAG_CURSE, flag(FLAG_CURSE) - 1);
        if (flag(FLAG_DEBT) > 0 && flag(FLAG_BOUNTY) == 0) {
            int left = flag(FLAG_DEBT_LEFT) - 1;
            if (left <= 0) {
                setFlag(FLAG_DEBT_LEFT, 0);
                setFlag(FLAG_BOUNTY, 1);
            } else {
                setFlag(FLAG_DEBT_LEFT, left);
            }
        }
    }

    /** Loads the victory screen for a defeated enemy. Returns false if the normal loot should be used instead. */
    public static boolean loadVictoryRewards(EnemySprite mob) {
        if (!isActive() || mob == null)
            return false;
        int n = currentChallenge();
        boolean hunter = isHunter(mob.getData());
        int picks = 1;
        if (!hunter && flag(FLAG_LOST) == 0) picks++;     //won at the first attempt
        if (!hunter && isSpecial(n)) picks++;             //special challenge
        if (!hunter) setFlag(FLAG_LOST, 0);
        onChallengeWon(mob);
        Deck deck = Current.latestDeck();
        if (deck == null || deck.getMain() == null)
            return false;

        int gold = 0;
        Array<Reward> standard = mob.getRewards();
        for (int i = 0; i < standard.size; i++) {
            Reward r = standard.get(i);
            if (r != null && r.getType() == Reward.Type.Gold)
                gold += r.getCount();
        }

        List<PaperCard> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PaperCard card : deck.getMain().toFlatList()) {
            if (card == null || card.isVeryBasicLand())
                continue;
            if (seen.add(card.getName()))
                unique.add(card);
        }
        if (unique.isEmpty())
            return false;
        Collections.shuffle(unique);

        Array<Reward> choices = new Array<>();
        for (int i = 0; i < unique.size() && i < MAX_CHOICES; i++)
            choices.add(new Reward(unique.get(i)));

        gold = Math.round(gold * DIFF_GOLD[difficulty()] * (picks > 1 && !isSpecial(n) || picks > 2 ? 1.5f : 1f)); //first-try bonus
        if (gold > 0)
            Current.player().giveGold(gold);
        RewardScene.instance().loadSelectableRewards(choices, RewardScene.Type.RewardChoice, Math.min(picks, choices.size));
        return true;
    }

    private static String merchantVisit;
    private static ShopData merchantData;
    private static PointOfInterestChanges merchantChanges;

    /** Opens the travelling merchant shop with the given shop name (defined in the plane's shops.json). */
    public static boolean openMerchant(String shopName) {
        if (shopName == null || shopName.isEmpty())
            return false;
        //the shop itself or one of its themed variants ("<name> #W" ...), chosen at random for variety
        List<ShopData> options = new ArrayList<>();
        Array<ShopData> shops = WorldData.getShopList();
        for (int i = 0; i < shops.size; i++) {
            String n = shops.get(i).name;
            if (shopName.equals(n) || (n != null && n.startsWith(shopName + " #")))
                options.add(shops.get(i));
        }
        if (options.isEmpty())
            return false;
        //one stock per visit: going back into the shop during the same visit shows the same cards (and what was
        //bought stays sold); the next challenge brings a new stock
        String visit = shopName + "|" + currentChallenge() + "|" + Current.player().getName();
        if (!visit.equals(merchantVisit) || merchantChanges == null) {
            long seed = new java.util.Random(System.nanoTime() ^ Double.doubleToLongBits(Math.random())).nextLong();
            merchantVisit = visit;
            merchantData = options.get((int) Math.floorMod(seed, (long) options.size()));
            merchantChanges = new PointOfInterestChanges();
            merchantChanges.setRotatingShopSeed(MERCHANT_OBJECT_ID, seed);
        }
        ShopData merchant = merchantData;
        openShop = shopName;
        PointOfInterestChanges changes = merchantChanges;
        ShopActor actor = new ShopActor(MERCHANT_OBJECT_ID, merchant, changes);
        RewardScene.instance().loadRewards(actor.getRewardData(), RewardScene.Type.Shop, actor);
        Forge.switchScene(RewardScene.instance());
        return true;
    }

    // ---- statistics shown by the old man ({var=cr_...} in dialogs) ----
    private static File picksFile() {
        String name = Current.player().getName();
        if (name == null || name.isEmpty()) name = "giocatore";
        return new File(ForgeConstants.USER_ADVENTURE_DIR, "cronache_scelte_" + name.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
    }

    /** Remembers which card was picked at which challenge. */
    public static void recordPick(Reward reward) {
        try {
            if (!isActive() || reward == null || reward.getType() != Reward.Type.Card || reward.getCard() == null)
                return;
            File f = picksFile();
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f, true);
            w.write(currentChallenge() + "|" + reward.getCard().getName() + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    private static String lastPicks(int max) {
        List<String> lines = new ArrayList<>();
        try {
            File f = picksFile();
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null)
                    if (!line.trim().isEmpty()) lines.add(line);
                r.close();
            }
        } catch (Exception ignored) { }
        if (lines.isEmpty())
            return "nessuna, per ora";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - max); i < lines.size(); i++) {
            String[] p = lines.get(i).split("\\|", 2);
            sb.append("\n").append(p.length == 2 ? "Sfida " + p[0] + ": " + p[1] : lines.get(i));
        }
        return sb.toString();
    }

    public static void fillVariables(TypingLabel label) {
        try {
            if (!isActive() || Current.player() == null)
                return;
            int wins = Current.player().getStatistic().totalWins();
            int losses = Current.player().getStatistic().totalLoss();
            int total = wins + losses;
            label.setVariable("cr_sfida", String.valueOf(currentChallenge()));
            label.setVariable("cr_vinte", String.valueOf(wins));
            label.setVariable("cr_perse", String.valueOf(losses));
            label.setVariable("cr_perc", total == 0 ? "-" : (100 * wins / total) + "%");
            label.setVariable("cr_mazzo", String.valueOf(playerMinDeckSize(40)));
            label.setVariable("cr_diff", DIFF_NAMES[difficulty()]);
            label.setVariable("cr_carte", lastPicks(10));
        } catch (Exception ignored) { }
    }
}
