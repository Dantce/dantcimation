package dantcimation.style;

/**
 * Every threshold of the animation style guide, in one place. Tuning the look means changing a
 * number here, never the code that checks it.
 *
 * <p>All lengths are frames at {@link FrameGrid#FRAMES_PER_SECOND}.</p>
 */
public final class StyleRules
{
    /** T3: an impact passage (a key on every frame) may not run longer than this. */
    public static final int MAX_IMPACT_FRAMES = 9;

    /** T4: an acting character on screen may not go longer than this without a new key. */
    public static final int MAX_HOLD_FRAMES = 24;

    /** T5: a gap of at least this many frames counts as a hold. */
    public static final int MIN_HOLD_FRAMES = 6;

    /** T5: at most this many poses in a row before a hold. */
    public static final int MAX_POSES_WITHOUT_HOLD = 8;

    private StyleRules()
    {}
}
