package dev.demperor.bitwig.a49;

import java.util.EnumSet;
import java.util.Set;

/**
 * Builds the 90-byte LED output payload.
 *
 * <p>Known buttons are dim by default. Buttons that are physically held or
 * latched by Bitwig are bright. While Shift is held, only buttons that have a
 * shifted function stay lit.</p>
 */
public final class A49LedState
{
    public byte[] buildPayload(final Set<A49Action> physicalButtons, final Set<A49Action> latchedButtons)
    {
        final byte[] payload = new byte[A49Mappings.LED_PAYLOAD_LENGTH];

        for (final Integer index : A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.values())
            payload[index.intValue()] = A49Mappings.LED_DIM;

        payload[A49Mappings.LED_FIRMWARE_MANAGED_START_INDEX] = A49Mappings.LED_FIRMWARE_MANAGED_DEFAULT;
        payload[A49Mappings.LED_FIRMWARE_MANAGED_END_INDEX] = A49Mappings.LED_FIRMWARE_MANAGED_DEFAULT;

        final Set<A49Action> safePhysical = physicalButtons == null || physicalButtons.isEmpty()
            ? EnumSet.noneOf(A49Action.class)
            : EnumSet.copyOf(physicalButtons);
        final Set<A49Action> safeLatched = latchedButtons == null || latchedButtons.isEmpty()
            ? EnumSet.noneOf(A49Action.class)
            : EnumSet.copyOf(latchedButtons);

        final boolean shiftHeld = safePhysical.contains(A49Action.SHIFT);
        if (shiftHeld)
        {
            for (final Integer index : A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.values())
            {
                if (!A49Mappings.SHIFT_LAYER_LED_INDICES.contains(index))
                    payload[index.intValue()] = A49Mappings.LED_OFF;
            }
        }

        for (final A49Action action : safeLatched)
        {
            final Integer index = A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.get(action);
            if (index != null && (!shiftHeld || A49Mappings.SHIFT_LAYER_LED_INDICES.contains(index)))
                payload[index.intValue()] = A49Mappings.LED_BRIGHT;
        }

        for (final A49Action action : safePhysical)
        {
            final Integer index = A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION.get(action);
            if (index != null && (!shiftHeld || A49Mappings.SHIFT_LAYER_LED_INDICES.contains(index)))
                payload[index.intValue()] = A49Mappings.LED_BRIGHT;
        }

        return payload;
    }
}
