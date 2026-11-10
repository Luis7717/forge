package forge.itemmanager.filters;

import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.itemmanager.ItemManager;
import forge.itemmanager.SFilterUtil;

import java.util.function.Predicate;


public class CardSearchFilter extends TextSearchFilter<PaperCard> {
    private final boolean inName, inType, inText, inCost;

    public CardSearchFilter(ItemManager<? super PaperCard> itemManager0) {
        this(itemManager0, true, true, true, false);
    }

    public CardSearchFilter(ItemManager<? super PaperCard> itemManager0, boolean inName0, boolean inType0, boolean inText0, boolean inCost0) {
        super(itemManager0);
        inName = inName0;
        inType = inType0;
        inText = inText0;
        inCost = inCost0;
    }

    private String caption = "";

    //the ghost text says what is searched: name, type and the text of the cards
    @Override
    public String getCaption() {
        return caption;
    }

    @Override
    public void setCaption(String caption0) {
        caption = caption0 == null ? "" : caption0;
        getWidget();
        String what = forge.Forge.getLocalizer().getMessageorUseDefault("lblSearchCardWords", "Search name, type or text");
        txtSearch.setGhostText(caption.isEmpty() ? what : what + " (" + caption + ")");
    }

    @Override
    public ItemFilter<PaperCard> createCopy() {
        CardSearchFilter copy = new CardSearchFilter(itemManager, inName, inType, inText, inCost);
        copy.getWidget(); //initialize widget
        copy.txtSearch.setText(txtSearch.getText());
        return copy;
    }

    @Override
    protected Predicate<PaperCard> buildPredicate() {
        return SFilterUtil.buildTextFilter(
                txtSearch.getText(),
                false,
                inName,
                inType,
                inText,
                inCost);
    }

    @Override
    protected <U extends InventoryItem> boolean showUnsupportedItem(U item) {
        //fallback to regular item text filter if item not PaperCard
        return SFilterUtil.buildItemTextFilter(txtSearch.getText()).test(item);
    }
}
