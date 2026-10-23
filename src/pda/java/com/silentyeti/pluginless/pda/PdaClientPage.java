package com.silentyeti.pluginless.pda;

import com.wf.wflib.ui.WidgetDef;

/**
 * One page of the Pluginless PDA app on the client. A page registers its own signals in {@link #signals()} (names
 * prefixed {@code pda_pl_}), draws inside the area below the top bar, and sends intents with
 * {@link PdaClientSetup#send}. The app builds every page once, so pages register before the first draw.
 */
public interface PdaClientPage {
    /** Same id as the server {@code PdaPage}. */
    String id();

    String title();

    /** Called once at client setup. */
    void signals();

    /** The page's tree, {@link PdaClientSetup#AREA_WIDTH} x {@link PdaClientSetup#AREA_HEIGHT} units. */
    WidgetDef build();
}
