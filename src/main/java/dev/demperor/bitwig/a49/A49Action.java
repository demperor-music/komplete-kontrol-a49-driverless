package dev.demperor.bitwig.a49;

/**
 * Logical actions produced by the keyboard.
 *
 * <p>The first group is reported directly by the hardware. The second group
 * is only produced by Shift combinations; see
 * {@link A49Mappings#SHIFT_COMBOS}.</p>
 */
public enum A49Action
{
    SHIFT,
    SCALE,
    ARP,
    UNDO,
    QUANTIZE,
    IDEAS,
    LOOP,
    METRONOME,
    TEMPO,
    PLAY,
    RECORD,
    STOP,
    PRESET_UP,
    PRESET_DOWN,
    LEFT,
    RIGHT,
    BROWSER,
    PLUG_IN,
    TRACK,
    BROWSER_KNOB_CLICK,

    RESTART,
    COUNT_IN,
    CLEAR,
    REDO,
    AUTO,
    EDIT_SCALE,
    EDIT_ARP,
    MIDI,
    INSTANCE,
    MUTE,
    SOLO
}
