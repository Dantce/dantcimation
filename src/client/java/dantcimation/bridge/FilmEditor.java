package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.RigBone;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.UnknownForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.ReplayFactory;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
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
 * <p>Anything that is not a character — a backdrop, a flash, a prop — is a form too, and BBS
 * already has a way to write one down. So a tool hands over a form as the data BBS itself saves,
 * and keys its properties by the path the timeline shows them under; nothing here knows what an
 * impact frame is.</p>
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
        Form form = body.has("form") ? readForm(body) : createModel(model);
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

        if (form instanceof ModelForm modelForm)
        {
            json.addProperty("model", modelForm.model.get());
        }

        return json;
    }

    /** Take an actor out of the film, with everything keyed on it. */
    public static JsonObject removeActor(UIFilmPanel panel, Film film, Replay replay)
    {
        int index = film.replays.getList().indexOf(replay);

        film.replays.remove(replay);

        List<Replay> remaining = film.replays.getList();

        panel.replayEditor.setFilm(film);
        panel.replayEditor.setReplay(remaining.isEmpty() ? null : remaining.get(Math.min(index, remaining.size() - 1)));
        panel.getController().createEntities();

        JsonObject json = new JsonObject();

        json.addProperty("removed", index);
        json.addProperty("actors", remaining.size());

        return json;
    }

    /**
     * Give an actor another form. Its keys stay; the ones whose property the new form does not
     * have simply stop doing anything, as they do when a form is swapped in the editor.
     */
    public static JsonObject setForm(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        Form form = readForm(body);

        BaseValue.edit(replay.form, (value) -> value.set(form));

        panel.getController().createEntities();
        refresh(panel, replay);

        return describeForm(replay);
    }

    /**
     * The actor's form as BBS saves it, and the paths of the properties that can be keyed on it
     * — a body part's properties sit under the part's id.
     */
    public static JsonObject describeForm(Replay replay)
    {
        Form form = replay.form.get();
        JsonObject json = new JsonObject();
        JsonArray properties = new JsonArray();

        if (form == null)
        {
            throw new IllegalArgumentException("This actor has no form");
        }

        String text = DataToString.toString(FormUtils.toData(form), true);

        try
        {
            json.add("form", JsonParser.parseString(text));
        }
        catch (JsonParseException e)
        {
            /* Better the text than nothing, should BBS ever write something JSON cannot hold */
            json.addProperty("formText", text);
        }

        for (String path : FormUtils.collectPropertyPaths(form))
        {
            properties.add(path);
        }

        json.add("properties", properties);

        return json;
    }

    private static Form createModel(String model)
    {
        Form form = BBSMod.getForms().create(Link.bbs("model"));

        if (!(form instanceof ModelForm modelForm))
        {
            throw new IllegalStateException("BBS did not hand back a model form");
        }

        modelForm.model.set(model);

        return form;
    }

    /** A form from {@code {"form": {"id": "bbs:billboard", ...}}}, written the way BBS saves forms. */
    private static Form readForm(JsonObject body)
    {
        if (!body.has("form") || !body.get("form").isJsonObject())
        {
            throw new IllegalArgumentException("Send the form as a 'form' object, the way BBS saves it");
        }

        MapType data = DataToString.mapFromString(body.get("form").toString());
        Form form = data == null ? null : FormUtils.fromData(data);

        if (form == null || form instanceof UnknownForm)
        {
            throw new IllegalArgumentException("BBS could not make a form out of that; 'id' must name a form type, such as bbs:model or bbs:billboard");
        }

        return form;
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

    /**
     * Key a property of the actor's form — its colour, its overlay, whether it shows, its
     * transform, a property of one of its body parts. Each key holds until the next one.
     *
     * <p>A value is written the way BBS saves that kind of property. A colour may also be given
     * as {@code "#RRGGBB"} or {@code "#AARRGGBB"}, because a packed integer is no way to read
     * one.</p>
     */
    @SuppressWarnings("rawtypes")
    public static JsonObject setPropertyKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        Form form = getForm(replay);
        String path = getPropertyPath(body);
        IKeyframeFactory factory = getFactory(form, path);
        JsonArray keys = getKeys(body);
        int[] frames = new int[keys.size()];
        Object[] values = new Object[keys.size()];

        for (int i = 0; i < frames.length; i++)
        {
            JsonObject key = keys.get(i).getAsJsonObject();

            frames[i] = getFrame(key);
            values[i] = factory.fromData(readValue(key));
        }

        KeyframeChannel channel = replay.properties.getOrCreate(form, path);

        if (channel == null)
        {
            throw new IllegalStateException("BBS would not make a track for '" + path + "'");
        }

        BaseValue.edit(replay.properties, (properties) ->
        {
            for (int i = 0; i < frames.length; i++)
            {
                insertConstant(channel, frames[i], values[i]);
            }
        });

        refresh(panel, replay);

        return written(frames.length);
    }

    /** Remove the keys of one property in a frame range, both ends included. */
    public static JsonObject clearPropertyKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        KeyframeChannel<?> channel = replay.properties.get(TrackId.parse(getPropertyPath(body)));

        return clear(panel, replay, replay.properties, channel == null ? List.of() : List.of(channel), body);
    }

    /** Remove the pose keys of a frame range, both ends included. */
    public static JsonObject clearPoseKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        KeyframeChannel<?> channel = replay.properties.getOrCreate(getModelForm(replay), FormProperties.POSE_PROPERTY);

        return clear(panel, replay, replay.properties, channel == null ? List.of() : List.of(channel), body);
    }

    /**
     * Remove the keys of where the actor stands and faces in a frame range, both ends included
     * — all of them, or only the channels named in {@code "channels"}, such as {@code ["pitch"]}.
     */
    public static JsonObject clearRootKeys(UIFilmPanel panel, Replay replay, JsonObject body)
    {
        List<KeyframeChannel<?>> channels = new ArrayList<>();
        JsonArray names = body.has("channels") && body.get("channels").isJsonArray() ? body.getAsJsonArray("channels") : null;

        for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
        {
            if (names == null || contains(names, channel.getId()))
            {
                channels.add(channel);
            }
        }

        return clear(panel, replay, replay.keyframes, channels, body);
    }

    private static JsonObject clear(UIFilmPanel panel, Replay replay, BaseValue owner, List<KeyframeChannel<?>> channels, JsonObject body)
    {
        int from = getInt(body, "fromFrame", 0);
        int to = getInt(body, "toFrame", Integer.MAX_VALUE);
        int[] removed = new int[1];

        if (!channels.isEmpty())
        {
            BaseValue.edit(owner, (value) ->
            {
                for (KeyframeChannel<?> channel : channels)
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
                }
            });
        }

        refresh(panel, replay);

        JsonObject json = new JsonObject();

        json.addProperty("removed", removed[0]);

        return json;
    }

    private static boolean contains(JsonArray names, String name)
    {
        for (JsonElement element : names)
        {
            if (element.isJsonPrimitive() && element.getAsString().equals(name))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Move every key that sits on a frame to the tick keys are written at (see
     * {@link FrameGrid#toKeyTick}). Keys written before that tick was settled on, or placed by
     * hand in the editor, sit exactly on their frame, where an export shows them a frame late
     * or not by chance. Keys that are off the grid are left for the validator to report.
     */
    public static JsonObject regrid(UIFilmPanel panel, Film film)
    {
        int[] moved = new int[1];

        for (Replay replay : film.replays.getList())
        {
            BaseValue.edit(replay, (value) ->
            {
                for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
                {
                    moved[0] += regrid(channel);
                }

                for (KeyframeChannel<?> channel : replay.properties.tracks.values())
                {
                    moved[0] += regrid(channel);
                }
            });
        }

        panel.replayEditor.updateChannelsList();

        JsonObject json = new JsonObject();

        json.addProperty("moved", moved[0]);

        return json;
    }

    private static int regrid(KeyframeChannel<?> channel)
    {
        int moved = 0;

        for (Keyframe<?> keyframe : channel.getKeyframes())
        {
            float tick = keyframe.getTick();
            float target = FrameGrid.snap(tick);

            if (FrameGrid.isOnGrid(tick) && tick != target)
            {
                keyframe.setTick(target);

                moved += 1;
            }
        }

        return moved;
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
        int index = channel.insert(FrameGrid.toKeyTick(frame), value);

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

    private static Form getForm(Replay replay)
    {
        Form form = replay.form.get();

        if (form == null)
        {
            throw new IllegalArgumentException("This actor has no form");
        }

        return form;
    }

    private static String getPropertyPath(JsonObject body)
    {
        if (!body.has("property") || !body.get("property").isJsonPrimitive() || body.get("property").getAsString().isBlank())
        {
            throw new IllegalArgumentException("Name the 'property' to key, such as color_overlay");
        }

        return body.get("property").getAsString();
    }

    /** What turns saved data into a value of this property; this is also the check that it exists. */
    @SuppressWarnings("rawtypes")
    private static IKeyframeFactory getFactory(Form form, String path)
    {
        BaseValueBasic property = FormUtils.getProperty(form, path);

        if (path.substring(path.lastIndexOf('/') + 1).startsWith(FormProperties.POSE_PROPERTY))
        {
            throw new IllegalArgumentException("Poses are keyed through pose-keys, which keeps them on the frame grid");
        }

        if (property instanceof BaseKeyframeFactoryValue<?> keyed && property.isVisible())
        {
            return keyed.getFactory();
        }

        throw new IllegalArgumentException("This actor's form has no property '" + path + "' that can be keyed. It has: " + String.join(", ", FormUtils.collectPropertyPaths(form)));
    }

    private static BaseType readValue(JsonObject key)
    {
        if (!key.has("value"))
        {
            throw new IllegalArgumentException("Every key needs a 'value'");
        }

        JsonElement value = key.get("value");

        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().startsWith("#"))
        {
            return new IntType(readColor(value.getAsString()));
        }

        BaseType data = DataToString.fromString(value.toString());

        if (data == null)
        {
            throw new IllegalArgumentException("BBS could not read the value " + value);
        }

        return data;
    }

    /** {@code #RRGGBB} is opaque; {@code #AARRGGBB} says how strong, which is what an overlay's alpha means. */
    private static int readColor(String text)
    {
        String hex = text.substring(1);

        try
        {
            if (hex.length() == 6)
            {
                return 0xff000000 | Integer.parseInt(hex, 16);
            }

            if (hex.length() == 8)
            {
                return Integer.parseUnsignedInt(hex, 16);
            }
        }
        catch (NumberFormatException e)
        {}

        throw new IllegalArgumentException("A colour is #RRGGBB or #AARRGGBB, not " + text);
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
