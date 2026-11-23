package dev.demperor.bitwig.a49;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class A49DecoderTest
{
    private static final int REPORT_LENGTH = 30;

    @Test
    void firstEncoderReportOnlyInitializesTheCounter()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.NAMED);
        assertEquals(0, decoder.decode(idleReport(4)).getEncoderDelta());
    }

    @Test
    void encoderHandlesSingleStepsInBothDirections()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.NAMED);
        decoder.decode(idleReport(0));
        assertEquals(1, decoder.decode(idleReport(15)).getEncoderDelta());
        assertEquals(-1, decoder.decode(idleReport(0)).getEncoderDelta());
    }

    @Test
    void encoderHandlesMultiStepSpins()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.NAMED);
        decoder.decode(idleReport(0));
        assertEquals(-3, decoder.decode(idleReport(3)).getEncoderDelta());
        assertEquals(6, decoder.decode(idleReport(13)).getEncoderDelta());
    }

    @Test
    void physicalButtonPressAndReleaseProduceEvents()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.NAMED);

        final A49Decoder.DecodedReport pressed = decoder.decode(report(0, 0x24, 0, 0x02, 0, 0));
        assertEquals(1, pressed.getEvents().size());
        assertEquals(A49HidEvent.Type.ACTION_PRESSED, pressed.getEvents().get(0).getType());
        assertEquals(A49Action.PLAY, pressed.getEvents().get(0).getAction());
        assertTrue(pressed.getPhysicalButtons().contains(A49Action.PLAY));

        final A49Decoder.DecodedReport released = decoder.decode(idleReport(0));
        assertEquals(1, released.getEvents().size());
        assertEquals(A49HidEvent.Type.ACTION_RELEASED, released.getEvents().get(0).getType());
        assertEquals(A49Action.PLAY, released.getEvents().get(0).getAction());
        assertTrue(released.getPhysicalButtons().isEmpty());
    }

    @Test
    void namedModeTurnsShiftPlayIntoRestart()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.NAMED);
        final A49Decoder.DecodedReport decoded = decoder.decode(report(0, 0x24, 0x01, 0x02, 0, 0));

        final List<A49HidEvent> events = decoded.getEvents();
        assertEquals(1, events.size());
        assertEquals(A49Action.RESTART, events.get(0).getAction());
        assertEquals(A49HidEvent.Type.ACTION_PRESSED, events.get(0).getType());
    }

    @Test
    void physicalModeKeepsShiftCombinationsSeparate()
    {
        final A49Decoder decoder = new A49Decoder(A49ComboMode.PHYSICAL);
        final List<A49HidEvent> events = decoder.decode(report(0, 0x24, 0x01, 0x02, 0, 0)).getEvents();

        final EnumSet<A49Action> pressed = EnumSet.noneOf(A49Action.class);
        for (final A49HidEvent event : events)
        {
            assertEquals(A49HidEvent.Type.ACTION_PRESSED, event.getType());
            pressed.add(event.getAction());
        }
        assertTrue(pressed.contains(A49Action.SHIFT));
        assertTrue(pressed.contains(A49Action.PLAY));
        assertFalse(pressed.contains(A49Action.RESTART));
    }

    private static byte[] idleReport(final int encoderValue)
    {
        return report(encoderValue, A49Mappings.INPUT_OCTAVE_STATE_BASE, 0, 0, 0, 0);
    }

    private static byte[] report(final int encoderValue, final int octaveIndicator,
                                 final int byte1, final int byte2, final int byte3, final int byte5)
    {
        final byte[] report = new byte[REPORT_LENGTH];
        report[0] = A49Mappings.REPORT_ID_INPUT;
        report[1] = (byte) byte1;
        report[2] = (byte) byte2;
        report[3] = (byte) byte3;
        report[5] = (byte) byte5;
        report[A49Mappings.INPUT_ENCODER_INDEX] = (byte) encoderValue;
        report[report.length - 1] = (byte) octaveIndicator;
        return report;
    }
}
