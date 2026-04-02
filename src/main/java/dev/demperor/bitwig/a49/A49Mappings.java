package dev.demperor.bitwig.a49;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Protocol constants and button/LED maps for the Komplete Kontrol A49.
 *
 * <p>The values in this class were derived from USB HID captures of the
 * keyboard. They are not part of an official Native Instruments API.</p>
 */
public final class A49Mappings
{
    /** Native Instruments USB vendor and product IDs for the A49. */
    public static final short VENDOR_ID = (short) 0x17CC;
    public static final short PRODUCT_ID = (short) 0x1740;

    public static final String MANUFACTURER_CONTAINS = "Native Instruments";
    public static final String PRODUCT_CONTAINS = "KOMPLETE KONTROL A49";

    /** HID report IDs used by the keyboard. */
    public static final byte REPORT_ID_INPUT = (byte) 0x01;
    public static final byte REPORT_ID_LED = (byte) 0x80;
    public static final byte REPORT_ID_DISPLAY = (byte) 0xE0;
    public static final byte REPORT_ID_FEATURE_F8 = (byte) 0xF8;
    public static final byte REPORT_ID_FEATURE_D0 = (byte) 0xD0;

    /** The OLED is 128x32 pixels, stored as four 8-pixel-high pages. */
    public static final int DISPLAY_WIDTH = 128;
    public static final int DISPLAY_HEIGHT = 32;
    public static final int DISPLAY_PAGE_HEIGHT = 8;
    public static final int DISPLAY_PAGE_COUNT = DISPLAY_HEIGHT / DISPLAY_PAGE_HEIGHT;
    public static final int DISPLAY_FRAMEBUFFER_LENGTH = DISPLAY_WIDTH * DISPLAY_PAGE_COUNT;
    public static final int DISPLAY_CHUNK_DATA_LENGTH = DISPLAY_WIDTH * 2;
    public static final int DISPLAY_PACKET_DATA_OFFSET = 9;
    public static final int DISPLAY_PACKET_TOTAL_LENGTH = DISPLAY_PACKET_DATA_OFFSET + DISPLAY_CHUNK_DATA_LENGTH;

    /** Byte offsets in the raw input report. */
    public static final int INPUT_ENCODER_INDEX = 28;
    public static final int INPUT_OCTAVE_STATE_BASE = 0x24;
    public static final int INPUT_OCTAVE_STATE_STEP = 0x0C;

    /** LED output report layout. */
    public static final int LED_REPORT_TOTAL_LENGTH = 91;
    public static final int LED_PAYLOAD_LENGTH = LED_REPORT_TOTAL_LENGTH - 1;
    public static final int LED_FIRMWARE_MANAGED_START_INDEX = 19;
    public static final int LED_FIRMWARE_MANAGED_END_INDEX = 20;
    public static final byte LED_FIRMWARE_MANAGED_DEFAULT = (byte) 0x20;
    public static final byte LED_OFF = (byte) 0x00;
    public static final byte LED_DIM = (byte) 0x20;
    public static final byte LED_BRIGHT = (byte) 0xFF;

    /** MIDI used for the extra control-change messages injected by the extension. */
    public static final int MIDI_CHANNEL = 0;
    public static final int MIDI_STATUS_CC = 0xB0;
    public static final int MIDI_VALUE_ON = 127;
    public static final int MIDI_VALUE_OFF = 0;

    public static final A49ComboMode DEFAULT_COMBO_MODE = A49ComboMode.NAMED;

    /** Bit positions of the physical buttons in the raw input report. */
    public static final ButtonBit[] PHYSICAL_BITS = new ButtonBit[] {
        new ButtonBit(A49Action.SHIFT, 1, 0x01),
        new ButtonBit(A49Action.SCALE, 1, 0x02),
        new ButtonBit(A49Action.ARP, 1, 0x04),
        new ButtonBit(A49Action.UNDO, 1, 0x08),
        new ButtonBit(A49Action.QUANTIZE, 1, 0x10),
        new ButtonBit(A49Action.IDEAS, 1, 0x20),
        new ButtonBit(A49Action.LOOP, 1, 0x40),
        new ButtonBit(A49Action.METRONOME, 1, 0x80),

        new ButtonBit(A49Action.TEMPO, 2, 0x01),
        new ButtonBit(A49Action.PLAY, 2, 0x02),
        new ButtonBit(A49Action.RECORD, 2, 0x04),
        new ButtonBit(A49Action.STOP, 2, 0x08),
        new ButtonBit(A49Action.PRESET_UP, 2, 0x10),
        new ButtonBit(A49Action.PRESET_DOWN, 2, 0x20),
        new ButtonBit(A49Action.LEFT, 2, 0x40),
        new ButtonBit(A49Action.RIGHT, 2, 0x80),

        new ButtonBit(A49Action.BROWSER, 3, 0x01),
        new ButtonBit(A49Action.PLUG_IN, 3, 0x02),
        new ButtonBit(A49Action.TRACK, 3, 0x04),

        new ButtonBit(A49Action.BROWSER_KNOB_CLICK, 5, 0x02)
    };

    /** Shift+button combinations mapped to their logical action. */
    public static final Map<A49Action, A49Action> SHIFT_COMBOS;
    /** MIDI CC number injected for each logical action. */
    public static final Map<A49Action, Integer> DEFAULT_CC_BY_ACTION;
    /** Index into the LED output payload for each logical action. */
    public static final Map<A49Action, Integer> LED_PAYLOAD_INDEX_BY_ACTION;
    /** LEDs that stay visible while Shift is held. */
    public static final Set<Integer> SHIFT_LAYER_LED_INDICES;

    static
    {
        final Map<A49Action, A49Action> combos = new EnumMap<A49Action, A49Action>(A49Action.class);
        combos.put(A49Action.PLAY, A49Action.RESTART);
        combos.put(A49Action.RECORD, A49Action.COUNT_IN);
        combos.put(A49Action.STOP, A49Action.CLEAR);
        combos.put(A49Action.UNDO, A49Action.REDO);
        combos.put(A49Action.QUANTIZE, A49Action.AUTO);
        combos.put(A49Action.SCALE, A49Action.EDIT_SCALE);
        combos.put(A49Action.ARP, A49Action.EDIT_ARP);
        combos.put(A49Action.PLUG_IN, A49Action.MIDI);
        combos.put(A49Action.TRACK, A49Action.INSTANCE);
        combos.put(A49Action.LEFT, A49Action.MUTE);
        combos.put(A49Action.RIGHT, A49Action.SOLO);
        SHIFT_COMBOS = Collections.unmodifiableMap(combos);

        final Map<A49Action, Integer> cc = new EnumMap<A49Action, Integer>(A49Action.class);
        cc.put(A49Action.PLAY, Integer.valueOf(29));
        cc.put(A49Action.RECORD, Integer.valueOf(30));
        cc.put(A49Action.STOP, Integer.valueOf(31));
        cc.put(A49Action.LOOP, Integer.valueOf(32));
        cc.put(A49Action.METRONOME, Integer.valueOf(33));
        cc.put(A49Action.TEMPO, Integer.valueOf(34));
        cc.put(A49Action.PRESET_UP, Integer.valueOf(35));
        cc.put(A49Action.PRESET_DOWN, Integer.valueOf(36));
        cc.put(A49Action.IDEAS, Integer.valueOf(37));
        cc.put(A49Action.QUANTIZE, Integer.valueOf(38));
        cc.put(A49Action.UNDO, Integer.valueOf(39));
        cc.put(A49Action.ARP, Integer.valueOf(40));
        cc.put(A49Action.SCALE, Integer.valueOf(41));
        cc.put(A49Action.SHIFT, Integer.valueOf(42));
        cc.put(A49Action.RESTART, Integer.valueOf(43));
        cc.put(A49Action.COUNT_IN, Integer.valueOf(44));
        cc.put(A49Action.CLEAR, Integer.valueOf(45));
        cc.put(A49Action.REDO, Integer.valueOf(46));
        cc.put(A49Action.AUTO, Integer.valueOf(47));
        cc.put(A49Action.EDIT_SCALE, Integer.valueOf(48));
        cc.put(A49Action.EDIT_ARP, Integer.valueOf(49));
        cc.put(A49Action.BROWSER, Integer.valueOf(50));
        cc.put(A49Action.PLUG_IN, Integer.valueOf(51));
        cc.put(A49Action.MIDI, Integer.valueOf(52));
        cc.put(A49Action.TRACK, Integer.valueOf(53));
        cc.put(A49Action.INSTANCE, Integer.valueOf(54));
        cc.put(A49Action.BROWSER_KNOB_CLICK, Integer.valueOf(55));
        cc.put(A49Action.LEFT, Integer.valueOf(56));
        cc.put(A49Action.RIGHT, Integer.valueOf(57));
        cc.put(A49Action.MUTE, Integer.valueOf(58));
        cc.put(A49Action.SOLO, Integer.valueOf(59));
        DEFAULT_CC_BY_ACTION = Collections.unmodifiableMap(cc);

        final Map<A49Action, Integer> led = new EnumMap<A49Action, Integer>(A49Action.class);
        led.put(A49Action.SHIFT, Integer.valueOf(0));
        led.put(A49Action.SCALE, Integer.valueOf(1));
        led.put(A49Action.EDIT_SCALE, Integer.valueOf(1));
        led.put(A49Action.ARP, Integer.valueOf(2));
        led.put(A49Action.EDIT_ARP, Integer.valueOf(2));
        led.put(A49Action.UNDO, Integer.valueOf(3));
        led.put(A49Action.REDO, Integer.valueOf(3));
        led.put(A49Action.QUANTIZE, Integer.valueOf(4));
        led.put(A49Action.AUTO, Integer.valueOf(4));
        led.put(A49Action.IDEAS, Integer.valueOf(5));
        led.put(A49Action.LOOP, Integer.valueOf(6));
        led.put(A49Action.METRONOME, Integer.valueOf(7));
        led.put(A49Action.TEMPO, Integer.valueOf(8));
        led.put(A49Action.PLAY, Integer.valueOf(9));
        led.put(A49Action.RESTART, Integer.valueOf(9));
        led.put(A49Action.RECORD, Integer.valueOf(10));
        led.put(A49Action.COUNT_IN, Integer.valueOf(10));
        led.put(A49Action.STOP, Integer.valueOf(11));
        led.put(A49Action.CLEAR, Integer.valueOf(11));
        led.put(A49Action.PRESET_UP, Integer.valueOf(12));
        led.put(A49Action.PRESET_DOWN, Integer.valueOf(13));
        led.put(A49Action.LEFT, Integer.valueOf(14));
        led.put(A49Action.MUTE, Integer.valueOf(14));
        led.put(A49Action.RIGHT, Integer.valueOf(15));
        led.put(A49Action.SOLO, Integer.valueOf(15));
        led.put(A49Action.BROWSER, Integer.valueOf(16));
        led.put(A49Action.PLUG_IN, Integer.valueOf(17));
        led.put(A49Action.MIDI, Integer.valueOf(17));
        led.put(A49Action.TRACK, Integer.valueOf(18));
        led.put(A49Action.INSTANCE, Integer.valueOf(18));
        LED_PAYLOAD_INDEX_BY_ACTION = Collections.unmodifiableMap(led);

        final LinkedHashSet<Integer> shiftLeds = new LinkedHashSet<Integer>();
        shiftLeds.add(Integer.valueOf(0));
        shiftLeds.add(Integer.valueOf(1));
        shiftLeds.add(Integer.valueOf(2));
        shiftLeds.add(Integer.valueOf(3));
        shiftLeds.add(Integer.valueOf(4));
        shiftLeds.add(Integer.valueOf(9));
        shiftLeds.add(Integer.valueOf(10));
        shiftLeds.add(Integer.valueOf(11));
        shiftLeds.add(Integer.valueOf(14));
        shiftLeds.add(Integer.valueOf(15));
        shiftLeds.add(Integer.valueOf(17));
        shiftLeds.add(Integer.valueOf(18));
        SHIFT_LAYER_LED_INDICES = Collections.unmodifiableSet(shiftLeds);
    }

    private A49Mappings()
    {
    }

    public static final class ButtonBit
    {
        private final A49Action action;
        private final int byteIndex;
        private final int mask;

        public ButtonBit(final A49Action action, final int byteIndex, final int mask)
        {
            this.action = action;
            this.byteIndex = byteIndex;
            this.mask = mask;
        }

        public A49Action getAction()
        {
            return this.action;
        }

        public int getByteIndex()
        {
            return this.byteIndex;
        }

        public int getMask()
        {
            return this.mask;
        }
    }
}
