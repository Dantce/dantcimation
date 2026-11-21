package dantcimation.style;

import dantcimation.style.Violation.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * The curve rules of the style guide for character tracks (C1 and C3): poses are stepped, and
 * the only interpolation a character may show is a short impact passage.
 *
 * <p>Like {@link TimingValidator} it reads plain values — key ticks and the name of each key's
 * interpolation as BBS spells it — so it runs without the game.</p>
 */
public final class CurveValidator
{
    /** How BBS names the interpolation that holds a key until the next one. */
    public static final String CONSTANT = "constant";

    private CurveValidator()
    {}

    /**
     * @param ticks          key ticks of the track, in ascending order
     * @param interpolations interpolation of the transition leaving each key
     */
    public static List<Violation> validate(float[] ticks, String[] interpolations)
    {
        List<Violation> violations = new ArrayList<>();

        /* The last key has no transition to judge: nothing follows it */
        for (int i = 0; i < ticks.length - 1; i++)
        {
            String interpolation = interpolations[i];

            if (CONSTANT.equals(interpolation))
            {
                continue;
            }

            int frame = FrameGrid.nearestFrame(ticks[i]);
            int gap = FrameGrid.nearestFrame(ticks[i + 1]) - frame;

            if (isBanned(interpolation))
            {
                violations.add(new Violation("C3", Severity.ERROR, frame, "Interpolation '" + interpolation + "' is not used on characters; use '" + CONSTANT + "'."));
            }
            else if (gap > StyleRules.MAX_IMPACT_FRAMES)
            {
                violations.add(new Violation("C1", Severity.ERROR, frame, "Pose interpolates with '" + interpolation + "' for " + gap + " frames; character keys are '" + CONSTANT + "', or baked to stepped keys."));
            }
        }

        return violations;
    }

    private static boolean isBanned(String interpolation)
    {
        return "linear".equals(interpolation) || interpolation.startsWith("elastic");
    }
}
