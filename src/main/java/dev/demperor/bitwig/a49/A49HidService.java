package dev.demperor.bitwig.a49;

import purejavahidapi.HidDevice;
import purejavahidapi.HidDeviceInfo;
import purejavahidapi.PureJavaHidApi;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the raw HID connection to the A49.
 *
 * <p>PureJavaHidApi is used instead of MIDI because the display, the LEDs and
 * several buttons are only exposed through HID reports. The class opens the
 * matching hidraw device, decodes input reports into {@link A49HidEvent}s and
 * writes LED and OLED output reports.</p>
 */
public final class A49HidService
{
    private static final byte[] DISPLAY_PREPARE_COMMAND = hex("a00700");
    private static final byte[] DISPLAY_ENABLE_COMMAND = hex("a00304");
    private static final byte[] DISPLAY_SHUTDOWN_FEATURE = hex("80002000010000006400");

    /**
     * LED frames replayed during display initialization. The A49 firmware
     * expects these packets before it accepts the first framebuffer.
     */
    private static final byte[][] DISPLAY_STARTUP_LED_SEQUENCE = new byte[][] {
        hex("807c00007c7c007c7c7c7c7c7c00007c7c00007c7c7c"),
        hex("807c00007c7c007c7c7c7c7c7c00007c7c00007c7c7c"),
        hex("807c00000000007c00007c7c7c00007c7c0000007c7c"),
        hex("807e00000000007c00007c7c7c00000000007e7c0000"),
        hex("807e00000000007c00007c7c7c00000000007e7c0000"),
        hex("807e00000000007c00007c7c7c00000000007e7c0000"),
        hex("807e000000000000000000000000000000007e7c0000"),
        hex("807c000000000000000000000000007c7c007e7c7c7c"),
        hex("807c000000000000000000000000007c7c007e7c7c7c"),
        hex("807c00000000007c00007c7c7c00007c7c0000007c7c")
    };
    private static final int DISPLAY_INTER_PACKET_DELAY_MS = 40;

    private static final int SCROLL_INTERVAL_MS = 80;
    private static final int SCROLL_STEP_PX = 2;
    private static final int SCROLL_INITIAL_PAUSE_TICKS = 8;
    private static final int SCROLL_EDGE_PAUSE_TICKS = 12;
    private static final int ANIMATION_POLL_MS = 20;

    private final ConcurrentLinkedQueue<A49HidEvent> eventQueue;
    private final A49Decoder decoder;
    private final A49LedState ledState;
    private final A49DisplayRenderer displayRenderer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private volatile HidDevice device;
    private volatile byte[] lastLedPayload;
    private volatile byte[] lastDisplayFramebuffer;
    private volatile boolean displayInitialized;
    private volatile Set<A49Action> currentPhysicalButtons = EnumSet.noneOf(A49Action.class);
    private volatile Set<A49Action> latchedActions = EnumSet.noneOf(A49Action.class);
    private volatile byte lastOctaveIndicator = (byte) A49Mappings.INPUT_OCTAVE_STATE_BASE;

    private volatile Thread displayAnimationThread;
    private volatile String currentMessageText;
    private volatile TrackFrame currentTrackFrame;
    private volatile int displayScrollOffset;
    private volatile int displayScrollDirection = 1;
    private volatile int displayScrollPauseTicks;
    private volatile long nextDisplayScrollTimeMs;

    public A49HidService(final ConcurrentLinkedQueue<A49HidEvent> eventQueue)
    {
        this.eventQueue = eventQueue;
        this.decoder = new A49Decoder(A49Mappings.DEFAULT_COMBO_MODE);
        this.ledState = new A49LedState();
        this.displayRenderer = new A49DisplayRenderer();
    }

    public void start() throws IOException
    {
        if (!this.running.compareAndSet(false, true))
            return;

        try
        {
            final HidDeviceInfo deviceInfo = this.findDevice();
            this.device = PureJavaHidApi.openDevice(deviceInfo);
            if (this.device == null)
                throw new IOException("PureJavaHidApi.openDevice returned null.");

            this.device.setDeviceRemovalListener(source -> this.running.set(false));
            this.device.setInputReportListener((source, reportId, data, length) -> {
                if (!this.running.get())
                    return;
                if (reportId != A49Mappings.REPORT_ID_INPUT)
                    return;

                final byte[] report = rebuildInputReport(reportId, data, length);
                final A49Decoder.DecodedReport decoded = this.decoder.decode(report);

                for (final A49HidEvent event : decoded.getEvents())
                    this.eventQueue.offer(event);

                if (decoded.getEncoderDelta() != 0)
                    this.eventQueue.offer(A49HidEvent.encoderDelta(decoded.getEncoderDelta()));

                final byte previousOctave = this.lastOctaveIndicator;
                this.currentPhysicalButtons = EnumSet.copyOf(decoded.getPhysicalButtons());
                this.lastOctaveIndicator = decoded.getOctaveIndicator();
                this.updateLeds(false);

                if (decoded.getOctaveIndicator() != previousOctave)
                    this.showOctaveOffset(this.toOctaveOffset(decoded.getOctaveIndicator()));
            });

            this.currentPhysicalButtons = EnumSet.noneOf(A49Action.class);
            this.latchedActions = EnumSet.noneOf(A49Action.class);
            this.lastOctaveIndicator = (byte) A49Mappings.INPUT_OCTAVE_STATE_BASE;
            this.updateLeds(true);
            this.startDisplayAnimationThread();
        }
        catch (final IOException | RuntimeException ex)
        {
            this.running.set(false);
            final HidDevice currentDevice = this.device;
            this.device = null;
            if (currentDevice != null)
            {
                try
                {
                    currentDevice.close();
                }
                catch (final RuntimeException ignored)
                {
                }
            }
            PureJavaHidApi.cleanup();
            throw ex;
        }
    }

    public void stop()
    {
        this.running.set(false);
        this.stopDisplayAnimationThread();
        this.currentMessageText = null;
        this.currentTrackFrame = null;
        this.displayInitialized = false;
        this.lastLedPayload = null;
        this.lastDisplayFramebuffer = null;
        final HidDevice currentDevice = this.device;
        this.device = null;

        if (currentDevice != null)
        {
            synchronized (this.writeLock)
            {
                try
                {
                    if (this.displayInitialized)
                        this.sendDisplayFramebufferLocked(this.displayRenderer.renderBlankFrame(), true);
                    this.sendFeatureReport(currentDevice, A49Mappings.REPORT_ID_FEATURE_F8, DISPLAY_SHUTDOWN_FEATURE);
                }
                catch (final IllegalStateException ignored)
                {
                }
            }
            currentDevice.close();
        }

        PureJavaHidApi.cleanup();
    }

    public void showText(final String text)
    {
        if (!this.running.get())
            return;

        synchronized (this.writeLock)
        {
            if (this.device == null)
                return;
            this.ensureDisplayInitializedLocked();
            if (!this.displayInitialized)
                return;
            this.currentMessageText = text;
            this.currentTrackFrame = null;
            this.resetDisplayScrollState();
            this.sendDisplayFramebufferLocked(this.displayRenderer.renderMessageFrame(text, 0), false);
        }
    }


    public void showTrackFrame(final String trackType, final String trackName, final boolean mute, final boolean solo, final String volumeText, final boolean arm)
    {
        if (!this.running.get())
            return;

        synchronized (this.writeLock)
        {
            if (this.device == null)
                return;
            this.ensureDisplayInitializedLocked();
            if (!this.displayInitialized)
                return;
            this.currentMessageText = null;
            this.currentTrackFrame = new TrackFrame(trackType, trackName, mute, solo, volumeText, arm);
            this.resetDisplayScrollState();
            this.sendDisplayFramebufferLocked(this.displayRenderer.renderTrackFrame(trackType, trackName, mute, solo, volumeText, arm, 0), false);
        }
    }

    public void setLatchedActions(final Set<A49Action> actions)
    {
        this.latchedActions = actions == null || actions.isEmpty()
            ? EnumSet.noneOf(A49Action.class)
            : EnumSet.copyOf(actions);
        this.updateLeds(false);
    }

    private void showOctaveOffset(final int octaveOffset)
    {
        if (!this.running.get())
            return;

        synchronized (this.writeLock)
        {
            if (this.device == null)
                return;
            this.ensureDisplayInitializedLocked();
            if (!this.displayInitialized)
                return;
            this.currentMessageText = null;
            this.currentTrackFrame = null;
            this.resetDisplayScrollState();
            this.sendDisplayFramebufferLocked(this.displayRenderer.renderOctaveFrame(octaveOffset), true);
        }
    }

    private void startDisplayAnimationThread()
    {
        final Thread thread = new Thread(this::displayAnimationLoop, "A49 display animation");
        thread.setDaemon(true);
        this.displayAnimationThread = thread;
        thread.start();
    }

    private void stopDisplayAnimationThread()
    {
        final Thread thread = this.displayAnimationThread;
        this.displayAnimationThread = null;
        if (thread == null)
            return;

        thread.interrupt();
        try
        {
            thread.join(500);
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void displayAnimationLoop()
    {
        while (this.running.get())
        {
            try
            {
                Thread.sleep(ANIMATION_POLL_MS);
            }
            catch (final InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                return;
            }

            if (!this.running.get())
                return;
            if (System.currentTimeMillis() < this.nextDisplayScrollTimeMs)
                continue;

            this.nextDisplayScrollTimeMs = System.currentTimeMillis() + SCROLL_INTERVAL_MS;
            this.advanceDisplayScroll();
        }
    }

    private void advanceDisplayScroll()
    {
        if (!this.running.get())
            return;

        synchronized (this.writeLock)
        {
            if (this.device == null || !this.displayInitialized)
                return;

            final String message = this.currentMessageText;
            final TrackFrame track = this.currentTrackFrame;
            if (message == null && track == null)
                return;

            final int maxOffset = message != null
                ? this.displayRenderer.messageScrollMaxOffset(message)
                : this.displayRenderer.trackNameScrollMaxOffset(track.name);
            if (maxOffset <= 0)
                return;

            if (this.displayScrollPauseTicks > 0)
            {
                this.displayScrollPauseTicks--;
                return;
            }

            // Ping-pong: hold briefly at each edge, then reverse direction
            // instead of snapping back to the start.
            final int nextOffset = this.displayScrollOffset + (this.displayScrollDirection * SCROLL_STEP_PX);
            if (nextOffset >= maxOffset)
            {
                this.displayScrollOffset = maxOffset;
                this.displayScrollDirection = -1;
                this.displayScrollPauseTicks = SCROLL_EDGE_PAUSE_TICKS;
            }
            else if (nextOffset <= 0)
            {
                this.displayScrollOffset = 0;
                this.displayScrollDirection = 1;
                this.displayScrollPauseTicks = SCROLL_EDGE_PAUSE_TICKS;
            }
            else
            {
                this.displayScrollOffset = nextOffset;
            }

            final byte[] framebuffer = message != null
                ? this.displayRenderer.renderMessageFrame(message, this.displayScrollOffset)
                : this.displayRenderer.renderTrackFrame(track.trackType, track.name, track.mute,
                    track.solo, track.volumeText, track.arm, this.displayScrollOffset);
            this.sendDisplayFramebufferLocked(framebuffer, false);
        }
    }

    private void resetDisplayScrollState()
    {
        this.displayScrollOffset = 0;
        this.displayScrollDirection = 1;
        this.displayScrollPauseTicks = SCROLL_INITIAL_PAUSE_TICKS;
        this.nextDisplayScrollTimeMs = System.currentTimeMillis() + SCROLL_INTERVAL_MS;
    }

    private void ensureDisplayInitializedLocked()
    {
        if (this.displayInitialized)
            return;

        final HidDevice currentDevice = this.device;
        if (currentDevice == null)
            return;

        this.primeDisplayFeatureReports(currentDevice);
        this.sendRawPacket(currentDevice, DISPLAY_PREPARE_COMMAND);
        sleepQuietly(8);
        this.replayDisplayStartupLedSeed(currentDevice);
        this.sendRawPacket(currentDevice, DISPLAY_ENABLE_COMMAND);
        sleepQuietly(120);

        final byte[] blankFramebuffer = this.displayRenderer.renderBlankFrame();
        this.sendDisplayFramebufferLocked(blankFramebuffer, true);
        if (this.lastDisplayFramebuffer != null)
        {
            this.displayInitialized = true;
            this.updateLeds(true);
        }
    }

    private void replayDisplayStartupLedSeed(final HidDevice hidDevice)
    {
        for (final byte[] packet : DISPLAY_STARTUP_LED_SEQUENCE)
        {
            this.sendRawPacket(hidDevice, packet);
            sleepQuietly(8);
        }
    }

    private void primeDisplayFeatureReports(final HidDevice hidDevice)
    {
        try
        {
            final byte[] f8 = new byte[11];
            f8[0] = A49Mappings.REPORT_ID_FEATURE_F8;
            hidDevice.getFeatureReport(f8, f8.length);

            final byte[] d0a = new byte[33];
            d0a[0] = A49Mappings.REPORT_ID_FEATURE_D0;
            hidDevice.getFeatureReport(d0a, d0a.length);

            final byte[] d0b = new byte[33];
            d0b[0] = A49Mappings.REPORT_ID_FEATURE_D0;
            hidDevice.getFeatureReport(d0b, d0b.length);
        }
        catch (final RuntimeException ignored)
        {
        }
    }

    private HidDeviceInfo findDevice() throws IOException
    {
        final List<HidDeviceInfo> devices = PureJavaHidApi.enumerateDevices();
        for (final HidDeviceInfo info : devices)
        {
            if (info.getVendorId() != A49Mappings.VENDOR_ID || info.getProductId() != A49Mappings.PRODUCT_ID)
                continue;
            if (!matchesText(info.getManufacturerString(), A49Mappings.MANUFACTURER_CONTAINS))
                continue;
            if (!matchesText(info.getProductString(), A49Mappings.PRODUCT_CONTAINS))
                continue;
            return info;
        }

        // Some platforms do not expose manufacturer/product strings. Fall back
        // to the USB IDs so the extension still works there.
        for (final HidDeviceInfo info : devices)
        {
            if (info.getVendorId() == A49Mappings.VENDOR_ID && info.getProductId() == A49Mappings.PRODUCT_ID)
                return info;
        }

        throw new IOException("Could not find the Komplete Kontrol A49 HID device via PureJavaHidApi.");
    }

    private void updateLeds(final boolean force)
    {
        final byte[] payload = this.ledState.buildPayload(this.currentPhysicalButtons, this.latchedActions);
        this.applyOctaveLedState(payload, this.lastOctaveIndicator);

        if (!force && Arrays.equals(payload, this.lastLedPayload))
            return;

        synchronized (this.writeLock)
        {
            final HidDevice currentDevice = this.device;
            if (currentDevice == null)
                return;

            if (this.sendLedPayload(currentDevice, payload) >= 0)
                this.lastLedPayload = Arrays.copyOf(payload, payload.length);
        }
    }

    private void applyOctaveLedState(final byte[] payload, final byte octaveIndicator)
    {
        if (payload == null || payload.length <= A49Mappings.LED_FIRMWARE_MANAGED_END_INDEX)
            return;

        payload[A49Mappings.LED_FIRMWARE_MANAGED_START_INDEX] = A49Mappings.LED_FIRMWARE_MANAGED_DEFAULT;
        payload[A49Mappings.LED_FIRMWARE_MANAGED_END_INDEX] = A49Mappings.LED_FIRMWARE_MANAGED_DEFAULT;

        final int octaveOffset = this.toOctaveOffset(octaveIndicator);
        if (octaveOffset < 0)
            payload[A49Mappings.LED_FIRMWARE_MANAGED_START_INDEX] = (byte) 0xFF;
        else if (octaveOffset > 0)
            payload[A49Mappings.LED_FIRMWARE_MANAGED_END_INDEX] = (byte) 0xFF;
    }

    private int toOctaveOffset(final byte octaveIndicator)
    {
        final int rawDelta = (octaveIndicator & 0xFF) - A49Mappings.INPUT_OCTAVE_STATE_BASE;
        final int step = Math.max(1, A49Mappings.INPUT_OCTAVE_STATE_STEP);
        return rawDelta / step;
    }

    private void sendDisplayFramebufferLocked(final byte[] framebuffer, final boolean force)
    {
        if (framebuffer == null || framebuffer.length != A49Mappings.DISPLAY_FRAMEBUFFER_LENGTH)
            return;
        if (!force && Arrays.equals(framebuffer, this.lastDisplayFramebuffer))
            return;

        final HidDevice currentDevice = this.device;
        if (currentDevice == null)
            return;

        final byte[] packetA = buildDisplayPacket((byte) 0x00, framebuffer, 0);
        final byte[] packetB = buildDisplayPacket((byte) 0x02, framebuffer, 2);
        if (this.sendDisplayFullPacket(currentDevice, packetA, packetB))
            this.lastDisplayFramebuffer = Arrays.copyOf(framebuffer, framebuffer.length);
    }

    private boolean sendDisplayFullPacket(final HidDevice hidDevice, final byte[] packetA, final byte[] packetB)
    {
        if (this.sendRawPacket(hidDevice, packetA) < 0)
            return false;
        sleepQuietly(DISPLAY_INTER_PACKET_DELAY_MS);
        return this.sendRawPacket(hidDevice, packetB) >= 0;
    }

    private int sendRawPacket(final HidDevice hidDevice, final byte[] fullPacket)
    {
        if (hidDevice == null || fullPacket == null || fullPacket.length == 0)
            return -1;
        final byte reportId = fullPacket[0];
        final byte[] payload = new byte[fullPacket.length - 1];
        System.arraycopy(fullPacket, 1, payload, 0, payload.length);
        return hidDevice.setOutputReport(reportId, payload, payload.length);
    }

    private int sendFeatureReport(final HidDevice hidDevice, final byte reportId, final byte[] payload)
    {
        if (hidDevice == null)
            return -1;
        return hidDevice.setFeatureReport(reportId, payload, payload.length);
    }

    private int sendLedPayload(final HidDevice hidDevice, final byte[] payload)
    {
        if (isMacOs())
        {
            final byte[] data = new byte[payload.length + 1];
            data[0] = A49Mappings.REPORT_ID_LED;
            System.arraycopy(payload, 0, data, 1, payload.length);
            return hidDevice.setOutputReport(A49Mappings.REPORT_ID_LED, data, data.length);
        }

        return hidDevice.setOutputReport(A49Mappings.REPORT_ID_LED, payload, payload.length);
    }

    private static byte[] buildDisplayPacket(final byte chunkSelector, final byte[] framebuffer, final int firstPage)
    {
        final byte[] full = new byte[A49Mappings.DISPLAY_PACKET_TOTAL_LENGTH];
        full[0] = A49Mappings.REPORT_ID_DISPLAY;
        full[1] = 0x00;
        full[2] = 0x00;
        full[3] = chunkSelector;
        full[4] = 0x00;
        full[5] = (byte) 0x80;
        full[6] = 0x00;
        full[7] = 0x02;
        full[8] = 0x00;

        int dst = A49Mappings.DISPLAY_PACKET_DATA_OFFSET;
        final int stride = A49Mappings.DISPLAY_WIDTH;
        for (int pageOffset = 0; pageOffset < 2; pageOffset++)
        {
            final int page = firstPage + pageOffset;
            final int src = page * stride;
            System.arraycopy(framebuffer, src, full, dst, stride);
            dst += stride;
        }
        return full;
    }

    private static byte[] rebuildInputReport(final byte reportId, final byte[] data, final int length)
    {
        final int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        final byte[] report = new byte[safeLength + 1];
        report[0] = reportId;

        if (safeLength <= 0 || data == null)
            return report;

        if (isMacOs() && data[0] == reportId && safeLength > 0)
        {
            final byte[] macReport = new byte[safeLength];
            macReport[0] = reportId;
            if (safeLength > 1)
                System.arraycopy(data, 1, macReport, 1, safeLength - 1);
            return macReport;
        }

        System.arraycopy(data, 0, report, 1, safeLength);
        return report;
    }

    private static final class TrackFrame
    {
        private final String trackType;
        private final String name;
        private final boolean mute;
        private final boolean solo;
        private final String volumeText;
        private final boolean arm;

        private TrackFrame(final String trackType, final String name, final boolean mute, final boolean solo,
                           final String volumeText, final boolean arm)
        {
            this.trackType = trackType;
            this.name = name;
            this.mute = mute;
            this.solo = solo;
            this.volumeText = volumeText;
            this.arm = arm;
        }
    }

    private static boolean matchesText(final String actual, final String expectedSubstring)
    {
        if (actual == null || actual.trim().isEmpty())
            return true;
        return actual.toLowerCase(Locale.ROOT).contains(expectedSubstring.toLowerCase(Locale.ROOT));
    }

    private static boolean isMacOs()
    {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac");
    }

    private static void sleepQuietly(final int millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] hex(final String hex)
    {
        final int length = hex.length();
        final byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2)
            data[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return data;
    }
}
