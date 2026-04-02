package dev.demperor.bitwig.a49;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A small monochrome bitmap loaded from one of the JSON files in
 * {@code src/main/resources/icons}.
 *
 * <p>The JSON stores a flat pixel array where 0 means a lit pixel and a
 * non-zero value means background, matching the display's framebuffer
 * polarity.</p>
 */
public final class A49IconBitmap
{
    private final int width;
    private final int height;
    private final boolean[] pixels;

    public A49IconBitmap(final int width, final int height, final boolean[] pixels)
    {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public int getWidth()
    {
        return this.width;
    }

    public int getHeight()
    {
        return this.height;
    }

    public boolean getPixel(final int x, final int y)
    {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height)
            return false;
        return this.pixels[y * this.width + x];
    }

    public static A49IconBitmap loadFromResource(final String resourcePath) throws IOException
    {
        final InputStream in = A49IconBitmap.class.getResourceAsStream(resourcePath);
        if (in == null)
            throw new IOException("Resource not found: " + resourcePath);

        final StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line);
        }

        final String json = sb.toString();
        final int width = parseInt(json, "\"width\"");
        final int height = parseInt(json, "\"height\"");
        final String pixelsRaw = parseArray(json, "\"pixels\"");
        final String[] parts = pixelsRaw.split(",");

        if (parts.length != width * height)
            throw new IOException("Pixel count does not match width*height in " + resourcePath);

        final boolean[] pixels = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++)
            pixels[i] = Integer.parseInt(parts[i].trim()) == 0;

        return new A49IconBitmap(width, height, pixels);
    }

    private static int parseInt(final String json, final String key) throws IOException
    {
        final int keyPos = json.indexOf(key);
        if (keyPos < 0)
            throw new IOException("Missing key: " + key);

        final int colon = json.indexOf(':', keyPos);
        if (colon < 0)
            throw new IOException("Malformed JSON near " + key);

        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start)))
            start++;

        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end)))
            end++;

        return Integer.parseInt(json.substring(start, end));
    }

    private static String parseArray(final String json, final String key) throws IOException
    {
        final int keyPos = json.indexOf(key);
        if (keyPos < 0)
            throw new IOException("Missing key: " + key);

        final int open = json.indexOf('[', keyPos);
        final int close = json.indexOf(']', open);
        if (open < 0 || close < 0)
            throw new IOException("Malformed array for " + key);

        return json.substring(open + 1, close);
    }
}
