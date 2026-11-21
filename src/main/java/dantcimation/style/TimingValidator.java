package dantcimation.style;

import dantcimation.style.Violation.Severity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The timing rules of the style guide (T1 to T5), checked on the key ticks of one character's
 * pose track within one shot.
 *
 * <p>It takes plain ticks rather than a keyframe channel so that it has no dependency on the
 * game and can be run anywhere, including outside of it.</p>
 */
public final class TimingValidator
{
    private TimingValidator()
    {}

    /**
     * @param ticks   key ticks of the track, in any order
     * @param cadence cadence the shot declares for this character
     */
    public static List<Violation> validate(float[] ticks, Cadence cadence)
    {
        List<Violation> violations = new ArrayList<>();
        float[] sorted = ticks.clone();

        Arrays.sort(sorted);

        if (!checkGrid(sorted, violations))
        {
            /* Gaps measured between keys that are off the grid would only repeat the same
             * mistake under four other rule ids */
            return violations;
        }

        int[] frames = new int[sorted.length];

        for (int i = 0; i < sorted.length; i++)
        {
            frames[i] = FrameGrid.nearestFrame(sorted[i]);
        }

        checkGaps(frames, cadence, violations);

        return violations;
    }

    /** T1: every key sits on the 24 fps grid. */
    private static boolean checkGrid(float[] ticks, List<Violation> violations)
    {
        boolean onGrid = true;

        for (float tick : ticks)
        {
            if (FrameGrid.isOnGrid(tick))
            {
                continue;
            }

            int frame = FrameGrid.nearestFrame(tick);

            violations.add(new Violation("T1", Severity.ERROR, frame, "Key at tick " + tick + " is off the 24 fps grid; move it to frame " + frame + " (tick " + FrameGrid.toTick(frame) + ")."));
            onGrid = false;
        }

        return onGrid;
    }

    /**
     * T2 to T5, which all read the gaps between consecutive keys: a gap is a multiple of the
     * cadence's pose length (T2) unless it is a single frame inside a short impact passage (T3),
     * holds do not freeze (T4) and poses do not run on without a hold (T5).
     */
    private static void checkGaps(int[] frames, Cadence cadence, List<Violation> violations)
    {
        int impactStart = -1;
        int posesWithoutHold = 0;

        for (int i = 1; i < frames.length; i++)
        {
            int previous = frames[i - 1];
            int gap = frames[i] - previous;

            if (gap == 0)
            {
                continue;
            }

            if (gap == 1)
            {
                if (impactStart < 0)
                {
                    impactStart = previous;
                }

                /* A run from impactStart to here covers one more frame than their difference */
                if (frames[i] - impactStart == StyleRules.MAX_IMPACT_FRAMES)
                {
                    violations.add(new Violation("T3", Severity.ERROR, impactStart, "Impact passage on 1s runs past " + StyleRules.MAX_IMPACT_FRAMES + " frames; return to poses held " + cadence.framesPerPose + " frames."));
                }
            }
            else
            {
                impactStart = -1;

                if (gap % cadence.framesPerPose != 0)
                {
                    violations.add(new Violation("T2", Severity.ERROR, previous, "Pose is held " + gap + " frames; the " + cadence.key + " cadence holds poses for multiples of " + cadence.framesPerPose + "."));
                }
            }

            if (gap > StyleRules.MAX_HOLD_FRAMES)
            {
                violations.add(new Violation("T4", Severity.WARNING, previous, "Pose is frozen for " + gap + " frames; add a small adjustment every 6 to 9 frames or mark the hold as intentional."));
            }

            if (gap >= StyleRules.MIN_HOLD_FRAMES)
            {
                posesWithoutHold = 0;
            }
            else if (gap > 1)
            {
                /* Impact frames are one move drawn on 1s, not poses of their own */
                posesWithoutHold += 1;

                if (posesWithoutHold == StyleRules.MAX_POSES_WITHOUT_HOLD + 1)
                {
                    violations.add(new Violation("T5", Severity.WARNING, frames[i], "More than " + StyleRules.MAX_POSES_WITHOUT_HOLD + " poses in a row without a hold; rest for at least " + StyleRules.MIN_HOLD_FRAMES + " frames."));
                }
            }
        }
    }
}
