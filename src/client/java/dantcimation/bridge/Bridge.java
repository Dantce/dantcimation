package dantcimation.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dantcimation.bridge.BridgeServer.Request;
import dantcimation.bridge.BridgeServer.Response;
import dantcimation.style.Cadence;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the bridge answers. Every request is carried over to the client thread before it touches
 * the game, because that is the only thread the film editor's state may be read or changed on.
 */
public class Bridge implements BridgeServer.Handler
{
    public static final int DEFAULT_PORT = 47110;

    /** Bumped whenever a request or an answer changes in a way a tool cannot survive. */
    public static final int VERSION = 2;

    private static final int TIMEOUT_SECONDS = 10;

    @Override
    public Response handle(Request request) throws Exception
    {
        try
        {
            return CompletableFuture
                .supplyAsync(() -> this.route(request), MinecraftClient.getInstance())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            return Response.error(503, "The game did not answer within " + TIMEOUT_SECONDS + " seconds");
        }
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
        catch (NoFilm e)
        {
            return Response.error(409, "No film is open. Open one in the BBS film editor first.");
        }
        catch (IllegalArgumentException | JsonParseException e)
        {
            /* What the tool sent is wrong, and the message says how */
            return Response.error(400, e.getMessage());
        }
        catch (IllegalStateException e)
        {
            /* The request is fine, the game is not ready for it */
            return Response.error(409, e.getMessage());
        }
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
