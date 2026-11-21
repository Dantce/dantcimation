package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.RigBone;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.ReplayFactory;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Changes the film that is open in the film editor on behalf of a tool.
 *
 * <p>Tools speak frames at 24 fps, degrees and named bones; this is where that turns into BBS
 * keyframes. Every key it writes is constant and on the frame grid, because that is what the
 * style guide asks of a character — a tool cannot write anything else by accident.</p>
 *
 * <p>Each change is one {@link BaseValue#edit}: that is what makes it a single undo step in the
 * editor and what sends it to the server's copy of the film. A problem with what a tool sent is
 * reported as an {@link IllegalArgumentException}, before anything is changed.</p>
 *
 * <p>Like {@link FilmInspector}, this must be called on the client thread.</p>
 */
public final class FilmEditor
{
    public static final String DEFAULT_MODEL = "player/steve";

    private FilmEditor()
    {}

    /* Actors */

    /**
     * Add an actor to the film. Without a position it stands where the player is, facing the way
     * the player faces, which is the spot the person at the keyboard is looking at the scene from.
     */
    public static JsonObject addActor(UIFilmPanel panel, Film film, JsonObject body)
    {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        String model = getString(body, "model", DEFAULT_MODEL);
        String name = getString(body, "name", "");
        double x = getDouble(body, "x", player == null ? 0D : player.getX());
        double y = getDouble(body, "y", player == null ? 0D : player.getY());
        double z = getDouble(body, "z", player == null ? 0D : player.getZ());
        float yaw = (float) getDouble(body, "yaw", player == null ? 0D : player.getYaw());
        Form form = BBSMod.getForms().create(Link.bbs("model"));

        if (!(form instanceof ModelForm modelForm))
        {
            throw new IllegalStateException("BBS did not hand back a model form");
        }

        modelForm.model.set(model);

        Replay[] added = new Replay[1];

        BaseValue.edit(film.replays, (replays) ->
        {
            Replay replay = ReplayFactory.atPosition(film, new Vector3d(x, y, z), 0F, yaw);

            replay.form.set(form);
            replay.label.set(name);

            added[0] = replay;
        });

        /* The same tail the editor's own "add replay" runs: rebuild the rows, focus the
         * newcomer, rebuild the bodies */
        panel.replayEditor.setFilm(film);
        panel.replayEditor.setReplay(added[0]);
        panel.getController().createEntities();
        panel.replayEditor.updateChannelsList();

        JsonObject json = new JsonObject();

        json.addProperty("index", film.replays.getList().indexOf(added[0]));
        json.addProperty("id", added[0].getId());
        json.addProperty("model", model);

        return json;
    }

    public static Replay getActor(Film film, int index)
    {
        List<Replay> replays = film.replays.getList();

        if (index < 0 || index >= replays.size())
        {
            throw new IllegalArgumentException("There is no actor " + index + "; the film has " + replays.size());
        }

        return replays.get(index);
    }

    /**
     * The bones of the actor's model and what each hangs from. Models load in the background, so
     * right after an actor is added this can still be on its way.
     */
    public static JsonObject getRig(Replay replay)
    {
        ModelForm form = getModelForm(replay);
        ModelInstance instance = BBSModClient.getModels().getModel(form.model.get());

        if (instance == null)
        {
            throw new IllegalStateException("Model '" + form.model.get() + "' is not loaded yet; ask again in a moment");
        }

        IModel model = instance.getModel();
        JsonObject json = new JsonObject();
        JsonArray bones = new JsonArray();

        for (String name : new TreeSet<>(model.getAllGroupKeys()))
        {
            JsonObject bone = new JsonObject();
            RigBone rigBone = model.getBone(name);
            RigBone parent = rigBone == null ? null : rigBone.getParentBone();

            bone.addProperty("name", name);

            if (parent != null)
            {
                bone.addProperty("parent", parent.getBoneName());
            }

            bones.add(bone);
        }

        json.addProperty("model", form.model.get());
        json.add("bones", bones);

        return json;
    }

    /* Keys */

    /**
     * Write whole-pose keys. Each key replaces the pose at its frame: a bone the key does not
     * mention is at rest there.
     */
    public static JsonObject setPoseKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        ModelForm form = getModelForm(replay);
        JsonArray keys = getKeys(body);
        Collection<String> bones = getLoadedBones(form);
        int[] frames = new int[keys.size()];
        Pose[] poses = new Pose[keys.size()];

        /* Read everything before changing anything, so a bad key leaves the film as it was */
        for (int i = 0; i < frames.length; i++)
        {
            JsonObject key = keys.get(i).getAsJsonObject();

            frames[i] = getFrame(key);
            poses[i] = readPose(key, bones);
        }

        KeyframeChannel channel = replay.properties.getOrCreate(form, FormProperties.POSE_PROPERTY);

        if (channel == null)
        {
            throw new IllegalStateException("This actor's form has no pose track");
        }

        BaseValue.edit(replay.properties, (properties) ->
        {
            for (int i = 0; i < frames.length; i++)
            {
                insertConstant(channel, frames[i], poses[i]);
            }
        });

        refresh(panel, replay);

        return written(frames.length);
    }

    /**
     * Write where the actor stands and which way it faces. Only what a key names is written, so
     * a key can turn the actor without moving it.
     */
    public static JsonObject setRootKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        JsonArray keys = getKeys(body);

        for (JsonElement key : keys)
        {
            getFrame(key.getAsJsonObject());
        }

        BaseValue.edit(replay.keyframes, (keyframes) ->
        {
            for (JsonElement element : keys)
            {
                JsonObject key = element.getAsJsonObject();
                int frame = getFrame(key);

                insertDouble(keyframes.x, frame, key, "x");
                insertDouble(keyframes.y, frame, key, "y");
                insertDouble(keyframes.z, frame, key, "z");
                insertDouble(keyframes.pitch, frame, key, "pitch");

                if (key.has("yaw"))
                {
                    /* The body and the head follow the facing unless the key says otherwise */
                    double yaw = key.get("yaw").getAsDouble();

                    insertConstant(keyframes.yaw, frame, yaw);
                    insertConstant(keyframes.bodyYaw, frame, getDouble(key, "bodyYaw", yaw));
                    insertConstant(keyframes.headYaw, frame, getDouble(key, "headYaw", yaw));
                }
            }
        });

        refresh(panel, replay);

        return written(keys.size());
    }

    /** Remove the pose keys of a frame range, both ends included. */
    public static JsonObject clearPoseKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        int from = getInt(body, "fromFrame", 0);
        int to = getInt(body, "toFrame", Integer.MAX_VALUE);
        KeyframeChannel channel = replay.properties.getOrCreate(getModelForm(replay), FormProperties.POSE_PROPERTY);
        int[] removed = new int[1];

        if (channel != null)
        {
            BaseValue.edit(replay.properties, (properties) ->
            {
                List<?> keyframes = channel.getKeyframes();

                for (int i = keyframes.size() - 1; i >= 0; i--)
                {
                    int frame = FrameGrid.nearestFrame(((Keyframe<?>) keyframes.get(i)).getTick());

                    if (frame >= from && frame <= to)
                    {
                        channel.remove(i);

                        removed[0] += 1;
                    }
                }
            });
        }

        refresh(panel, replay);

        JsonObject json = new JsonObject();

        json.addProperty("removed", removed[0]);

        return json;
    }

    /** Move the editor's playhead, so the person watching sees the frame a tool is working on. */
    public static JsonObject seek(UIFilmPanel panel, JsonObject body)
    {
        int frame = getFrame(body);
        JsonObject json = new JsonObject();

        panel.setCursor(FrameGrid.toTick(frame));
        json.addProperty("frame", frame);

        return json;
    }

    private static void insertDouble(KeyframeChannel<Double> channel, int frame, JsonObject key, String name)
    {
        if (key.has(name))
        {
            insertConstant(channel, frame, key.get(name).getAsDouble());
        }
    }

    /** A key on the frame grid that holds its value until the next one. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void insertConstant(KeyframeChannel channel, int frame, Object value)
    {
        int index = channel.insert(FrameGrid.toTick(frame), value);

        channel.get(index).getInterpolation().setInterp(Interpolations.CONST);
    }

    private static void refresh(UIFilmPanel panel, Replay replay)
    {
        if (panel.replayEditor.getReplay() == replay)
        {
            panel.replayEditor.updateChannelsList();
        }
    }

    /* Reading what a tool sent */

    /**
     * A pose as tools write it: {@code {"pose": {"<bone>": {"r": [x, y, z], "t": [...],
     * "s": [...]}}}}, rotation in degrees, translation in model pixels.
     *
     * <p>Every bone it sets is fixed to the pose, so that the model's own procedural movement —
     * vanilla's limb swing — does not show through a keyed pose.</p>
     */
    private static Pose readPose(JsonObject key, Collection<String> bones)
    {
        if (!key.has("pose") || !key.get("pose").isJsonObject())
        {
            throw new IllegalArgumentException("A pose key needs a 'pose' object of bones");
        }

        Pose pose = new Pose();

        for (Map.Entry<String, JsonElement> entry : key.getAsJsonObject("pose").entrySet())
        {
            String bone = entry.getKey();

            if (bones != null && !bones.contains(bone))
            {
                throw new IllegalArgumentException("Unknown bone '" + bone + "'. This model has: " + String.join(", ", new TreeSet<>(bones)));
            }

            JsonObject json = entry.getValue().getAsJsonObject();
            PoseTransform transform = pose.getOrCreate(bone);

            if (json.has("r"))
            {
                JsonArray r = getVector(json, "r");

                transform.rotate.set(
                    (float) Math.toRadians(r.get(0).getAsDouble()),
                    (float) Math.toRadians(r.get(1).getAsDouble()),
                    (float) Math.toRadians(r.get(2).getAsDouble())
                );
            }

            if (json.has("t"))
            {
                JsonArray t = getVector(json, "t");

                transform.translate.set(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
            }

            if (json.has("s"))
            {
                JsonArray s = getVector(json, "s");

                transform.scale.set(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
            }

            transform.fix = 1F;
        }

        return pose;
    }

    private static JsonArray getVector(JsonObject json, String name)
    {
        JsonElement element = json.get(name);

        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3)
        {
            throw new IllegalArgumentException("'" + name + "' must be three numbers");
        }

        return element.getAsJsonArray();
    }

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

    private static int getFrame(JsonObject key)
    {
        if (!key.has("frame"))
        {
            throw new IllegalArgumentException("Every key needs a 'frame'");
        }

        double frame = key.get("frame").getAsDouble();

        if (frame < 0 || frame != Math.rint(frame))
        {
            throw new IllegalArgumentException("'frame' must be a whole number of frames at 24 fps, not " + frame);
        }

        return (int) frame;
    }

    private static ModelForm getModelForm(Replay replay)
    {
        if (replay.form.get() instanceof ModelForm modelForm)
        {
            return modelForm;
        }

        throw new IllegalArgumentException("This actor is not a model");
    }

    /** The model's bone names, or {@code null} while the model is still loading. */
    private static Collection<String> getLoadedBones(ModelForm form)
    {
        ModelInstance instance = BBSModClient.getModels().getModel(form.model.get());

        return instance == null ? null : instance.getModel().getAllGroupKeys();
    }

    private static JsonObject written(int count)
    {
        JsonObject json = new JsonObject();

        json.addProperty("written", count);

        return json;
    }

    private static String getString(JsonObject json, String name, String orDefault)
    {
        return json.has(name) ? json.get(name).getAsString() : orDefault;
    }

    private static double getDouble(JsonObject json, String name, double orDefault)
    {
        return json.has(name) ? json.get(name).getAsDouble() : orDefault;
    }

    private static int getInt(JsonObject json, String name, int orDefault)
    {
        return json.has(name) ? json.get(name).getAsInt() : orDefault;
    }
}
