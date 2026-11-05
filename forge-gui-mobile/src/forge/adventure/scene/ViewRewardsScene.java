package forge.adventure.scene;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import forge.Adventure;
import forge.card.CardZoom;
import forge.screens.FScreen;
import forge.screens.RewardScreen;

import java.util.List;

public class ViewRewardsScene extends ForgeScene {
    RewardScreen screen;
    private static List<?> list;
    private static int index;
    private static ViewRewardsScene object;

    public static ViewRewardsScene getInstance(List<?> list0, int index0) {
        if (object == null)
            object = new ViewRewardsScene();
        // always set new list and index
        list = list0;
        index = index0;
        return object;
    }

    @Override
    public void enter() {
        Adventure.getInstance().renderTransitionScreen = false;

        // init screen
        getScreen();

        // access the image and swap if needed
        RewardScene scene = RewardScene.instance();
        TextureRegion currentBackground = scene.getUIBackground();

        if (RewardScene.Type.Shop.equals(scene.type)) {
            if (scene.marketBackgroundImg != null && scene.marketBackgroundImg.getDrawable() instanceof TextureRegionDrawable drawable) {
                currentBackground = drawable.getRegion();
            }
        }

        screen.setBackground(currentBackground, RewardScene.Type.Loot.equals(scene.type));

        super.enter();
        CardZoom.show(list, index, new CardZoom.ActivateHandler() {
            //rewards and opened packs: "Aggiungi ai preferiti" under the zoomed card
            @Override
            public String getActivateAction(int i) {
                return null;
            }

            @Override
            public void setSelectedIndex(int i) {
            }

            @Override
            public void activate(int i) {
            }

            @Override
            public java.util.List<forge.menu.FMenuItem> getZoomActions(int i, Object item) {
                if (!(item instanceof forge.adventure.util.RewardActor))
                    return null;
                forge.adventure.util.Reward r = ((forge.adventure.util.RewardActor) item).getReward();
                if (r == null || r.getCard() == null)
                    return null;
                final forge.item.PaperCard card = r.getCard();
                final boolean fav = forge.adventure.util.Current.player().favoriteCards.contains(card);
                java.util.List<forge.menu.FMenuItem> out = new java.util.ArrayList<>();
                out.add(new forge.menu.FMenuItem(fav ? "Rimuovi dai preferiti" : "Aggiungi ai preferiti",
                        forge.Forge.hdbuttons ? (fav ? forge.assets.FSkinImage.HDSTAR_OUTLINE : forge.assets.FSkinImage.HDSTAR_FILLED)
                                : (fav ? forge.assets.FSkinImage.STAR_OUTLINE : forge.assets.FSkinImage.STAR_FILLED),
                        e -> {
                            if (fav)
                                forge.adventure.util.Current.player().favoriteCards.remove(card);
                            else
                                forge.adventure.util.Current.player().favoriteCards.add(card);
                        }));
                return out;
            }
        }, true);
    }

    @Override
    public boolean leave() {
        Adventure.getInstance().renderTransitionScreen = true;
        return super.leave();
    }

    @Override
    public FScreen getScreen() {
        if (screen == null) {
            screen = new RewardScreen("", null);
        }
        return screen;
    }
}
