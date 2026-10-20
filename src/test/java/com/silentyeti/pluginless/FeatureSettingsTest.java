package com.silentyeti.pluginless;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatureSettingsTest {
    @Test
    void defaultsAreValidAndBrokenCollectionsCannotBeApplied() {
        var settings = new FeatureSettings();
        assertNull(settings.problem());
        settings.commandCosts.put("rtp", -1.0); assertNotNull(settings.problem());
        settings.commandCosts.put("rtp", Double.NaN); assertNotNull(settings.problem());
        settings.commandCosts.put("rtp", 50.0); assertNull(settings.problem());
        settings.rtpDimensions = List.of("not a dimension"); assertNotNull(settings.problem());
        settings.rtpDimensions = List.of("minecraft:overworld");
        settings.titles.put("off", "ambiguous"); assertNotNull(settings.problem());
        settings.titles.remove("off"); settings.blockedWords = null; assertNotNull(settings.problem());
    }
}
