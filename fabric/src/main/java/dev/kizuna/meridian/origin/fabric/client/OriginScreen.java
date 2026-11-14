package dev.kizuna.meridian.origin.fabric.client;

import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginCard;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * 出身界面：几张并排的纸，每张上半是来历，下半照实列天赋和天生的麻烦。
 * 没选时每张底下有「就是它」，点了以后底栏换成确认；选过以后只有一张，只能看。
 *
 * <p>中性底色，不带主题边框。窗口窄得并排放不下时改成上下排，整列可以滚动。</p>
 */
public final class OriginScreen extends BaseOwoScreen<FlowLayout> {
    private static final int FULL = 100;
    /** 一张纸的宽度随窗口变：并排时把宽度平分，但不宽过上限；平分下来比下限还窄就改成上下排。 */
    private static final int MAX_CARD_WIDTH = 170;
    private static final int MIN_CARD_WIDTH = 110;
    private static final int ICON_SIZE = 16;
    private static final int GAP = 8;
    private static final int PADDING = 8;
    private static final int SCROLLBAR_SPACE = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 72;
    private static final int BACKDROP = 0xFA101012;
    private static final int PAPER = 0xFF26262A;
    private static final int PAPER_EDGE = 0xFF4A4A52;
    private static final int PAPER_PICKED_EDGE = 0xFFD8D8DC;
    private static final String TALENTS = "screen.meridian_origin.talents";
    private static final String THREATS = "screen.meridian_origin.threats";
    private static final Color TITLE = Color.ofArgb(0xFFF2F2F2);
    private static final Color TEXT = Color.ofArgb(0xFFD0D0D4);
    private static final Color SECTION = Color.ofArgb(0xFF9A9AA2);
    private final OriginOffer offer;
    private final Consumer<String> choose;
    /** 点了「就是它」、还没确认的那一张。 */
    private Optional<OriginCard> picked = Optional.empty();
    private FlowLayout root;
    private int cardWidth;
    private boolean sideBySide;

    public OriginScreen(OriginOffer offer, Consumer<String> choose) {
        super(Component.translatable(offer.getChosen() ? "screen.meridian_origin.title.chosen"
                : "screen.meridian_origin.title"));
        this.offer = offer;
        this.choose = choose;
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        // 整屏一层半透明的中性底，标题、纸、底栏从上到下。
        this.root = root;
        root.surface(Surface.flat(BACKDROP));
        root.padding(Insets.of(PADDING));
        root.gap(GAP);
        root.horizontalAlignment(HorizontalAlignment.CENTER);
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        // 选出身不暂停世界，多人和单人一致。
        return false;
    }

    OriginOffer offer() {
        // 界面上现在摆着的候选。
        return offer;
    }

    void pick(OriginCard card) {
        // 点了某一张的「就是它」：先不发，底栏换成确认。
        picked = Optional.of(card);
        rebuild();
    }

    void settle() {
        // 确认：把出身 ID 交出去，合上界面。
        choose.accept(picked.orElseThrow().getId());
        onClose();
    }

    void reconsider() {
        // 再想想：回到没点选的样子。
        picked = Optional.empty();
        rebuild();
    }

    private void rebuild() {
        // 点选、取消确认时整屏重排一次；内容很少，不做局部更新。
        measure();
        root.clearChildren();
        root.child(UIComponents.label(getTitle()).color(TITLE).shadow(true /* shadow */));
        root.child(cards());
        if (sideBySide && !offer.getChosen()) {
            root.child(chooseRow());
        }
        root.child(footer());
    }

    private void measure() {
        // 并排时几张纸平分窗口宽度；平分下来太窄就上下排，一张占满能用的宽度。
        int count = offer.getCardsCount();
        int usable = width - 2 * PADDING - SCROLLBAR_SPACE;
        int shared = (usable - (count - 1) * GAP) / count;
        sideBySide = shared >= MIN_CARD_WIDTH;
        cardWidth = Math.min(MAX_CARD_WIDTH, sideBySide ? shared : Math.max(MIN_CARD_WIDTH, usable));
    }

    private io.wispforest.owo.ui.core.UIComponent cards() {
        // 几张纸放在一个竖向滚动区里：纸比窗口高时滚着看，标题、按钮和底栏留在外面不动。
        FlowLayout row = sideBySide ? UIContainers.horizontalFlow(Sizing.content(), Sizing.content())
                : UIContainers.verticalFlow(Sizing.content(), Sizing.content());
        row.gap(GAP);
        row.verticalAlignment(VerticalAlignment.TOP);
        offer.getCardsList().forEach(card -> row.child(card(card)));
        int fixedRows = sideBySide && !offer.getChosen() ? 2 : 1;
        int room = height - fixedRows * (BUTTON_HEIGHT + GAP) - font.lineHeight - GAP - 2 * PADDING;
        var scroll = UIContainers.verticalScroll(Sizing.content(),
                Sizing.fixed(Math.max(BUTTON_HEIGHT, Math.min(room, contentHeight()))), row);
        scroll.padding(Insets.right(SCROLLBAR_SPACE));
        return scroll;
    }

    private int contentHeight() {
        // 滚动区只要纸那么高：并排时取最高的一张，上下排时把几张加起来。纸矮于窗口时按钮就贴在纸下面。
        var heights = offer.getCardsList().stream().mapToInt(this::cardHeight);
        return sideBySide ? heights.max().orElse(0)
                : heights.sum() + (offer.getCardsCount() - 1) * GAP;
    }

    private int cardHeight(OriginCard card) {
        // 照 card() 里的排法把各行的高度加起来；字的行数按纸的宽度折行算。
        Identifier id = Identifier.parse(card.getId());
        String key = "origin." + id.getNamespace() + "." + id.getPath();
        int textWidth = cardWidth - 2 * PADDING;
        int rowGap = GAP / 2;
        int name = wrapped(Component.translatable(key + ".name"), textWidth - ICON_SIZE - rowGap);
        int story = wrapped(Component.translatable(key + ".story"), textWidth);
        int sections = sectionHeight(TALENTS, key + ".talent.", card.getTalentLines(), textWidth)
                + sectionHeight(THREATS, key + ".threat.", card.getThreatLines(), textWidth);
        int button = sideBySide || offer.getChosen() ? 0 : rowGap + rowGap + BUTTON_HEIGHT;
        return 2 * PADDING + Math.max(ICON_SIZE, name) + rowGap + story + sections + button;
    }

    private int sectionHeight(String titleKey, String linePrefix, int lines, int textWidth) {
        // 一段：上边距、小标题，下面每条一行（没有时是一行「无」），行与行之间各一个间隔。
        int rowGap = GAP / 2;
        int height = rowGap + rowGap + wrapped(Component.translatable(titleKey), textWidth);
        if (lines == 0) {
            height += rowGap + wrapped(Component.translatable("screen.meridian_origin.none"), textWidth);
        }
        for (int line = 1; line <= lines; line++) {
            height += rowGap + wrapped(Component.translatable(linePrefix + line), textWidth);
        }
        return height;
    }

    private int wrapped(Component content, int textWidth) {
        return font.wordWrapHeight(content, textWidth);
    }

    private FlowLayout chooseRow() {
        // 并排时每张纸正下方一个「就是它」，不跟着纸滚动，纸再长也点得到。
        FlowLayout row = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        row.gap(GAP);
        row.padding(Insets.right(SCROLLBAR_SPACE));
        offer.getCardsList().forEach(card -> row.child(chooseButton(card)));
        return row;
    }

    private io.wispforest.owo.ui.component.ButtonComponent chooseButton(OriginCard card) {
        var button = UIComponents.button(Component.translatable("screen.meridian_origin.choose"),
                ignored -> pick(card));
        button.horizontalSizing(Sizing.fixed(cardWidth));
        button.verticalSizing(Sizing.fixed(BUTTON_HEIGHT));
        button.id("choose-" + card.getId());
        return button;
    }

    private FlowLayout card(OriginCard card) {
        // 一张纸：图标和名字、来历、天赋、天生的麻烦；上下排时「就是它」跟在纸的最后。
        Identifier id = Identifier.parse(card.getId());
        String key = "origin." + id.getNamespace() + "." + id.getPath();
        boolean highlighted = picked.filter(card::equals).isPresent();
        int textWidth = cardWidth - 2 * PADDING;
        FlowLayout paper = UIContainers.verticalFlow(Sizing.fixed(cardWidth), Sizing.content());
        paper.surface(Surface.flat(PAPER).and(Surface.outline(highlighted ? PAPER_PICKED_EDGE : PAPER_EDGE)));
        paper.padding(Insets.of(PADDING));
        paper.gap(GAP / 2);
        FlowLayout heading = UIContainers.horizontalFlow(Sizing.fill(FULL), Sizing.content());
        heading.gap(GAP / 2);
        heading.verticalAlignment(VerticalAlignment.CENTER);
        var icon = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(card.getIcon())));
        heading.child(UIComponents.item(icon));
        heading.child(text(Component.translatable(key + ".name"), TITLE, textWidth - ICON_SIZE - GAP / 2));
        paper.child(heading);
        paper.child(text(Component.translatable(key + ".story"), TEXT, textWidth));
        section(paper, TALENTS, key + ".talent.", card.getTalentLines(), textWidth);
        section(paper, THREATS, key + ".threat.", card.getThreatLines(), textWidth);
        if (!sideBySide && !offer.getChosen()) {
            paper.child(chooseButton(card).margins(Insets.top(GAP / 2)));
        }
        return paper;
    }

    private void section(FlowLayout paper, String titleKey, String linePrefix, int lines, int textWidth) {
        // 一段界面文字：小标题，下面每条一行；一条都没有时写「无」。
        paper.child(text(Component.translatable(titleKey), SECTION, textWidth).margins(Insets.top(GAP / 2)));
        if (lines == 0) {
            paper.child(text(Component.translatable("screen.meridian_origin.none"), TEXT, textWidth));
        }
        for (int line = 1; line <= lines; line++) {
            paper.child(text(Component.translatable(linePrefix + line), TEXT, textWidth));
        }
    }

    private FlowLayout footer() {
        // 底栏三种样子：选过的只有「合上」；没点选时提醒不能改，可以先不选；点选以后问一遍再定。
        FlowLayout bar = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
        bar.gap(GAP);
        bar.verticalAlignment(VerticalAlignment.CENTER);
        if (offer.getChosen()) {
            bar.child(button("screen.meridian_origin.close", "close", this::onClose));
        } else if (picked.isEmpty()) {
            bar.child(UIComponents.label(Component.translatable("screen.meridian_origin.final")).color(TEXT));
            bar.child(button("screen.meridian_origin.later", "later", this::onClose));
        } else {
            Identifier id = Identifier.parse(picked.get().getId());
            Component name = Component.translatable("origin." + id.getNamespace() + "." + id.getPath() + ".name");
            bar.child(UIComponents.label(Component.translatable("screen.meridian_origin.confirm", name)).color(TITLE));
            bar.child(button("screen.meridian_origin.confirm.yes", "confirm", this::settle));
            bar.child(button("screen.meridian_origin.confirm.no", "cancel", this::reconsider));
        }
        return bar;
    }

    private static io.wispforest.owo.ui.component.ButtonComponent button(String key, String id, Runnable action) {
        var button = UIComponents.button(Component.translatable(key), ignored -> action.run());
        button.horizontalSizing(Sizing.fixed(BUTTON_WIDTH));
        button.verticalSizing(Sizing.fixed(BUTTON_HEIGHT));
        button.id(id);
        return button;
    }

    private static LabelComponent text(Component content, Color color, int width) {
        // 纸上的字不带阴影，按纸的宽度折行。
        LabelComponent label = UIComponents.label(content).color(color);
        label.shadow(false /* shadow */);
        label.maxWidth(width);
        label.horizontalSizing(Sizing.fixed(width));
        return label;
    }
}
