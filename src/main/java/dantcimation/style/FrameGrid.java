package dantcimation.style;

/**
 * The time base of every Dantcimation animation: a 24 frames per second grid laid over BBS's
 * 20 ticks per second film time.
 *
 * <p>BBS stores keyframe ticks as floats, and its exporter advances {@code 20 / frameRate} ticks
 * per video frame. A 24 fps export therefore samples tick {@code frame * 20 / 24}, and a pose
 * key has to sit exactly on one of those ticks to be held for a whole number of frames. Rounding
 * to whole ticks instead gives holds that alternate between 2 and 3 frames.</p>
 *
 * <p>Tools speak frames. This class is the only place that converts them to ticks and back.</p>
 */
public final class FrameGrid
{
    public static final int FRAMES_PER_SECOND = 24;
    public static final int TICKS_PER_SECOND = 20;

    /** How far, in frames, a tick may sit from a frame and still count as being on it. */
    public static final double TOLERANCE = 0.001D;

    private FrameGrid()
    {}

    public static float toTick(int frame)
    {
        return (float) toTickExact(frame);
    }

    public static double toTickExact(double frame)
    {
        return frame * TICKS_PER_SECOND / FRAMES_PER_SECOND;
    }

    public static double toFrame(double tick)
    {
        return tick * FRAMES_PER_SECOND / TICKS_PER_SECOND;
    }

    public static int nearestFrame(double tick)
    {
        return (int) Math.round(toFrame(tick));
    }

    /**
     * Whether a stored keyframe tick sits on the frame grid.
     *
     * <p>Ticks are floats, so far into a film a frame can no longer be represented exactly. The
     * tolerance grows with the float's own resolution at that tick rather than rejecting keys
     * that are as close to their frame as a float allows.</p>
     */
    public static boolean isOnGrid(float tick)
    {
        double frame = toFrame(tick);
        double resolution = toFrame(Math.ulp(tick));

        return Math.abs(frame - Math.round(frame)) <= Math.max(TOLERANCE, resolution);
    }

    public static float snap(float tick)
    {
        return toTick(nearestFrame(tick));
    }
}
