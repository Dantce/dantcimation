package dantcimation.bridge;

import com.mojang.blaze3d.opengl.GlStateManager;
import dantcimation.bridge.Frames.Frame;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.graphics.PixelPackState;
import mchorse.bbs_mod.graphics.texture.Texture;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Reads back what the film editor's preview is showing.
 *
 * <p>BBS copies the world it draws for the preview into a texture of its own every frame while
 * the film editor is open; that copy is what the editor's screenshot and video export read, and
 * what this reads. It has to be called on the client thread, which is the one that owns the
 * graphics context.</p>
 */
public final class FrameGrabber
{
    private FrameGrabber()
    {}

    public static Frame grab()
    {
        Texture texture = BBSRendering.getTexture();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        /* Through GlStateManager rather than raw GL, so the game's own record of what is bound
         * stays true — BBS's recorder learned that the hard way */
        GlStateManager._bindTexture(texture.id);

        try
        {
            /* The read has no size argument: it writes the whole level, whatever size that is.
             * So the buffer is sized from the level itself and never from what it should be. */
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

            if (width <= 0 || height <= 0)
            {
                throw new IllegalStateException("The preview has not been drawn yet; open the film editor's preview first");
            }

            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 3);

            /* Rows of three-byte pixels are not a multiple of four bytes at most widths */
            try (PixelPackState pack = PixelPackState.push(1))
            {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer);
            }

            byte[] rgb = new byte[width * height * 3];

            buffer.rewind();
            buffer.get(rgb);

            return new Frame(width, height, rgb);
        }
        finally
        {
            GlStateManager._bindTexture(previous);
        }
    }
}
