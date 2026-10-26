package com.silentyeti.pluginless.pda;

import com.silentyeti.pluginless.Pluginless;
import com.wf.wflib.pda.client.PdaApp;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.button;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.stack;
import static com.silentyeti.pluginless.pda.PdaWidgets.fit;
import static com.silentyeti.pluginless.pda.PdaWidgets.wrap;
import static com.silentyeti.pluginless.pda.PdaWidgets.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * The one Pluginless tile on the PDA home. A strip across the top (back, page title, balance) and below it the open
 * page: the home page or a {@link PdaClientPage}. Pages are built once, so they are chosen by the {@code page} signal.
 */
public final class PlApp implements PdaApp {
    /** Height of the strip above the page. */
    static final float TOP = 18;
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Pluginless.MOD_ID, "pluginless");
    static final PlApp INSTANCE = new PlApp();
    static final Subject<PlApp> APP = Subject.register("pda_pl", PlApp.class, () -> INSTANCE);

    private PlApp() {}

    static void register() {
        var pages = PdaClientSetup.pages();
        Signals.num(APP, "page", app -> index(pages));
        Signals.bool(APP, "sub", app -> index(pages) > 0);
        Signals.text(APP, "title", PdaWidgets.TEXT_MAX, app -> title(pages));
        Signals.text(APP, "balance", PdaWidgets.TEXT_MAX, app -> {
            var view = PdaClientState.view(PdaClientState.page());
            return PdaClientState.money(view, PdaClientState.number(view, "balance"));
        });
        Signals.num(APP, "unread", app -> PdaClientState.number(PdaClientState.view("home"), "unread"));
        Signals.text(APP, "msg", PdaWidgets.TEXT_MAX, app -> PdaClientState.message());
        Signals.bool(APP, "msg_ok", app -> !PdaClientState.message().isEmpty() && PdaClientState.messageOk());
        Signals.bool(APP, "msg_bad", app -> !PdaClientState.message().isEmpty() && !PdaClientState.messageOk());
        for (var page : pages) page.signals();
    }

    private static int index(List<PdaClientPage> pages) {
        var open = PdaClientState.page();
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).id().equals(open)) return i;
        return 0;
    }

    private static String title(List<PdaClientPage> pages) { return pages.get(index(pages)).title(); }

    @Override
    public ResourceLocation id() { return ID; }

    @Override
    public Component title() { return Component.literal("PLUGINLESS"); }

    @Override
    public boolean visible(LocalPlayer player) { return PdaClientSetup.linked(); }

    @Override
    public int badge() { return (int) PdaClientState.number(PdaClientState.view("home"), "unread"); }

    @Override
    public void opened() {
        PdaClientState.reset();
        PdaClientSetup.open("home");
    }

    @Override
    public void closed() { PdaClientSetup.close(); }

    @Override
    public WidgetDef build() {
        var pages = PdaClientSetup.pages();
        var tree = new ArrayList<WidgetDef>();
        tree.add(rect(PdaClientSetup.AREA_WIDTH, TOP, PdaShell.BAR_BACK));
        tree.add(at("0", Float.toString(TOP - 1), rect(PdaClientSetup.AREA_WIDTH, 1, PdaShell.EDGE)));
        tree.add(when("pda_pl.sub", stack(
                at("2", "2", button(PdaShell.PANEL, PdaShell.HOVER, 4, item -> PdaClientSetup.open("home"),
                        text("< BACK", PdaShell.TEXT))),
                at("56", "5", fit("{pda_pl.title}", PdaShell.ACCENT, 200)))));
        tree.add(place(Float.toString(PdaClientSetup.AREA_WIDTH - 8), "5", Point.TOP_RIGHT,
                text("{pda_pl.balance}", PdaShell.ACCENT)));
        tree.add(when("pda_pl.msg_ok", at("8", Float.toString(TOP + PdaClientSetup.AREA_HEIGHT - 20), wrap("{pda_pl.msg}", PdaShell.ACCENT, 368, 2))));
        tree.add(when("pda_pl.msg_bad", at("8", Float.toString(TOP + PdaClientSetup.AREA_HEIGHT - 20), wrap("{pda_pl.msg}", PdaShell.ALERT, 368, 2))));
        for (int i = 0; i < pages.size(); i++) {
            tree.add(when("pda_pl.page == " + i, at("0", Float.toString(TOP), pages.get(i).build())));
        }
        tree.add(new TextTip());
        return stack(tree.toArray(WidgetDef[]::new));
    }
}
