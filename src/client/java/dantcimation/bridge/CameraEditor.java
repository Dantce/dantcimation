package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.UnknownClip;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.List;

/**
 * The film's camera, on behalf of a tool.
 *
 * <p>A film's camera is a timeline of clips. One clip that holds still is a locked-off shot; a
 * keyframe clip is a move. As with forms, a tool may hand over any clip as the data BBS saves,
 * so every clip type BBS has is reachable; the one thing given a shape of its own here is the
 * move, because that is where the style guide has an opinion: a camera eases, it never travels
 * at a constant speed.</p>
 *
 * <p>Clips start and end on whole ticks, of which there are 20 a second against 24 frames. A
 * cut asked for on a frame that is not a multiple of 6 lands on the nearest tick, and the
 * answer says where it landed. Keys inside a move are not bound by this and sit on the frame
 * asked for.</p>
 *
 * <p>Must be called on the client thread.</p>
 */
public final class CameraEditor
{
    public static final String DEFAULT_EASE = "cubic_inout";

    private static final String[] CHANNELS = {"x", "y", "z", "yaw", "pitch", "roll", "fov"};

    private CameraEditor()
    {}

    /** The camera's clips in the order BBS holds them, and where the editor's view is now. */
    public static JsonObject describe(UIFilmPanel panel, Film film)
    {
        JsonObject json = new JsonObject();
        JsonArray clips = new JsonArray();
        List<Clip> list = film.camera.get();

        for (int i = 0; i < list.size(); i++)
        {
            clips.add(describe(film, list.get(i), i));
        }

        json.add("clips", clips);
        json.add("view", describeView(panel));

        return json;
    }

    /**
     * Add a clip: {@code "type"} for an empty one of that type ({@code idle} by default), or
     * {@code "clip"} for one written the way BBS saves it. A clip made by type is filled from
     * where the editor's view is, which for an idle clip is the whole of it; {@code "fromView"}
     * asks for that, or declines it, outright.
     */
    public static JsonObject addClip(UIFilmPanel panel, Film film, JsonObject body)
    {
        Clip clip = body.has("clip") ? readClip(film, body) : createClip(film, getString(body, "type", "idle"));

        /* An empty clip made by type would otherwise sit at the world's origin */
        boolean fromView = body.has("fromView") ? getBoolean(body, "fromView") : !body.has("clip");

        if (fromView && clip instanceof CameraClip cameraClip)
        {
            cameraClip.fromCamera(panel.getCamera());
        }
        else if (fromView && body.has("fromView"))
        {
            throw new IllegalArgumentException("Only a camera clip can be filled from the view");
        }

        place(film, clip, body, !body.has("clip"));
        film.camera.addClip(clip);

        return describe(film, clip, film.camera.getIndex(clip));
    }

    /**
     * Add a camera move: a keyframe clip built from keys in film frames. A key sets any of x,
     * y, z, yaw, pitch, roll and fov; {@code "fromView": true} takes what it does not set from
     * the editor's view. {@code "ease"} says how the camera leaves that key for the next one.
     *
     * <p>A channel no key sets is left alone, so a move that only keys fov zooms whatever
     * camera is underneath it.</p>
     */
    public static JsonObject addMove(UIFilmPanel panel, Film film, JsonObject body)
    {
        JsonArray keys = getKeys(body);
        Position view = new Position(panel.getCamera());
        int first = Integer.MAX_VALUE;
        int last = 0;

        /* Read everything before changing anything */
        for (JsonElement element : keys)
        {
            JsonObject key = element.getAsJsonObject();
            int frame = getFrame(key, "frame");

            getEase(key);

            for (String channel : CHANNELS)
            {
                if (key.has(channel))
                {
                    key.get(channel).getAsDouble();
                }
            }

            first = Math.min(first, frame);
            last = Math.max(last, frame);
        }

        KeyframeClip clip = new KeyframeClip();
        JsonObject span = new JsonObject();

        span.addProperty("frame", body.has("frame") ? getFrame(body, "frame") : first);

        if (body.has("frames"))
        {
            span.add("frames", body.get("frames"));
        }
        else if (last > first)
        {
            span.addProperty("frames", last - span.get("frame").getAsInt());
        }

        if (body.has("layer"))
        {
            span.add("layer", body.get("layer"));
        }

        place(film, clip, span, true);

        for (JsonElement element : keys)
        {
            JsonObject key = element.getAsJsonObject();
            /* A clip's keys count from the clip's start */
            float tick = Math.max(0F, FrameGrid.toKeyTick(getFrame(key, "frame")) - clip.tick.get());
            IInterp ease = getEase(key);
            boolean fromView = getBoolean(key, "fromView");

            insert(clip.x, tick, ease, key, "x", fromView ? view.point.x : null);
            insert(clip.y, tick, ease, key, "y", fromView ? view.point.y : null);
            insert(clip.z, tick, ease, key, "z", fromView ? view.point.z : null);
            insert(clip.yaw, tick, ease, key, "yaw", fromView ? (double) view.angle.yaw : null);
            insert(clip.pitch, tick, ease, key, "pitch", fromView ? (double) view.angle.pitch : null);
            insert(clip.roll, tick, ease, key, "roll", fromView ? (double) view.angle.roll : null);
            insert(clip.fov, tick, ease, key, "fov", fromView ? (double) view.angle.fov : null);
        }

        film.camera.addClip(clip);

        return describe(film, clip, film.camera.getIndex(clip));
    }

    public static JsonObject removeClip(UIFilmPanel panel, Film film, int index)
    {
        List<Clip> list = film.camera.get();

        if (index < 0 || index >= list.size())
        {
            throw new IllegalArgumentException("There is no camera clip " + index + "; the film has " + list.size());
        }

        film.camera.remove(list.get(index));

        /* The timeline remembers its selection by index, and the clip's own panel may be open */
        panel.cameraEditor.pickClip(null);

        JsonObject json = new JsonObject();

        json.addProperty("removed", index);
        json.addProperty("clips", film.camera.get().size());

        return json;
    }

    /** Take every clip off the camera. */
    public static JsonObject clear(UIFilmPanel panel, Film film)
    {
        List<Clip> list = List.copyOf(film.camera.get());

        for (Clip clip : list)
        {
            film.camera.remove(clip);
        }

        panel.cameraEditor.pickClip(null);

        JsonObject json = new JsonObject();

        json.addProperty("removed", list.size());

        return json;
    }

    /* Where a clip sits */

    /**
     * Put the clip where the body says: {@code "frame"} (the start by default), {@code "frames"}
     * (to the end of the film by default) and {@code "layer"} (the first one free there by
     * default). A clip that came as data keeps what its data said unless the body says otherwise.
     */
    private static void place(Film film, Clip clip, JsonObject body, boolean fresh)
    {
        if (fresh || body.has("frame"))
        {
            clip.tick.set(toTick(body.has("frame") ? getFrame(body, "frame") : 0));
        }

        if (body.has("frames"))
        {
            int frames = getFrame(body, "frames");

            if (frames < 1)
            {
                throw new IllegalArgumentException("'frames' is how long the clip lasts and must be at least 1");
            }

            int start = FrameGrid.nearestFrame(clip.tick.get());

            clip.duration.set(Math.max(1, toTick(start + frames) - clip.tick.get()));
        }
        else if (fresh)
        {
            clip.duration.set(Math.max(1, Math.max(film.calculateDuration(), 20) - clip.tick.get()));
        }

        if (body.has("layer"))
        {
            clip.layer.set(body.get("layer").getAsInt());
        }
        else if (fresh)
        {
            clip.layer.set(0);
            clip.layer.set(film.camera.findFreeLayer(clip));
        }
    }

    private static int toTick(int frame)
    {
        return (int) Math.round(FrameGrid.toTickExact(frame));
    }

    /* Making clips */

    private static Clip createClip(Film film, String type)
    {
        try
        {
            return film.camera.getFactory().create(type.contains(":") ? Link.create(type) : Link.bbs(type));
        }
        catch (IllegalStateException e)
        {
            throw new IllegalArgumentException("BBS has no camera clip of type '" + type + "'. It has: " + film.camera.getFactory().getKeys());
        }
    }

    private static Clip readClip(Film film, JsonObject body)
    {
        if (!body.get("clip").isJsonObject())
        {
            throw new IllegalArgumentException("Send the clip as a 'clip' object, the way BBS saves it");
        }

        MapType data = DataToString.mapFromString(body.get("clip").toString());
        Clip clip = null;

        try
        {
            clip = data == null ? null : film.camera.getFactory().fromData(data);
        }
        catch (IllegalStateException e)
        {}

        if (clip == null || clip instanceof UnknownClip)
        {
            throw new IllegalArgumentException("BBS could not make a clip out of that; 'type' must name a clip type, such as bbs:idle. It has: " + film.camera.getFactory().getKeys());
        }

        return clip;
    }

    private static void insert(KeyframeChannel<Double> channel, float tick, IInterp ease, JsonObject key, String name, Double orElse)
    {
        Double value = key.has(name) ? Double.valueOf(key.get(name).getAsDouble()) : orElse;

        if (value != null)
        {
            int index = channel.insert(tick, value);

            channel.get(index).getInterpolation().setInterp(ease);
        }
    }

    /** The style guide's curve rule C3, applied where the curve is chosen rather than after. */
    private static IInterp getEase(JsonObject key)
    {
        String name = getString(key, "ease", DEFAULT_EASE);

        if (!Interpolations.MAP.containsKey(name))
        {
            throw new IllegalArgumentException("Unknown ease '" + name + "'. BBS has: " + String.join(", ", Interpolations.MAP.keySet()));
        }

        if (name.equals("linear") || name.startsWith("elastic"))
        {
            throw new IllegalArgumentException("A camera does not move on '" + name + "': the style guide rules out linear and elastic curves (C3). Use an ease such as cubic_inout or quart_out, or constant for a cut");
        }

        return Interpolations.MAP.get(name);
    }

    /* Describing */

    private static JsonObject describe(Film film, Clip clip, int index)
    {
        JsonObject json = new JsonObject();
        MapType data = film.camera.getFactory().toData(clip);
        int start = FrameGrid.nearestFrame(clip.tick.get());

        json.addProperty("index", index);
        json.addProperty("type", film.camera.getFactory().getType(clip).toString());
        json.addProperty("frame", start);
        json.addProperty("frames", FrameGrid.nearestFrame(clip.tick.get() + clip.duration.get()) - start);
        json.addProperty("layer", clip.layer.get());
        json.addProperty("enabled", clip.enabled.get());

        String text = DataToString.toString(data, true);

        try
        {
            json.add("clip", JsonParser.parseString(text));
        }
        catch (JsonParseException e)
        {
            json.addProperty("clipText", text);
        }

        return json;
    }

    private static JsonObject describeView(UIFilmPanel panel)
    {
        Position view = new Position(panel.getCamera());
        JsonObject json = new JsonObject();

        json.addProperty("x", view.point.x);
        json.addProperty("y", view.point.y);
        json.addProperty("z", view.point.z);
        json.addProperty("yaw", view.angle.yaw);
        json.addProperty("pitch", view.angle.pitch);
        json.addProperty("roll", view.angle.roll);
        json.addProperty("fov", view.angle.fov);

        return json;
    }

    /* Reading what a tool sent */

    private static JsonArray getKeys(JsonObject body)
    {
        if (!body.has("keys") || !body.get("keys").isJsonArray() || body.getAsJsonArray("keys").isEmpty())
        {
            throw new IllegalArgumentException("Send a non-empty 'keys' array");
        }

        for (JsonElement key : body.getAsJsonArray("keys"))
        {
            if (!key.isJsonObject())
            {
                throw new IllegalArgumentException("Every key must be an object");
            }
        }

        return body.getAsJsonArray("keys");
    }

    private static int getFrame(JsonObject json, String name)
    {
        if (!json.has(name))
        {
            throw new IllegalArgumentException("Every key needs a '" + name + "'");
        }

        double frame = json.get(name).getAsDouble();

        if (frame < 0 || frame != Math.rint(frame))
        {
            throw new IllegalArgumentException("'" + name + "' must be a whole number of frames at 24 fps, not " + frame);
        }

        return (int) frame;
    }

    private static String getString(JsonObject json, String name, String orDefault)
    {
        return json.has(name) ? json.get(name).getAsString() : orDefault;
    }

    private static boolean getBoolean(JsonObject json, String name)
    {
        return json.has(name) && json.get(name).getAsBoolean();
    }
}
