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
 * <p>A key is written a hair before its frame, not on it ({@link #toKeyTick}). BBS lets a key
 * take effect only once the playhead is past it &mdash; a key at tick 22.5 sampled at tick 22.5
 * still shows the key before &mdash; and makes an exception only for whole ticks. An export
 * samples exactly the frame ticks, give or take float error, so a key sitting on one would show
 * a frame late or on time by chance. The lead takes the chance out.</p>
 *
 * <p>Tools speak frames. This class is the only place that converts them to ticks and back.</p>
 */
public final class FrameGrid
{
    public static final int FRAMES_PER_SECOND = 24;
    public static final int TICKS_PER_SECOND = 20;

    /**
     * How far ahead of its frame, in ticks, a key is written: far enough to clear float error
     * a long way into a film, and half a millisecond of film time.
     */
    public static final double KEY_LEAD = 0.01D;

    /** How far, in frames, a tick may sit from a frame and still count as being on it. */
    public static final double TOLERANCE = 0.02D;

    private FrameGrid()
    {}

    public static float toTick(int frame)
    {
        return (float) toTickExact(frame);
    }

    /**
     * The tick a key of this frame is stored at: {@link #KEY_LEAD} before the frame's own tick,
     * so that the frame samples it. Frame 0 has nothing before it and stays at 0.
     */
    public static float toKeyTick(int frame)
    {
        return frame <= 0 ? 0F : (float) (toTickExact(frame) - KEY_LEAD);
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

    /** The tick a key near this one should be stored at. */
    public static float snap(float tick)
    {
        return toKeyTick(nearestFrame(tick));
    }
}
