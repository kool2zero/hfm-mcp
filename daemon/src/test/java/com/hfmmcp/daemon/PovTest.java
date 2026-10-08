package com.hfmmcp.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hfmmcp.daemon.pov.Pov;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PovTest {
    @Test
    void parsesAndFormatsRoundTrip() {
        String s = "S#Actual.Y#2025.P#Dec.W#YTD.V#<Entity Currency>.E#Group.UK.A#Sales.I#[ICP None].C1#[None]";
        Map<String, String> p = Pov.parse(s);
        assertEquals("Group.UK", p.get("E"), "parent-qualified entity keeps its dot");
        assertEquals("<Entity Currency>", p.get("V"));
        assertEquals("[ICP None]", p.get("I"));
        assertEquals(s, Pov.format(p));
    }

    @Test
    void rejectsNonPov() {
        assertThrows(IllegalArgumentException.class, () -> Pov.parse("Actual.2025"));
    }
}
