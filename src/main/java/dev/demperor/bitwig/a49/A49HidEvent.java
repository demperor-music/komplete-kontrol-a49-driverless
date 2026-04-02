package dev.demperor.bitwig.a49;

/**
 * A single event produced by {@link A49Decoder}: a button press, a button
 * release, or an encoder step.
 */
public final class A49HidEvent
{
    public enum Type
    {
        ACTION_PRESSED,
        ACTION_RELEASED,
        ENCODER_DELTA
    }

    private final Type type;
    private final A49Action action;
    private final int encoderDelta;

    private A49HidEvent(final Type type, final A49Action action, final int encoderDelta)
    {
        this.type = type;
        this.action = action;
        this.encoderDelta = encoderDelta;
    }

    public static A49HidEvent pressed(final A49Action action)
    {
        return new A49HidEvent(Type.ACTION_PRESSED, action, 0);
    }

    public static A49HidEvent released(final A49Action action)
    {
        return new A49HidEvent(Type.ACTION_RELEASED, action, 0);
    }

    public static A49HidEvent encoderDelta(final int delta)
    {
        return new A49HidEvent(Type.ENCODER_DELTA, null, delta);
    }

    public Type getType()
    {
        return this.type;
    }

    public A49Action getAction()
    {
        return this.action;
    }

    public int getEncoderDelta()
    {
        return this.encoderDelta;
    }
}
