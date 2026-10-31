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

    /** Battle effect for a newly spawned challenger: the Bell's curse adds life while it lasts. */
    public static EffectData challengerEffect() {
        if (!isActive() || flag(FLAG_CURSE) <= 0)
            return null;
        EffectData effect = new EffectData();
        effect.name = "Maledizione della Campana";
        effect.lifeModifier = Math.max(1, flag(FLAG_CURSE_LIFE));
        return effect;
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

    /** Price modifier of Tobia's cart, depending on how much he trusts the player. */
    private static String openShop = "";

    public static float merchantPriceModifier() {
        if (openShop.startsWith("Cronache Bustine"))
            return 0.5f; //Mirta's packs: 500 gold instead of the standard 1000
        int trust = flag(FLAG_TRUST_TOBIA);
        if (trust >= 15) return 0.75f;
        if (trust >= 12) return 0.9f;
        if (trust > 0 && trust <= 6) return 1.3f;
        return 1.0f;
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

        if (gold > 0)
            Current.player().giveGold(gold);
        RewardScene.instance().loadSelectableRewards(choices, RewardScene.Type.RewardChoice, 1);
        return true;
    }

    /** Opens the travelling merchant shop with the given shop name (defined in the plane's shops.json). */
    public static boolean openMerchant(String shopName) {
        if (shopName == null || shopName.isEmpty())
            return false;
        ShopData merchant = null;
        Array<ShopData> shops = WorldData.getShopList();
        for (int i = 0; i < shops.size; i++) {
            if (shopName.equals(shops.get(i).name)) {
                merchant = shops.get(i);
                break;
            }
        }
        if (merchant == null)
            return false;
        openShop = shopName;
        ShopActor actor = new ShopActor(MERCHANT_OBJECT_ID, merchant, new PointOfInterestChanges());
        RewardScene.instance().loadRewards(actor.getRewardData(), RewardScene.Type.Shop, actor);
        Forge.switchScene(RewardScene.instance());
        return true;
    }
}
