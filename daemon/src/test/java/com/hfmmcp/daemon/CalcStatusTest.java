package com.hfmmcp.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hfmmcp.daemon.service.CalcStatus;
import org.junit.jupiter.api.Test;

class CalcStatusTest {
    @Test
    void okWhenNoBits() {
        CalcStatus s = CalcStatus.decode(0);
        assertEquals("OK", s.code());
        assertFalse(s.stale());
        assertFalse(s.noData());
    }

    @Test
    void needsConsolidationWithNoData() {
        CalcStatus s = CalcStatus.decode(16777216L | 2L);
        assertEquals("CN", s.code());
        assertTrue(s.stale());
        assertTrue(s.noData());
    }

    @Test
    void signedIntErrorBit() {
        assertEquals("ERROR", CalcStatus.decode(Integer.MIN_VALUE).code());
    }

    @Test
    void consolidationOutranksCalculate() {
        assertEquals("CN", CalcStatus.decode(16777216L | 4194304L).code());
        assertEquals("CH", CalcStatus.decode(4194304L).code());
        assertEquals("CH", CalcStatus.decode(33554432L).code(), "value member needs calc counts as impacted");
        assertEquals("TR", CalcStatus.decode(8388608L).code());
        assertEquals("OK SC", CalcStatus.decode(2097152L).code());
    }
}
