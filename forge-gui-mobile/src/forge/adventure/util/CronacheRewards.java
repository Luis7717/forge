package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.ShopActor;
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
    private static final int MAX_CHOICES = 15;
    private static final int MERCHANT_OBJECT_ID = 900000;

    private CronacheRewards() { }

    public static boolean isActive() {
        String plane = Config.instance().getPlane();
        return plane != null && plane.equalsIgnoreCase(PLANE);
    }

    /** Loads the victory screen for a defeated enemy. Returns false if the normal loot should be used instead. */
    public static boolean loadVictoryRewards(EnemySprite mob) {
        if (!isActive() || mob == null)
            return false;
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
        ShopActor actor = new ShopActor(MERCHANT_OBJECT_ID, merchant, new PointOfInterestChanges());
        RewardScene.instance().loadRewards(actor.getRewardData(), RewardScene.Type.Shop, actor);
        Forge.switchScene(RewardScene.instance());
        return true;
    }
}
