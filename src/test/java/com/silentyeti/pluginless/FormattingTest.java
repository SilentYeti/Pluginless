package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormattingTest {
    private static String text(String input) {
        return Formatting.parse(input).stream().map(Formatting.Span::text).reduce("", String::concat);
    }

    @Test
    void legacyHexDecorationsAndReset() {
        var spans = Formatting.parse("&cR&#123456&lB&rZ").stream().filter(s -> !s.text().isEmpty()).toList();
        assertEquals(0xff5555, spans.get(0).color());
        assertEquals(0x123456, spans.get(1).color());
        assertEquals(2, spans.get(1).flags());
        assertNull(spans.get(2).color());
        assertEquals(0, spans.get(2).flags());
        assertEquals("&q &#xyz", text("&q &#xyz"));
    }

    @Test
    void gradientInterpolatesCodePointsAndLeavesSurroundingText() {
        var spans = Formatting.parse("<gradient:#ff0000:#0000ff>A😀B</gradient>!").stream().filter(s -> !s.text().isEmpty()).toList();
        assertEquals(0xff0000, spans.get(0).color());
        assertEquals(0x800080, spans.get(1).color());
        assertEquals("😀", spans.get(1).text());
        assertEquals(0x0000ff, spans.get(2).color());
        assertNull(spans.get(3).color());
        assertEquals("A😀B!", text("<gradient:#ff0000:#0000ff>A😀B</gradient>!"));
    }

    @Test
    void emptySingleAndMalformedGradientsAreSafe() {
        assertEquals("", text("<gradient:#ff0000:#0000ff></gradient>"));
        assertEquals(0xff0000, Formatting.parse("<gradient:#ff0000:#0000ff>X</gradient>").stream().filter(s -> !s.text().isEmpty()).findFirst().orElseThrow().color());
        assertEquals("<gradient:#bad:#0000ff>X</gradient>", text("<gradient:#bad:#0000ff>X</gradient>"));
        assertEquals("<gradient:#ff0000:#0000ff>name</gradient>", Formatting.colorize("<gradient:#ff0000:#0000ff>", "name"));
        assertEquals("&#123456name", Formatting.colorize("#123456", "name"));
    }
}
