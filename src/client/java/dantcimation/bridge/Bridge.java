package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dantcimation.bridge.BridgeServer.Request;
import dantcimation.bridge.BridgeServer.Response;
import dantcimation.bridge.Frames.Frame;
import dantcimation.style.Cadence;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * What the bridge answers. Every request is carried over to the client thread before it touches
 * the game, because that is the only thread the film editor's state may be read or changed on.
 */
public class Bridge implements BridgeServer.Handler
{
    public static final int DEFAULT_PORT = 47110;

    /** Bumped whenever a request or an answer changes in a way a tool cannot survive. */
    public static final int VERSION = 7;

    private static final int TIMEOUT_SECONDS = 10;

    /**
     * How long the game is given to draw a frame after the playhead moved. An unfocused game
     * draws as few as ten frames a second, so this is a couple of those.
     */
    private static final int SETTLE_MILLIS = 250;

    @Override
    public Response handle(Request request) throws Exception
    {
        try
        {
            if (request.method().equals("GET") && request.path().equals("/render"))
            {
                return this.render(request);
            }

            return this.onClient(() -> this.route(request));
        }
        catch (TimeoutException e)
        {
            return Response.error(503, "The game did not answer within " + TIMEOUT_SECONDS + " seconds");
        }
    }

    private <T> T onClient(Supplier<T> supplier) throws Exception
    {
        return CompletableFuture
            .supplyAsync(supplier, MinecraftClient.getInstance())
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Response route(Request request)
    {
        try
        {
            JsonObject answer = request.method().equals("POST") ? this.post(request) : this.get(request);

            if (answer == null)
            {
                return Response.error(404, "Unknown request: " + request.method() + " " + request.path());
            }

            return Response.ok(answer.toString());
        }
        catch (RuntimeException e)
        {
            return toError(e);
        }
    }

    private static Response toError(Throwable e)
    {
        if (e instanceof NoFilm)
        {
            return Response.error(409, "No film is open. Open one in the BBS film editor first.");
        }

        if (e instanceof IllegalArgumentException || e instanceof JsonParseException)
        {
            /* What the tool sent is wrong, and the message says how */
            return Response.error(400, e.getMessage());
        }

        if (e instanceof IllegalStateException)
        {
            /* The request is fine, the game is not ready for it */
            return Response.error(409, e.getMessage());
        }

        return Response.error(500, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /**
     * The preview as a picture: what it shows right now, or a contact sheet of the frames asked
     * for with {@code frames=0,9,12}.
     *
     * <p>Unlike every other request this one cannot be answered in a single visit to the client
     * thread: the playhead has to be moved, the game has to draw the frame, and only then is
     * there something to read back. So it is run from the bridge thread, which waits in between.</p>
     */
    private Response render(Request request) throws Exception
    {
        List<Frame> grabbed = new ArrayList<>();
        int[] frames;
        int columns;
        int cellWidth;

        try
        {
            frames = parseFrames(request.query().getOrDefault("frames", ""));
            columns = Integer.parseInt(request.query().getOrDefault("columns", String.valueOf(Math.max(1, Math.min(4, frames.length)))));
            cellWidth = Integer.parseInt(request.query().getOrDefault("width", String.valueOf(Frames.DEFAULT_CELL_WIDTH)));

            if (columns < 1 || columns > 8 || cellWidth < 64 || cellWidth > 1920)
            {
                throw new IllegalArgumentException("'columns' is 1 to 8 and 'width' is 64 to 1920");
            }
        }
        catch (IllegalArgumentException e)
        {
            return toError(e);
        }

        try
        {
            if (frames.length == 0)
            {
                grabbed.add(this.onClient(FrameGrabber::grab));

                return Response.png(Frames.toPng(Frames.toImage(grabbed.get(0))));
            }

            int cursor = this.onClient(() -> panel().getCursor());

            for (int frame : frames)
            {
                this.onClient(() ->
                {
                    panel().setCursor(FrameGrid.toTick(frame));

                    return null;
                });

                Thread.sleep(SETTLE_MILLIS);
                grabbed.add(this.onClient(FrameGrabber::grab));
            }

            this.onClient(() ->
            {
                panel().setCursor(cursor);

                return null;
            });
        }
        catch (ExecutionException e)
        {
            return toError(e.getCause());
        }

        return Response.png(Frames.toPng(Frames.sheet(grabbed, frames, columns, cellWidth)));
    }

    private static int[] parseFrames(String list)
    {
        if (list.isBlank())
        {
            return new int[0];
        }

        String[] parts = list.split(",");

        if (parts.length > Frames.MAX_FRAMES)
        {
            throw new IllegalArgumentException("At most " + Frames.MAX_FRAMES + " frames a sheet");
        }

        int[] frames = new int[parts.length];

        for (int i = 0; i < parts.length; i++)
        {
            frames[i] = Integer.parseInt(parts[i].trim());

            if (frames[i] < 0)
            {
                throw new IllegalArgumentException("Frames are not negative");
            }
        }

        return frames;
    }

    /** The film editor, with a film in it. */
    private static UIFilmPanel panel()
    {
        film();

        return FilmInspector.getPanel();
    }

    private JsonObject get(Request request)
    {
        String path = request.path();

        if (path.equals("/status"))
        {
            return this.status();
        }

        if (path.equals("/models"))
        {
            JsonObject json = new JsonObject();
            JsonArray models = new JsonArray();

            for (String key : new TreeSet<>(BBSModClient.getModels().getAvailableKeys()))
            {
                models.add(key);
            }

            json.add("models", models);

            return json;
        }

        if (path.equals("/film"))
        {
            return FilmInspector.describe(film());
        }

        if (path.equals("/film/camera"))
        {
            return CameraEditor.describe(panel(), film());
        }

        if (path.equals("/film/validate"))
        {
            Cadence cadence = Cadence.byKey(request.query().getOrDefault("cadence", Cadence.ACTING.key));

            if (cadence == null)
            {
                throw new IllegalArgumentException("Unknown cadence; use 'acting' or 'action'");
            }

            return FilmInspector.validate(film(), cadence);
        }

        String[] actor = matchActor(path);

        if (actor != null && actor[1].equals("rig"))
        {
            return FilmEditor.getRig(FilmEditor.getActor(film(), Integer.parseInt(actor[0])));
        }

        if (actor != null && actor[1].equals("pose-keys"))
        {
            return FilmEditor.getPoseKeys(FilmEditor.getActor(film(), Integer.parseInt(actor[0])), request.query());
        }

        if (actor != null && actor[1].equals("property-keys"))
        {
            return FilmEditor.getPropertyKeys(FilmEditor.getActor(film(), Integer.parseInt(actor[0])), request.query());
        }

        if (actor != null && actor[1].equals("form"))
        {
            return FilmEditor.describeForm(FilmEditor.getActor(film(), Integer.parseInt(actor[0])));
        }

        return null;
    }

    private JsonObject post(Request request)
    {
        String path = request.path();
        Film film = film();
        UIFilmPanel panel = FilmInspector.getPanel();
        JsonElement parsed = request.body().isBlank() ? new JsonObject() : JsonParser.parseString(request.body());

        if (!parsed.isJsonObject())
        {
            throw new IllegalArgumentException("The body must be a JSON object");
        }

        JsonObject body = parsed.getAsJsonObject();

        if (path.equals("/film/actors"))
        {
            return FilmEditor.addActor(panel, film, body);
        }

        if (path.equals("/film/seek"))
        {
            return FilmEditor.seek(panel, body);
        }

        if (path.equals("/film/regrid"))
        {
            return FilmEditor.regrid(panel, film);
        }

        if (path.equals("/film/camera/clips"))
        {
            return CameraEditor.addClip(panel, film, body);
        }

        if (path.equals("/film/camera/moves"))
        {
            return CameraEditor.addMove(panel, film, body);
        }

        if (path.equals("/film/camera/clear"))
        {
            return CameraEditor.clear(panel, film);
        }

        String clip = matchIndexed(path, "/film/camera/clips/", "/remove");

        if (clip != null)
        {
            return CameraEditor.removeClip(panel, film, Integer.parseInt(clip));
        }

        String[] actor = matchActor(path);

        if (actor == null)
        {
            return null;
        }

        Replay replay = FilmEditor.getActor(film, Integer.parseInt(actor[0]));

        return switch (actor[1])
        {
            case "pose-keys" -> FilmEditor.setPoseKeys(panel, replay, body);
            case "root-keys" -> FilmEditor.setRootKeys(panel, replay, body);
            case "pose-keys/clear" -> FilmEditor.clearPoseKeys(panel, replay, body);
            case "root-keys/clear" -> FilmEditor.clearRootKeys(panel, replay, body);
            case "property-keys" -> FilmEditor.setPropertyKeys(panel, replay, body);
            case "property-keys/clear" -> FilmEditor.clearPropertyKeys(panel, replay, body);
            case "equipment" -> FilmEditor.setEquipment(panel, replay, body);
            case "form" -> FilmEditor.setForm(panel, replay, body);
            case "remove" -> FilmEditor.removeActor(panel, film, replay);
            default -> null;
        };
    }

    /**
     * Split {@code /film/actors/<index>/<what>} into the index and what is asked of that actor;
     * {@code null} when the path is not of that shape.
     */
    private static String[] matchActor(String path)
    {
        String prefix = "/film/actors/";

        if (!path.startsWith(prefix))
        {
            return null;
        }

        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');

        if (slash <= 0 || slash == rest.length() - 1)
        {
            return null;
        }

        String index = rest.substring(0, slash);

        for (int i = 0; i < index.length(); i++)
        {
            if (!Character.isDigit(index.charAt(i)))
            {
                return null;
            }
        }

        return new String[] {index, rest.substring(slash + 1)};
    }

    /** The index out of {@code <prefix><index><suffix>}; {@code null} when the path is not of that shape. */
    private static String matchIndexed(String path, String prefix, String suffix)
    {
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length() <= prefix.length() + suffix.length())
        {
            return null;
        }

        String index = path.substring(prefix.length(), path.length() - suffix.length());

        for (int i = 0; i < index.length(); i++)
        {
            if (!Character.isDigit(index.charAt(i)))
            {
                return null;
            }
        }

        return index.length() > 9 ? null : index;
    }

    private static Film film()
    {
        Film film = FilmInspector.getOpenFilm();

        if (film == null)
        {
            throw new NoFilm();
        }

        return film;
    }

    private JsonObject status()
    {
        JsonObject json = new JsonObject();
        Film film = FilmInspector.getOpenFilm();

        json.addProperty("name", "dantcimation");
        json.addProperty("bridge", VERSION);
        json.addProperty("minecraft", getVersion("minecraft"));
        json.addProperty("bbs", getVersion("bbs"));
        json.addProperty("framesPerSecond", FrameGrid.FRAMES_PER_SECOND);
        json.addProperty("inWorld", MinecraftClient.getInstance().world != null);
        json.addProperty("filmOpen", film != null);

        if (film != null)
        {
            json.addProperty("film", film.getId());
        }

        if (MinecraftClient.getInstance().world != null)
        {
            /* Where the picture is taken from right now. An effect that has to sit behind an
             * actor, or square to the lens, is placed from this. */
            Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
            JsonObject view = new JsonObject();

            view.addProperty("x", camera.getCameraPos().x);
            view.addProperty("y", camera.getCameraPos().y);
            view.addProperty("z", camera.getCameraPos().z);
            view.addProperty("yaw", camera.getYaw());
            view.addProperty("pitch", camera.getPitch());

            json.add("camera", view);
        }

        return json;
    }

    private static String getVersion(String mod)
    {
        return FabricLoader.getInstance().getModContainer(mod)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    /** Almost every request is about the open film; this is how they all say there is none. */
    private static class NoFilm extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }
}
