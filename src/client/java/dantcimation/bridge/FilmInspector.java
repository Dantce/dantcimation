package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dantcimation.style.Cadence;
import dantcimation.style.CurveValidator;
import dantcimation.style.FrameGrid;
import dantcimation.style.TimingValidator;
import dantcimation.style.Violation;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the film that is open in BBS's film editor into what the tools exchange: frames at
 * 24 fps instead of ticks, and plain JSON instead of BBS's value tree.
 *
 * <p>Everything here touches live editor state, so it must be called on the client thread.</p>
 */
public final class FilmInspector
{
    private FilmInspector()
    {}

    /** The film editor, or {@code null} when BBS's dashboard has not been opened yet. */
    public static UIFilmPanel getPanel()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        return dashboard == null ? null : dashboard.getPanel(UIFilmPanel.class);
    }

    /** The film open in the film editor, or {@code null} when the editor has none. */
    public static Film getOpenFilm()
    {
        UIFilmPanel panel = getPanel();

        return panel == null ? null : panel.getData();
    }

    public static JsonObject describe(Film film)
    {
        JsonObject json = new JsonObject();
        JsonArray replays = new JsonArray();
        List<Replay> list = film.replays.getList();

        for (int i = 0; i < list.size(); i++)
        {
            replays.add(describe(list.get(i), i));
        }

        json.addProperty("id", film.getId());
        json.addProperty("frames", toFrames(film.calculateDuration()));
        json.addProperty("cameraClips", film.camera.get().size());
        json.add("replays", replays);

        return json;
    }

    private static JsonObject describe(Replay replay, int index)
    {
        JsonObject json = new JsonObject();
        JsonArray tracks = new JsonArray();
        Form form = replay.form.get();

        json.addProperty("index", index);
        json.addProperty("id", replay.getId());
        json.addProperty("name", replay.getName());
        json.addProperty("enabled", replay.enabled.get());

        if (form instanceof ModelForm modelForm)
        {
            json.addProperty("model", modelForm.model.get());
        }

        /* Where it stands when the film starts */
        json.addProperty("x", replay.keyframes.x.interpolate(0F, 0D));
        json.addProperty("y", replay.keyframes.y.interpolate(0F, 0D));
        json.addProperty("z", replay.keyframes.z.interpolate(0F, 0D));
        json.addProperty("yaw", replay.keyframes.yaw.interpolate(0F, 0D));
        json.addProperty("bodyYaw", replay.keyframes.bodyYaw.interpolate(0F, 0D));

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            addTrack(tracks, "entity", channel.getId(), channel);
        }

        for (Map.Entry<TrackId, KeyframeChannel> entry : replay.properties.tracks.entrySet())
        {
            addTrack(tracks, entry.getKey().kind().key, entry.getKey().toKey(), entry.getValue());
        }

        json.add("tracks", tracks);

        return json;
    }

    /** Tracks without keys are left out: a replay carries dozens of empty entity channels. */
    private static void addTrack(JsonArray tracks, String kind, String id, KeyframeChannel<?> channel)
    {
        List<?> keyframes = channel.getKeyframes();

        if (keyframes.isEmpty())
        {
            return;
        }

        JsonObject json = new JsonObject();

        json.addProperty("kind", kind);
        json.addProperty("id", id);
        json.addProperty("keys", keyframes.size());
        json.addProperty("firstFrame", toFrame(((Keyframe<?>) keyframes.get(0)).getTick()));
        json.addProperty("lastFrame", toFrame(((Keyframe<?>) keyframes.get(keyframes.size() - 1)).getTick()));

        tracks.add(json);
    }

    /**
     * Run the timing and curve rules of the style guide over every pose track of every enabled
     * replay. One cadence is assumed for the whole film until shots can declare their own.
     */
    public static JsonObject validate(Film film, Cadence cadence)
    {
        JsonObject json = new JsonObject();
        JsonArray violations = new JsonArray();
        List<Replay> list = film.replays.getList();
        int checked = 0;

        for (int i = 0; i < list.size(); i++)
        {
            Replay replay = list.get(i);

            if (!replay.enabled.get())
            {
                continue;
            }

            for (Map.Entry<TrackId, KeyframeChannel> entry : replay.properties.tracks.entrySet())
            {
                if (!isPoseTrack(entry.getKey()))
                {
                    continue;
                }

                checked += 1;

                for (Violation violation : validate(entry.getValue(), cadence))
                {
                    JsonObject item = new JsonObject();

                    item.addProperty("replay", i);
                    item.addProperty("track", entry.getKey().toKey());
                    item.addProperty("rule", violation.rule());
                    item.addProperty("severity", violation.severity().name().toLowerCase());
                    item.addProperty("frame", violation.frame());
                    item.addProperty("message", violation.message());

                    violations.add(item);
                }
            }
        }

        json.addProperty("cadence", cadence.key);
        json.addProperty("tracksChecked", checked);
        json.add("violations", violations);

        return json;
    }

    private static List<Violation> validate(KeyframeChannel<?> channel, Cadence cadence)
    {
        List<?> keyframes = channel.getKeyframes();
        float[] ticks = new float[keyframes.size()];
        String[] interpolations = new String[keyframes.size()];

        for (int i = 0; i < ticks.length; i++)
        {
            Keyframe<?> keyframe = (Keyframe<?>) keyframes.get(i);

            ticks[i] = keyframe.getTick();
            interpolations[i] = keyframe.getInterpolation().getInterp().getKey();
        }

        List<Violation> violations = new ArrayList<>(TimingValidator.validate(ticks, cadence));

        violations.addAll(CurveValidator.validate(ticks, interpolations));

        return violations;
    }

    /** The whole pose of a form, its overlay poses, or one bone of either. */
    private static boolean isPoseTrack(TrackId track)
    {
        if (track.kind() == TrackKind.BONE)
        {
            return true;
        }

        return track.kind() == TrackKind.PROPERTY && track.subject().startsWith("pose");
    }

    /** A key's frame: a whole number when the key is on the grid, as every key a tool wrote is. */
    private static Number toFrame(float tick)
    {
        if (FrameGrid.isOnGrid(tick))
        {
            return FrameGrid.nearestFrame(tick);
        }

        return FrameGrid.toFrame(tick);
    }

    private static int toFrames(int ticks)
    {
        return (int) Math.ceil(FrameGrid.toFrame(ticks));
    }
}
