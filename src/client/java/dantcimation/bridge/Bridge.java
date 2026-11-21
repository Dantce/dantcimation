package dantcimation.bridge;

import com.google.gson.JsonObject;
import dantcimation.bridge.BridgeServer.Request;
import dantcimation.bridge.BridgeServer.Response;
import dantcimation.style.Cadence;
import dantcimation.style.FrameGrid;
import mchorse.bbs_mod.film.Film;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

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
    public static final int VERSION = 1;

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
        if (!request.method().equals("GET"))
        {
            return Response.error(404, "Unknown request: " + request.method() + " " + request.path());
        }

        if (request.path().equals("/status"))
        {
            return Response.ok(this.status().toString());
        }

        if (request.path().equals("/film"))
        {
            Film film = FilmInspector.getOpenFilm();

            return film == null ? noFilm() : Response.ok(FilmInspector.describe(film).toString());
        }

        if (request.path().equals("/film/validate"))
        {
            Film film = FilmInspector.getOpenFilm();
            Cadence cadence = Cadence.byKey(request.query().getOrDefault("cadence", Cadence.ACTING.key));

            if (cadence == null)
            {
                return Response.error(400, "Unknown cadence; use 'acting' or 'action'");
            }

            return film == null ? noFilm() : Response.ok(FilmInspector.validate(film, cadence).toString());
        }

        return Response.error(404, "Unknown request: " + request.method() + " " + request.path());
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

    private static Response noFilm()
    {
        return Response.error(409, "No film is open. Open one in the BBS film editor first.");
    }

    private static String getVersion(String mod)
    {
        return FabricLoader.getInstance().getModContainer(mod)
            .map((container) -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
