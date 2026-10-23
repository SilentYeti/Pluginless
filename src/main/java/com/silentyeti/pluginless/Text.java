package com.silentyeti.pluginless;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** The single Minecraft adapter for configured colours and gradients. */
public final class Text {
    public static MutableComponent format(String text) {
        var result = Component.empty();
        for (var span : Formatting.parse(text)) {
            var style = Style.EMPTY.withObfuscated((span.flags() & 1) != 0).withBold((span.flags() & 2) != 0)
                    .withStrikethrough((span.flags() & 4) != 0).withUnderlined((span.flags() & 8) != 0)
                    .withItalic((span.flags() & 16) != 0);
            if (span.color() != null) style = style.withColor(span.color());
            if (!span.text().isEmpty()) result.append(Component.literal(span.text()).setStyle(style));
        }
        return result;
    }
}
