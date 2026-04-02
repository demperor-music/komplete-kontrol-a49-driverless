package dev.demperor.bitwig.a49;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes raw HID input reports from the A49 into button events and encoder
 * deltas.
 *
 * <p>The keyboard reports the state of all buttons in every input report, so
 * the decoder keeps the previous state and emits press/release events for the
 * difference. Shift combinations are resolved into named actions.</p>
 */
public final class A49Decoder
{
    private final A49ComboMode comboMode;
    private Set<A49Action> prevLogicalActions = EnumSet.noneOf(A49Action.class);
    private Set<A49Action> lastPhysicalButtons = EnumSet.noneOf(A49Action.class);
    private Integer previousEncoderValue;

    public A49Decoder(final A49ComboMode comboMode)
    {
        this.comboMode = comboMode;
    }

    public DecodedReport decode(final byte[] report)
    {
        if (report == null || report.length == 0 || report[0] != A49Mappings.REPORT_ID_INPUT)
            return new DecodedReport(Collections.<A49HidEvent>emptyList(), 0, this.lastPhysicalButtons,
                (byte) A49Mappings.INPUT_OCTAVE_STATE_BASE);

        final Set<A49Action> physical = this.extractPhysicalButtons(report);
        this.lastPhysicalButtons = EnumSet.copyOf(physical);

        final Set<A49Action> logical = this.deriveLogicalActions(physical);
        final List<A49HidEvent> events = new ArrayList<A49HidEvent>();

        for (final A49Action action : sort(difference(this.prevLogicalActions, logical)))
            events.add(A49HidEvent.released(action));
        for (final A49Action action : sort(difference(logical, this.prevLogicalActions)))
            events.add(A49HidEvent.pressed(action));

        this.prevLogicalActions = EnumSet.copyOf(logical);

        return new DecodedReport(events, this.decodeEncoderDelta(report), physical, this.extractOctaveIndicator(report));
    }

    private Set<A49Action> extractPhysicalButtons(final byte[] report)
    {
        final Set<A49Action> pressed = EnumSet.noneOf(A49Action.class);
        for (final A49Mappings.ButtonBit buttonBit : A49Mappings.PHYSICAL_BITS)
        {
            final int index = buttonBit.getByteIndex();
            if (report.length > index && (((report[index] & 0xFF) & buttonBit.getMask()) != 0))
                pressed.add(buttonBit.getAction());
        }
        return pressed;
    }

    private Set<A49Action> deriveLogicalActions(final Set<A49Action> physical)
    {
        if (physical.isEmpty())
            return EnumSet.noneOf(A49Action.class);

        if (this.comboMode == A49ComboMode.PHYSICAL)
            return EnumSet.copyOf(physical);

        final Set<A49Action> logical = EnumSet.copyOf(physical);
        if (physical.contains(A49Action.SHIFT))
        {
            for (final Map.Entry<A49Action, A49Action> entry : A49Mappings.SHIFT_COMBOS.entrySet())
            {
                if (physical.contains(entry.getKey()))
                {
                    logical.remove(A49Action.SHIFT);
                    logical.remove(entry.getKey());
                    logical.add(entry.getValue());
                }
            }
        }

        if (this.comboMode == A49ComboMode.BOTH)
            logical.addAll(physical);
        return logical;
    }

    private int decodeEncoderDelta(final byte[] report)
    {
        if (report.length <= A49Mappings.INPUT_ENCODER_INDEX)
            return 0;

        final int current = report[A49Mappings.INPUT_ENCODER_INDEX] & 0xFF;
        if (this.previousEncoderValue == null)
        {
            this.previousEncoderValue = Integer.valueOf(current);
            return 0;
        }
        else if (this.previousEncoderValue.intValue() == current)
        {
            return 0;
        }

        final int up = (this.previousEncoderValue.intValue() + 1) & 0x0F;
        this.previousEncoderValue = Integer.valueOf(current);
        return up == current ? -1 : 1;
    }

    private byte extractOctaveIndicator(final byte[] report)
    {
        if (report == null || report.length <= 1)
            return (byte) A49Mappings.INPUT_OCTAVE_STATE_BASE;
        return report[report.length - 1];
    }

    private static Set<A49Action> difference(final Set<A49Action> left, final Set<A49Action> right)
    {
        final Set<A49Action> diff = EnumSet.noneOf(A49Action.class);
        diff.addAll(left);
        diff.removeAll(right);
        return diff;
    }

    private static List<A49Action> sort(final Set<A49Action> values)
    {
        final List<A49Action> sorted = new ArrayList<A49Action>(values);
        Collections.sort(sorted);
        return sorted;
    }

    public static final class DecodedReport
    {
        private final List<A49HidEvent> events;
        private final int encoderDelta;
        private final Set<A49Action> physicalButtons;
        private final byte octaveIndicator;

        public DecodedReport(final List<A49HidEvent> events,
                             final int encoderDelta,
                             final Set<A49Action> physicalButtons,
                             final byte octaveIndicator)
        {
            this.events = events;
            this.encoderDelta = encoderDelta;
            this.physicalButtons = EnumSet.copyOf(physicalButtons);
            this.octaveIndicator = octaveIndicator;
        }

        public List<A49HidEvent> getEvents()
        {
            return this.events;
        }

        public int getEncoderDelta()
        {
            return this.encoderDelta;
        }

        public Set<A49Action> getPhysicalButtons()
        {
            return EnumSet.copyOf(this.physicalButtons);
        }

        public byte getOctaveIndicator()
        {
            return this.octaveIndicator;
        }
    }
}
