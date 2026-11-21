package dantcimation.style;

/**
 * How long a character holds each pose in a shot. A shot declares one cadence per character;
 * the camera is never stepped and has no cadence.
 */
public enum Cadence
{
    /** On 3s, 8 poses per second: dialogue, gestures, reactions, walks. The default. */
    ACTING("acting", 3),

    /** On 2s, 12 poses per second: fights, runs, falls, fast comedy. */
    ACTION("action", 2);

    /** Stable name in saved data and in tool arguments. */
    public final String key;
    public final int framesPerPose;

    private Cadence(String key, int framesPerPose)
    {
        this.key = key;
        this.framesPerPose = framesPerPose;
    }

    public static Cadence byKey(String key)
    {
        for (Cadence cadence : values())
        {
            if (cadence.key.equals(key))
            {
                return cadence;
            }
        }

        return null;
    }
}
