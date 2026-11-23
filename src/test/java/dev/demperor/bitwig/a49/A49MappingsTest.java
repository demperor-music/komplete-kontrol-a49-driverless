package dev.demperor.bitwig.a49;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class A49MappingsTest
{
    @Test
    void actionCcsAreUnique()
    {
        final Set<Integer> seen = new HashSet<Integer>();
        for (final Integer cc : A49Mappings.DEFAULT_CC_BY_ACTION.values())
            assertTrue(seen.add(cc), "duplicate action CC " + cc);
    }

    @Test
    void actionCcsDoNotOverlapKnobPages()
    {
        final Set<Integer> knobCcs = new HashSet<Integer>();
        for (final int[] page : A49Mappings.KNOB_CC_PAGES)
        {
            for (final int cc : page)
                assertTrue(knobCcs.add(cc), "duplicate knob CC " + cc);
        }

        for (final Integer cc : A49Mappings.DEFAULT_CC_BY_ACTION.values())
            assertFalse(knobCcs.contains(cc), "action CC " + cc + " collides with a knob CC");
    }

    @Test
    void physicalButtonBitsAreUnique()
    {
        final Set<String> seen = new HashSet<String>();
        for (final A49Mappings.ButtonBit bit : A49Mappings.PHYSICAL_BITS)
            assertTrue(seen.add(bit.getByteIndex() + ":" + bit.getMask()), "duplicate button bit");
    }

    @Test
    void ledIndicesStayInsideThePayload()
    {
        for (final Integer index : A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.values())
        {
            assertTrue(index.intValue() >= 0, "negative LED index");
            assertTrue(index.intValue() < A49Mappings.LED_PAYLOAD_LENGTH, "LED index outside payload");
        }

        for (final Integer index : A49Mappings.SHIFT_LAYER_LED_INDICES)
            assertTrue(A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.containsValue(index), "unknown shift LED index");
    }
}
