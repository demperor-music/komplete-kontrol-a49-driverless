package dev.demperor.bitwig.a49;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class A49LedStateTest
{
    private final A49LedState ledState = new A49LedState();

    @Test
    void payloadHasTheExpectedLength()
    {
        assertEquals(A49Mappings.LED_PAYLOAD_LENGTH,
            this.ledState.buildPayload(EnumSet.noneOf(A49Action.class), EnumSet.noneOf(A49Action.class)).length);
    }

    @Test
    void heldAndLatchedButtonsAreBright()
    {
        final byte[] payload = this.ledState.buildPayload(
            EnumSet.of(A49Action.PLAY), EnumSet.of(A49Action.MUTE));

        assertEquals(A49Mappings.LED_BRIGHT, payload[index(A49Action.PLAY)]);
        assertEquals(A49Mappings.LED_BRIGHT, payload[index(A49Action.MUTE)]);
        assertEquals(A49Mappings.LED_DIM, payload[index(A49Action.STOP)]);
    }

    @Test
    void shiftHidesLedsWithoutAShiftedFunction()
    {
        final Set<A49Action> physical = EnumSet.of(A49Action.SHIFT, A49Action.LOOP);
        final byte[] payload = this.ledState.buildPayload(physical, EnumSet.noneOf(A49Action.class));

        assertEquals(A49Mappings.LED_BRIGHT, payload[index(A49Action.SHIFT)]);
        assertEquals(A49Mappings.LED_OFF, payload[index(A49Action.LOOP)]);
    }

    private static int index(final A49Action action)
    {
        return A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.get(action).intValue();
    }
}
