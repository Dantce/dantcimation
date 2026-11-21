package dantcimation.bridge;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Turns frames read back from the game into the picture a tool looks at: one frame, or a
 * contact sheet of several with each one's frame number in its corner.
 *
 * <p>A contact sheet is how an animator checks stepped animation — every pose side by side, read
 * as stills — and it is how the tool that wrote the poses checks its own work. Nothing here
 * touches the game, so it runs on a bridge thread and can be tried without it.</p>
 */
public final class Frames
{
    public static final int DEFAULT_CELL_WIDTH = 320;
    public static final int MAX_FRAMES = 24;

    private Frames()
    {}

    /**
     * One frame as the graphics card hands it back.
     *
     * @param rgb three bytes a pixel, rows from the BOTTOM up
     */
    public record Frame(int width, int height, byte[] rgb)
    {
        public Frame
        {
            if (width <= 0 || height <= 0 || rgb.length != width * height * 3)
            {
                throw new IllegalArgumentException("A " + width + "x" + height + " frame cannot have " + rgb.length + " bytes");
            }
        }
    }

    public static BufferedImage toImage(Frame frame)
    {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        byte[] rgb = frame.rgb();
        int[] row = new int[frame.width()];

        for (int y = 0; y < frame.height(); y++)
        {
            int offset = (frame.height() - 1 - y) * frame.width() * 3;

            for (int x = 0; x < row.length; x++)
            {
                row[x] = (rgb[offset] & 0xff) << 16 | (rgb[offset + 1] & 0xff) << 8 | (rgb[offset + 2] & 0xff);
                offset += 3;
            }

            image.setRGB(0, y, row.length, 1, row, 0, row.length);
        }

        return image;
    }

    /**
     * Lay frames out left to right, top to bottom, each scaled to the cell width and labelled.
     *
     * @param labels frame number of each frame, drawn in its corner
     */
    public static BufferedImage sheet(List<Frame> frames, int[] labels, int columns, int cellWidth)
    {
        if (frames.isEmpty() || frames.size() != labels.length)
        {
            throw new IllegalArgumentException("A sheet needs one label per frame");
        }

        Frame first = frames.get(0);
        int cellHeight = Math.max(1, Math.round(cellWidth * first.height() / (float) first.width()));
        int rows = (frames.size() + columns - 1) / columns;
        BufferedImage sheet = new BufferedImage(columns * cellWidth, rows * cellHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));

        for (int i = 0; i < frames.size(); i++)
        {
            int x = i % columns * cellWidth;
            int y = i / columns * cellHeight;
            String label = String.valueOf(labels[i]);

            graphics.drawImage(toImage(frames.get(i)), x, y, cellWidth, cellHeight, null);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(x, y, 12 + label.length() * 9, 18);
            graphics.setColor(Color.YELLOW);
            graphics.drawString(label, x + 5, y + 14);
        }

        graphics.dispose();

        return sheet;
    }

    public static byte[] toPng(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if (!ImageIO.write(image, "png", output))
        {
            throw new IOException("This Java runtime cannot write PNG images");
        }

        return output.toByteArray();
    }
}
