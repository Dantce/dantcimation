package dantcimation.style;

/**
 * One place where an animation breaks the style guide.
 *
 * @param rule     id of the rule in the style guide ({@code T1}, {@code T2}...)
 * @param severity whether the animation must be fixed or should be looked at
 * @param frame    frame the problem is at, on the 24 fps grid
 * @param message  what is wrong and how to fix it, written for the model that made the edit
 */
public record Violation(String rule, Severity severity, int frame, String message)
{
    public enum Severity
    {
        ERROR, WARNING
    }

    @Override
    public String toString()
    {
        return this.rule + " " + this.severity + " @" + this.frame + ": " + this.message;
    }
}
