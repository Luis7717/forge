package forge.adventure.character;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.data.RewardData;
import forge.adventure.data.ShopData;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Reward;
import forge.adventure.world.WorldSave;


/**
 * Map actor that will open the Shop on collision
 */
public class ShopActor extends MapActor {
    private final MapStage stage;
    private ShopData shopData;
    //Changes of the map this shop was loaded for. Used to look up the shop seed when the stock is generated.
    private final PointOfInterestChanges loadChanges;
    //Generated lazily the first time the shop is opened (or restocked), so that loading a town doesn't have to
    //generate the cards of every shop in it.
    private Array<Reward> rewardData;

    public ShopActor(MapStage stage, int id, ShopData data, PointOfInterestChanges loadChanges) {
        super(id);
        this.stage = stage;
        this.shopData = data;
        this.loadChanges = loadChanges;
    }

    /** Standalone shop not tied to a town map (travelling merchant of the Cronache plane). */
    public ShopActor(int id, ShopData data, PointOfInterestChanges changes) {
        this(null, id, data, changes);
    }

    /** Changes used to track bought cards: the current map's when in a town, otherwise this shop's own. */
    public PointOfInterestChanges getChanges() {
        return stage != null ? stage.getChanges() : loadChanges;
    }

    public float getPriceModifier() {
        if (stage == null)
            return forge.adventure.util.CronacheRewards.merchantPriceModifier(); //Tobia's cart: price depends on trust
        PointOfInterestChanges changes = getChanges();
        float townPricemodifier = changes == null ? 1f : changes.getTownPriceModifier();
        float shopPriceModifier = changes == null ? 1f : changes.getShopPriceModifier(objectId);
        return shopPriceModifier * townPricemodifier;
    }

    public MapStage getMapStage() {
        return stage;
    }

    @Override
    public void onPlayerCollide() {
        stage.getPlayerSprite().stop();
        RewardScene.instance().loadRewards(getRewardData(), RewardScene.Type.Shop, this);
        Forge.switchScene(RewardScene.instance());
    }


    public boolean isUnlimited() {
        return shopData.unlimited;
    }

    @Override
    public String getName() {
        return shopData.name;
    }

    public String getDescription() {
        return shopData.getDescription();
    }

    public int getRestockPrice() {
        return shopData.restockPrice;
    }

    public boolean canRestock() {
        return getRestockPrice() > 0;
    }

    public ShopData getShopData() {
        return shopData;
    }

    public void setRewardData(Array<Reward> data) {
        rewardData = data;
    }

    /**
     * Returns the stock of this shop, generating it from the shop seed on first use.
     * Same seed and same generation as before, only the moment it happens has moved from town load to shop entry.
     */
    public Array<Reward> getRewardData() {
        if (rewardData == null) {
            Array<Reward> generated = new Array<>();
            WorldSave.getCurrentSave().getWorld().getRandom().setSeed(loadChanges.getShopSeed(objectId));
            for (RewardData rdata : new Array.ArrayIterator<>(shopData.rewards)) {
                generated.addAll(rdata.generate(false, false));
            }
            rewardData = generated;
        }
        return rewardData;
    }
}
