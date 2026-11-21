package dantcimation.style;

import java.util.List;

/** Standalone checks of the frame grid and the timing rules; needs nothing but the JDK. */
public class StyleCoreChecks
{
    private static int failures;
    private static int checks;

    public static void main(String[] args)
    {
        grid();
        timing();

        System.out.println(checks + " checks, " + failures + " failures");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    private static void grid()
    {
        check(FrameGrid.toTick(3) == 2.5F, "3 frames are 2.5 ticks");
        check(FrameGrid.toTick(24) == 20F, "24 frames are 20 ticks");
        check(FrameGrid.nearestFrame(FrameGrid.toTick(7)) == 7, "frame survives the round trip");
        check(FrameGrid.isOnGrid(2.5F), "2.5 ticks is frame 3");
        check(FrameGrid.isOnGrid(FrameGrid.toTick(1)), "a frame that is not a finite binary fraction is on the grid");
        check(!FrameGrid.isOnGrid(2F), "2 ticks is frame 2.4");
        check(!FrameGrid.isOnGrid(7F), "7 ticks is frame 8.4");
        check(FrameGrid.snap(2F) == FrameGrid.toTick(2), "2 ticks snaps to frame 2");

        boolean allOnGrid = true;

        /* Twenty minutes of film: float ticks lose resolution, the grid check must not */
        for (int frame = 0; frame <= 24 * 60 * 20; frame++)
        {
            allOnGrid &= FrameGrid.isOnGrid(FrameGrid.toTick(frame));
        }

        check(allOnGrid, "every frame of a 20 minute film is on the grid");
    }

    private static void timing()
    {
        /* The emphatic gesture of the style guide: anticipation, extreme, settle, hold */
        check(validate(Cadence.ACTING, 0, 3, 6, 9, 18).isEmpty(), "gesture on 3s is clean");
        check(validate(Cadence.ACTION, 0, 2, 4, 6, 8, 14).isEmpty(), "exchange on 2s is clean");

        check(has(TimingValidator.validate(new float[] {0F, 2F, 5F}, Cadence.ACTING), "T1"), "whole ticks are off the grid");
        check(TimingValidator.validate(new float[] {0F, 2F, 5F}, Cadence.ACTING).size() == 1, "off-grid keys report T1 only");

        check(has(validate(Cadence.ACTING, 0, 3, 5, 9), "T2"), "a 2 frame pose breaks acting cadence");
        check(has(validate(Cadence.ACTION, 0, 2, 5), "T2"), "a 3 frame pose breaks action cadence");

        /* A whip: smear frames on 1s inside a shot on 3s, then back to poses */
        check(validate(Cadence.ACTING, 0, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 20).isEmpty(), "9 impact frames are allowed");
        check(has(validate(Cadence.ACTING, 0, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 21), "T3"), "10 impact frames are not");

        check(has(validate(Cadence.ACTING, 0, 3, 30), "T4"), "a 27 frame freeze is flagged");
        check(!has(validate(Cadence.ACTING, 0, 3, 27), "T4"), "a 24 frame hold is not");

        check(!has(validate(Cadence.ACTING, 0, 3, 6, 9, 12, 15, 18, 21, 24, 30), "T5"), "8 poses then a hold is fine");
        check(has(validate(Cadence.ACTING, 0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 33), "T5"), "9 poses without a hold is flagged");
    }

    private static List<Violation> validate(Cadence cadence, int... frames)
    {
        float[] ticks = new float[frames.length];

        for (int i = 0; i < frames.length; i++)
        {
            ticks[i] = FrameGrid.toTick(frames[i]);
        }

        return TimingValidator.validate(ticks, cadence);
    }

    private static boolean has(List<Violation> violations, String rule)
    {
        for (Violation violation : violations)
        {
            if (violation.rule().equals(rule))
            {
                return true;
            }
        }

        return false;
    }

    private static void check(boolean condition, String message)
    {
        checks += 1;

        if (!condition)
        {
            failures += 1;

            System.out.println("FAILED: " + message);
        }
    }
}
