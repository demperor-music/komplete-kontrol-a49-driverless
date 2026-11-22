package dev.demperor.bitwig.a49;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Renders text, icons and status frames for the A49's 128x32 monochrome OLED.
 *
 * <p>The display is addressed as four 8-pixel-high pages. The packed
 * framebuffer uses 0xFF for a black pixel and clears a bit for a lit pixel,
 * matching the controller's page-oriented format.</p>
 *
 * <p>Long text is kept on one line and can be scrolled horizontally by passing
 * a scroll offset to the render methods; see
 * {@link #messageScrollMaxOffset(String)} and
 * {@link #trackNameScrollMaxOffset(String)}.</p>
 */
public final class A49DisplayRenderer
{
    private enum IconType
    {
        NONE,
        BROWSER,
        INSTRUMENT,
        AUDIO,
        HYBRID,
        FX,
        MASTER,
        TRACK,
        DEVICE,
        MIDI,
        PLAY,
        STOP,
        RECORD,
        LOOP,
        METRO,
        ARP,
        SCALE,
        OCTAVE,
        INFO
    }

    private enum LayoutType
    {
        CARD,
        PROMPT,
        CENTERED
    }

    private static final int WIDTH = A49Mappings.DISPLAY_WIDTH;
    private static final int HEIGHT = A49Mappings.DISPLAY_HEIGHT;
    private static final int PAGE_HEIGHT = A49Mappings.DISPLAY_PAGE_HEIGHT;
    private static final int PAGE_COUNT = A49Mappings.DISPLAY_PAGE_COUNT;

    private static final int CARD_ICON_X = 1;
    private static final int CARD_ICON_Y = 4;
    private static final int CARD_TEXT_X = 18;
    private static final int CARD_HEADER_Y = 2;
    private static final int CARD_DETAIL_Y = 17;

    private static final int TRACK_ICON_X = 0;
    private static final int TRACK_ICON_Y = 0;
    private static final int TRACK_NAME_X = 16;
    private static final int TRACK_NAME_Y = 1;
    private static final int TRACK_TOGGLE_Y = 18;
    private static final int TRACK_VOLUME_Y = 18;
    private static final int TRACK_RECORD_ARM_X = 1;
    private static final int TRACK_SOLO_X = 17;
    private static final int TRACK_MUTE_X = 33;

    private final A49BitmapFont font = loadFont("/icons/charmap_font.json");
    private final A49IconBitmap browserIcon = loadIcon("/icons/browser.json");
    private final A49IconBitmap instrumentIcon = loadIcon("/icons/midi.json");
    private final A49IconBitmap midiIcon = loadIcon("/icons/midi.json");
    private final A49IconBitmap audioIcon = loadIcon("/icons/audio.json");
    private final A49IconBitmap hybridIcon = loadIcon("/icons/hybrid.json");
    private final A49IconBitmap fxIcon = loadIcon("/icons/fx.json");
    private final A49IconBitmap masterIcon = loadIcon("/icons/master.json");
    private final A49IconBitmap trackIcon = loadIcon("/icons/track.json");
    private final A49IconBitmap deviceIcon = loadIcon("/icons/device.json");
    private final A49IconBitmap loopIcon = loadIcon("/icons/loop.json");
    private final A49IconBitmap playIcon = loadIcon("/icons/play.json");
    private final A49IconBitmap stopIcon = loadIcon("/icons/stop.json");
    private final A49IconBitmap recordIcon = loadIcon("/icons/record.json");
    private final A49IconBitmap arpIcon = loadIcon("/icons/arp.json");
    private final A49IconBitmap metroIcon = loadIcon("/icons/metronome.json");
    private final A49IconBitmap scaleIcon = loadIcon("/icons/scale.json");
    private final A49IconBitmap octaveIcon = loadIcon("/icons/octave.json");
    private final A49IconBitmap infoIcon = loadIcon("/icons/info.json");
    private final A49IconBitmap muteOffIcon = loadIcon("/icons/mute_off.json");
    private final A49IconBitmap muteOnIcon = loadIcon("/icons/mute_on.json");
    private final A49IconBitmap soloOffIcon = loadIcon("/icons/solo-off.json");
    private final A49IconBitmap soloOnIcon = loadIcon("/icons/solo-on.json");
    private final A49IconBitmap recordArmOffIcon = loadIcon("/icons/record-arm-off.json");
    private final A49IconBitmap recordArmOnIcon = loadIcon("/icons/record-arm-on.json");

    public byte[] renderBlankFrame()
    {
        return this.packFramebuffer(new boolean[HEIGHT][WIDTH]);
    }

    public byte[] renderMessageFrame(final String text)
    {
        return this.renderMessageFrame(text, 0);
    }

    public byte[] renderMessageFrame(final String text, final int scrollOffset)
    {
        final MessageLayout layout = this.parseLayout(text);
        final BufferedImage image = this.newCanvas();
        final Graphics2D g = image.createGraphics();
        try
        {
            this.prepare(g);
            switch (layout.layoutType)
            {
                case PROMPT:
                    this.drawPrompt(g, layout);
                    break;
                case CENTERED:
                    this.drawCentered(g, layout, scrollOffset);
                    break;
                case CARD:
                default:
                    this.drawCard(g, layout, scrollOffset);
                    break;
            }
        }
        finally
        {
            g.dispose();
        }
        return this.packFromImage(image);
    }

    public byte[] renderTrackFrame(final String trackType, final String trackName, final boolean mute, final boolean solo, final String volumeText, final boolean arm)
    {
        return this.renderTrackFrame(trackType, trackName, mute, solo, volumeText, arm, 0);
    }

    public byte[] renderTrackFrame(final String trackType, final String trackName, final boolean mute, final boolean solo, final String volumeText, final boolean arm, final int nameScrollOffset)
    {
        final BufferedImage image = this.newCanvas();
        final Graphics2D g = image.createGraphics();
        try
        {
            this.prepare(g);
            final IconType iconType = resolveTrackIconType(trackType);
            this.drawIcon(g, iconType, TRACK_ICON_X, TRACK_ICON_Y);
            this.drawScrollingText(g, TRACK_NAME_X, TRACK_NAME_Y, WIDTH - TRACK_NAME_X - 1, normalize(trackName), nameScrollOffset);

            this.drawBitmapIcon(g, arm ? this.recordArmOnIcon : this.recordArmOffIcon, TRACK_RECORD_ARM_X, TRACK_TOGGLE_Y);
            this.drawBitmapIcon(g, solo ? this.soloOnIcon : this.soloOffIcon, TRACK_SOLO_X, TRACK_TOGGLE_Y);
            this.drawBitmapIcon(g, mute ? this.muteOnIcon : this.muteOffIcon, TRACK_MUTE_X, TRACK_TOGGLE_Y);

            final String volume = normalize(volumeText).isEmpty() ? "--" : normalize(volumeText);
            final int volumeX = Math.max(TRACK_SOLO_X + 16, WIDTH - this.font.measureText(volume) - 1);
            this.drawText(g, volumeX, TRACK_VOLUME_Y, volume);
        }
        finally
        {
            g.dispose();
        }
        return this.packFromImage(image);
    }

    public byte[] renderOctaveFrame(final int octaveOffset)
    {
        final MessageLayout layout = new MessageLayout("Octave", formatSignedInt(octaveOffset), IconType.OCTAVE, LayoutType.CARD);
        final BufferedImage image = this.newCanvas();
        final Graphics2D g = image.createGraphics();
        try
        {
            this.prepare(g);
            this.drawCard(g, layout, 0);
        }
        finally
        {
            g.dispose();
        }
        return this.packFromImage(image);
    }

    /** Maximum horizontal scroll offset for a message, in pixels. */
    public int messageScrollMaxOffset(final String text)
    {
        final MessageLayout layout = this.parseLayout(text);
        final String scrollText = this.scrollableText(layout);
        if (scrollText.isEmpty())
            return 0;
        final int viewportWidth = layout.layoutType == LayoutType.CARD
            ? WIDTH - CARD_TEXT_X - 1
            : WIDTH - 2;
        return Math.max(0, this.font.measureText(scrollText) - viewportWidth);
    }

    /** Maximum horizontal scroll offset for a track name, in pixels. */
    public int trackNameScrollMaxOffset(final String trackName)
    {
        return Math.max(0, this.font.measureText(normalize(trackName)) - (WIDTH - TRACK_NAME_X - 1));
    }

    private static A49BitmapFont loadFont(final String path)
    {
        try
        {
            return A49BitmapFont.loadFromResource(path);
        }
        catch (final IOException ex)
        {
            throw new RuntimeException("Could not load font: " + path, ex);
        }
    }

    private static A49IconBitmap loadIcon(final String path)
    {
        try
        {
            return A49IconBitmap.loadFromResource(path);
        }
        catch (final IOException ex)
        {
            throw new RuntimeException("Could not load icon: " + path, ex);
        }
    }

    private BufferedImage newCanvas()
    {
        return new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
    }

    private void prepare(final Graphics2D g)
    {
        g.setBackground(Color.BLACK);
        g.clearRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
    }

    private void drawCard(final Graphics2D g, final MessageLayout layout, final int scrollOffset)
    {
        this.drawIcon(g, layout.iconType, CARD_ICON_X, CARD_ICON_Y);
        this.drawText(g, CARD_TEXT_X, CARD_HEADER_Y, truncateToWidth(layout.header, WIDTH - CARD_TEXT_X - 1));

        final String detail = normalize(layout.detail);
        if (!detail.isEmpty())
            this.drawScrollingText(g, CARD_TEXT_X, CARD_DETAIL_Y, WIDTH - CARD_TEXT_X - 1, detail, scrollOffset);
    }

    private void drawPrompt(final Graphics2D g, final MessageLayout layout)
    {
        final String top = truncateToWidth(layout.header.toUpperCase(Locale.ROOT), WIDTH - 2);
        final String bottom = truncateToWidth(layout.detail.toUpperCase(Locale.ROOT), WIDTH - 2);
        this.drawCenteredLine(g, top, 2);
        this.drawCenteredLine(g, bottom, 17);
    }

    private void drawCentered(final Graphics2D g, final MessageLayout layout, final int scrollOffset)
    {
        final String text = this.scrollableText(layout);
        final int y = Math.max(0, (HEIGHT - this.font.getLineHeight()) / 2);
        if (this.font.measureText(text) <= WIDTH - 2)
            this.drawCenteredLine(g, text, y);
        else
            this.drawScrollingText(g, 1, y, WIDTH - 2, text, scrollOffset);
    }

    private void drawCenteredLine(final Graphics2D g, final String text, final int y)
    {
        final int x = Math.max(0, (WIDTH - this.font.measureText(text)) / 2);
        this.drawText(g, x, y, text);
    }

    private void drawScrollingText(final Graphics2D g, final int x, final int y, final int maxWidth, final String text, final int scrollOffset)
    {
        if (text == null || text.isEmpty() || maxWidth <= 0)
            return;

        final Shape previousClip = g.getClip();
        try
        {
            g.setClip(x, y, maxWidth, this.font.getLineHeight() + 2);
            this.drawText(g, x - Math.max(0, scrollOffset), y, text);
        }
        finally
        {
            g.setClip(previousClip);
        }
    }

    private String scrollableText(final MessageLayout layout)
    {
        if (layout.layoutType == LayoutType.CARD)
            return normalize(layout.detail);
        if (layout.layoutType == LayoutType.CENTERED)
            return (layout.header + (layout.detail.isEmpty() ? "" : " " + layout.detail)).trim();
        return "";
    }

    private void drawText(final Graphics2D g, final int x, final int y, final String text)
    {
        this.font.drawText(new Graphics2DProxy(g), x, y, text);
    }

    private void drawIcon(final Graphics2D g, final IconType iconType, final int x, final int y)
    {
        this.drawBitmapIcon(g, this.iconBitmapFor(iconType), x, y);
    }

    private A49IconBitmap iconBitmapFor(final IconType iconType)
    {
        switch (iconType)
        {
            case BROWSER:
                return this.browserIcon;
            case INSTRUMENT:
                return this.instrumentIcon;
            case AUDIO:
                return this.audioIcon;
            case HYBRID:
                return this.hybridIcon;
            case FX:
                return this.fxIcon;
            case MASTER:
                return this.masterIcon;
            case TRACK:
                return this.trackIcon;
            case DEVICE:
                return this.deviceIcon;
            case MIDI:
                return this.midiIcon;
            case PLAY:
                return this.playIcon;
            case STOP:
                return this.stopIcon;
            case RECORD:
                return this.recordIcon;
            case LOOP:
                return this.loopIcon;
            case METRO:
                return this.metroIcon;
            case ARP:
                return this.arpIcon;
            case SCALE:
                return this.scaleIcon;
            case OCTAVE:
                return this.octaveIcon;
            case INFO:
                return this.infoIcon;
            case NONE:
            default:
                return null;
        }
    }

    private static IconType resolveTrackIconType(final String trackType)
    {
        final String lower = trackType == null ? "" : trackType.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("master"))
            return IconType.MASTER;
        if (lower.contains("instrument") || lower.contains("midi"))
            return IconType.INSTRUMENT;
        if (lower.contains("audio"))
            return IconType.AUDIO;
        if (lower.contains("hybrid"))
            return IconType.HYBRID;
        if (lower.contains("effect") || lower.contains("fx"))
            return IconType.FX;
        return IconType.TRACK;
    }

    private void drawBitmapIcon(final Graphics2D g, final A49IconBitmap icon, final int x, final int y)
    {
        if (icon == null)
            return;

        for (int iy = 0; iy < icon.getHeight(); iy++)
        {
            for (int ix = 0; ix < icon.getWidth(); ix++)
            {
                if (icon.getPixel(ix, iy))
                    g.fillRect(x + ix, y + iy, 1, 1);
            }
        }
    }

    private byte[] packFromImage(final BufferedImage image)
    {
        final boolean[][] canvas = new boolean[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < WIDTH; x++)
                canvas[y][x] = (image.getRGB(x, y) & 0x00FFFFFF) != 0;
        }
        return this.packFramebuffer(canvas);
    }

    private byte[] packFramebuffer(final boolean[][] canvas)
    {
        final byte[] framebuffer = new byte[A49Mappings.DISPLAY_FRAMEBUFFER_LENGTH];
        int index = 0;
        for (int page = 0; page < PAGE_COUNT; page++)
        {
            final int yBase = page * PAGE_HEIGHT;
            for (int x = 0; x < WIDTH; x++)
            {
                int value = 0xFF;
                for (int bit = 0; bit < PAGE_HEIGHT; bit++)
                {
                    final int y = yBase + bit;
                    if (y >= 0 && y < HEIGHT && canvas[y][x])
                        value &= ~(1 << bit);
                }
                framebuffer[index++] = (byte) value;
            }
        }
        return framebuffer;
    }

    private MessageLayout parseLayout(final String rawText)
    {
        final String parsed = normalizeForParsing(rawText);
        if (parsed.isEmpty())
            return new MessageLayout("", "", IconType.NONE, LayoutType.CENTERED);

        final String lowerParsed = parsed.toLowerCase(Locale.ROOT);
        if (lowerParsed.equals("preset browser") || lowerParsed.equals("browser") || lowerParsed.equals("open browser")
            || lowerParsed.equals("press browser"))
            return new MessageLayout("Press", "BROWSER", IconType.BROWSER, LayoutType.PROMPT);

        String header = parsed;
        String detail = "";
        if (parsed.contains("\n"))
        {
            final String[] parts = parsed.split("\\n", 2);
            header = parts[0].trim();
            detail = parts.length > 1 ? parts[1].trim() : "";
        }
        else if (parsed.contains(":"))
        {
            final int index = parsed.indexOf(':');
            header = parsed.substring(0, index).trim();
            detail = parsed.substring(index + 1).trim();
        }
        else if (parsed.contains(" — "))
        {
            final int index = parsed.indexOf(" — ");
            header = parsed.substring(0, index).trim();
            detail = parsed.substring(index + 3).trim();
        }
        else if (parsed.contains(" - "))
        {
            final int index = parsed.indexOf(" - ");
            header = parsed.substring(0, index).trim();
            detail = parsed.substring(index + 3).trim();
        }
        else
        {
            // Keep the full string. Long single-line messages are rendered as a
            // centered marquee instead of being wrapped onto a second line.
            header = parsed;
            detail = "";
        }

        if (detail.isEmpty() && header.length() > 14)
            return new MessageLayout(header, "", iconFor(header, detail, parsed), LayoutType.CENTERED);

        final String canonicalHeader = canonicalHeader(header, detail);
        final IconType icon = iconFor(canonicalHeader, detail, parsed);
        final LayoutType layoutType = chooseLayout(parsed, canonicalHeader, detail);
        return new MessageLayout(canonicalHeader, detail, icon, layoutType);
    }

    private static LayoutType chooseLayout(final String parsed, final String header, final String detail)
    {
        final String lower = parsed.toLowerCase(Locale.ROOT);
        if (lower.contains("press browser") || lower.equals("preset browser") || lower.equals("browser"))
            return LayoutType.PROMPT;
        if (detail.isEmpty() && (lower.contains("busy") || lower.contains("loading") || lower.contains("ready") || lower.contains("software")))
            return LayoutType.CENTERED;
        if (detail.isEmpty() && header.length() <= 14)
            return LayoutType.CENTERED;
        return LayoutType.CARD;
    }

    private static IconType iconFor(final String header, final String detail, final String parsed)
    {
        final String haystack = (header + " " + detail + " " + parsed).toLowerCase(Locale.ROOT);
        if (haystack.contains("master track") || haystack.contains("master"))
            return IconType.MASTER;
        if (haystack.contains("hybrid track") || haystack.contains("hybrid"))
            return IconType.HYBRID;
        if (haystack.contains("fx track") || haystack.contains("effect track") || haystack.contains(" fx ") || haystack.endsWith(" fx"))
            return IconType.FX;
        if (haystack.contains("one-shot") || haystack.contains("audio track") || haystack.contains("audio"))
            return IconType.AUDIO;
        if (haystack.contains("instrument track") || haystack.contains("instrument") || haystack.contains("piano"))
            return IconType.INSTRUMENT;
        if (haystack.contains("browser") || haystack.contains("preset") || haystack.contains("add device"))
            return IconType.BROWSER;
        if (haystack.contains("loop"))
            return IconType.LOOP;
        if (haystack.contains("device") || haystack.contains("plugin"))
            return IconType.DEVICE;
        if (haystack.contains("midi") || haystack.contains("editor") || haystack.contains("clip"))
            return IconType.MIDI;
        if (haystack.contains("record"))
            return IconType.RECORD;
        if (haystack.contains("stop"))
            return IconType.STOP;
        if (haystack.contains("play") || haystack.contains("restart") || haystack.contains("transport"))
            return IconType.PLAY;
        if (haystack.contains("metro") || haystack.contains("metronome") || haystack.contains("count-in"))
            return IconType.METRO;
        if (haystack.contains("arp"))
            return IconType.ARP;
        if (haystack.contains("scale") || haystack.contains("edit"))
            return IconType.SCALE;
        if (haystack.contains("octave"))
            return IconType.OCTAVE;
        if (haystack.contains("track") || haystack.contains("mix"))
            return IconType.TRACK;
        return IconType.INFO;
    }

    private static String canonicalHeader(final String header, final String detail)
    {
        final String lower = header.toLowerCase(Locale.ROOT);
        final String haystack = (header + " " + detail).toLowerCase(Locale.ROOT);
        if (lower.startsWith("devices panel") || haystack.contains("device panel"))
            return "Device";
        if (lower.startsWith("instrument track") || lower.startsWith("audio track") || lower.startsWith("hybrid track")
            || lower.startsWith("fx track") || lower.startsWith("master track") || lower.startsWith("track"))
            return "Track";
        if (lower.startsWith("mixer") || lower.startsWith("mix view") || lower.startsWith("arrange view"))
            return "Track";
        if (lower.startsWith("browser") || haystack.contains("preset browser"))
            return "Browser";
        if (lower.startsWith("device") || lower.startsWith("plug-in") || lower.startsWith("plugin"))
            return "Device";
        if (lower.startsWith("midi"))
            return "MIDI";
        if (lower.startsWith("loop"))
            return "Loop";
        if (lower.startsWith("metronome"))
            return "Metronome";
        if (lower.startsWith("arp"))
            return "Arp";
        if (lower.startsWith("scale"))
            return "Scale";
        if (lower.startsWith("octave"))
            return "Octave";
        if (lower.startsWith("record"))
            return "Record";
        if (lower.startsWith("stop"))
            return "Stop";
        if (lower.startsWith("play") || lower.startsWith("restart"))
            return "Play";
        if (haystack.contains("results"))
            return "Results";
        if (haystack.contains("file types"))
            return "File Types";
        if (haystack.contains("browser"))
            return "Browser";
        if (haystack.contains("instrument"))
            return "Instrument";
        if (haystack.contains("track") || haystack.contains("mix"))
            return "Track";
        if (haystack.contains("device") || haystack.contains("plugin"))
            return "Device";
        if (haystack.contains("midi") || haystack.contains("editor"))
            return "MIDI";
        if (haystack.contains("loop"))
            return "Loop";
        if (haystack.contains("metronome"))
            return "Metronome";
        if (haystack.contains("arp"))
            return "Arp";
        if (haystack.contains("scale"))
            return "Scale";
        if (haystack.contains("octave"))
            return "Octave";
        if (haystack.contains("record"))
            return "Record";
        if (haystack.contains("stop"))
            return "Stop";
        if (haystack.contains("play") || haystack.contains("restart"))
            return "Play";
        return truncatePlain(header, 16);
    }

    private List<String> wrapDetail(final String text, final int maxWidth, final int maxLines)
    {
        return this.font.wrapText(normalize(text), maxWidth, maxLines);
    }

    private String truncateToWidth(final String text, final int maxWidth)
    {
        final List<String> lines = this.font.wrapText(normalize(text), maxWidth, 1);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    private static String truncatePlain(final String text, final int maxLength)
    {
        if (text == null)
            return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String normalize(final String text)
    {
        if (text == null)
            return "";
        return text.replace('\u2014', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalizeForParsing(final String text)
    {
        if (text == null)
            return "";
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String formatSignedInt(final int value)
    {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static final class MessageLayout
    {
        private final String header;
        private final String detail;
        private final IconType iconType;
        private final LayoutType layoutType;

        private MessageLayout(final String header, final String detail, final IconType iconType, final LayoutType layoutType)
        {
            this.header = header == null ? "" : header;
            this.detail = detail == null ? "" : detail;
            this.iconType = iconType == null ? IconType.NONE : iconType;
            this.layoutType = layoutType == null ? LayoutType.CARD : layoutType;
        }
    }

    private static final class Graphics2DProxy implements A49BitmapFont.Graphics2DProxy
    {
        private final Graphics2D graphics;

        private Graphics2DProxy(final Graphics2D graphics)
        {
            this.graphics = graphics;
        }

        @Override
        public void fillRect(final int x, final int y, final int width, final int height)
        {
            this.graphics.fillRect(x, y, width, height);
        }
    }
}
