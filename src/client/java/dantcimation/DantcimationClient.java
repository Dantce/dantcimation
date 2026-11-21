package dantcimation;

import com.google.gson.JsonObject;
import dantcimation.bridge.Bridge;
import dantcimation.bridge.BridgeServer;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.BBSClientReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Client entry point of Dantcimation, registered under {@code bbs-client-addon} like any other
 * BBS addon so that it starts through the same door and nothing in BBS has to know about it.
 */
public class DantcimationClient implements BBSAddonMod
{
    private static final Logger LOGGER = LoggerFactory.getLogger("dantcimation");

    private static BridgeServer server;

    /**
     * Where the port and the token of this session are written. A tool that wants to talk to
     * the game reads them from here; the token changes every launch.
     */
    public static File getBridgeFile()
    {
        return new File(new File(BBSMod.getSettingsFolder().getParentFile(), "dantcimation"), "bridge.json");
    }

    @Subscribe
    public void onClientReady(BBSClientReadyEvent event)
    {
        byte[] bytes = new byte[16];

        new SecureRandom().nextBytes(bytes);

        String token = HexFormat.of().formatHex(bytes);

        server = new BridgeServer(token, new Bridge());

        try
        {
            int port = server.start(Bridge.DEFAULT_PORT);
            File file = getBridgeFile();
            JsonObject json = new JsonObject();

            json.addProperty("url", "http://127.0.0.1:" + port);
            json.addProperty("port", port);
            json.addProperty("token", token);

            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), json.toString(), StandardCharsets.UTF_8);

            LOGGER.info("Dantcimation bridge is listening on 127.0.0.1:{}; connection details are in {}", port, file.getAbsolutePath());
        }
        catch (IOException e)
        {
            LOGGER.error("Dantcimation bridge could not start", e);
        }
    }
}
