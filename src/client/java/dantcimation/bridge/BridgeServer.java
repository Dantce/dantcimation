package dantcimation.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The door an outside process uses to reach the mod: a deliberately small HTTP server.
 *
 * <p>It only listens on the loopback address, answers nothing without the session token, and
 * turns away anything another web page could send (a request carrying a foreign
 * {@code Origin}), so having the game open does not open the films to the network or to the
 * browser.</p>
 *
 * <p>It is written on a plain {@link ServerSocket} rather than the JDK's HTTP server, because
 * launchers ship trimmed Java runtimes and that module is not guaranteed to be in them. It knows
 * nothing about the game either: what a request means is up to the {@link Handler}.</p>
 */
public class BridgeServer
{
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_LINE = 16 * 1024;
    private static final int TIMEOUT = 10000;

    private final Handler handler;
    private final String token;

    private ServerSocket socket;
    private ExecutorService workers;
    private volatile boolean running;

    public BridgeServer(String token, Handler handler)
    {
        this.token = token;
        this.handler = handler;
    }

    /**
     * Start listening on the preferred port, or on any free one when it is taken.
     *
     * @return the port that was bound
     */
    public int start(int preferredPort) throws IOException
    {
        InetAddress loopback = InetAddress.getLoopbackAddress();

        try
        {
            this.socket = new ServerSocket(preferredPort, 16, loopback);
        }
        catch (IOException e)
        {
            this.socket = new ServerSocket(0, 16, loopback);
        }

        this.running = true;
        this.workers = Executors.newFixedThreadPool(2, (runnable) -> daemon(runnable, "Dantcimation bridge worker"));

        daemon(this::acceptLoop, "Dantcimation bridge").start();

        return this.socket.getLocalPort();
    }

    public void stop()
    {
        this.running = false;

        try
        {
            if (this.socket != null)
            {
                this.socket.close();
            }
        }
        catch (IOException e)
        {}

        if (this.workers != null)
        {
            this.workers.shutdownNow();
        }
    }

    private static Thread daemon(Runnable runnable, String name)
    {
        Thread thread = new Thread(runnable, name);

        thread.setDaemon(true);

        return thread;
    }

    private void acceptLoop()
    {
        while (this.running)
        {
            try
            {
                Socket client = this.socket.accept();

                this.workers.execute(() -> this.serve(client));
            }
            catch (IOException e)
            {
                /* Closing the socket is how the loop is stopped */
            }
        }
    }

    private void serve(Socket client)
    {
        try (Socket connection = client)
        {
            connection.setSoTimeout(TIMEOUT);

            Response response;

            try
            {
                response = this.respond(connection.getInputStream());
            }
            catch (BadRequest e)
            {
                response = Response.error(e.status, e.getMessage());
            }
            catch (Exception e)
            {
                response = Response.error(500, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            write(connection.getOutputStream(), response);
        }
        catch (IOException e)
        {
            /* The other side went away; there is nobody left to tell */
        }
    }

    private Response respond(InputStream input) throws Exception
    {
        String requestLine = readLine(input);
        String[] parts = requestLine == null ? new String[0] : requestLine.split(" ");

        if (parts.length != 3)
        {
            throw new BadRequest(400, "Malformed request line");
        }

        Map<String, String> headers = new HashMap<>();
        String line;

        while ((line = readLine(input)) != null && !line.isEmpty())
        {
            int colon = line.indexOf(':');

            if (colon > 0)
            {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }

        String target = parts[1];
        int question = target.indexOf('?');
        String path = question < 0 ? target : target.substring(0, question);
        Map<String, String> query = parseQuery(question < 0 ? "" : target.substring(question + 1));

        if (!this.isOwnOrigin(headers.get("origin")))
        {
            throw new BadRequest(403, "Requests from web pages are not accepted");
        }

        if (!this.isAuthorized(headers, query))
        {
            throw new BadRequest(401, "Missing or wrong token");
        }

        return this.handler.handle(new Request(parts[0].toUpperCase(Locale.ROOT), path, query, readBody(input, headers)));
    }

    /**
     * A browser names the page a request comes from, and no page can forge that. Only a page
     * this server served itself may talk to it; anything without an origin is not a browser.
     */
    private boolean isOwnOrigin(String origin)
    {
        if (origin == null)
        {
            return true;
        }

        int port = this.socket.getLocalPort();

        return origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port);
    }

    private boolean isAuthorized(Map<String, String> headers, Map<String, String> query)
    {
        String header = headers.get("authorization");

        if (header != null && header.startsWith("Bearer "))
        {
            return this.token.equals(header.substring("Bearer ".length()).trim());
        }

        return this.token.equals(query.get("token"));
    }

    private static String readBody(InputStream input, Map<String, String> headers) throws IOException, BadRequest
    {
        String header = headers.get("content-length");

        if (header == null)
        {
            return "";
        }

        int length;

        try
        {
            length = Integer.parseInt(header);
        }
        catch (NumberFormatException e)
        {
            throw new BadRequest(400, "Malformed Content-Length");
        }

        if (length < 0 || length > MAX_BODY)
        {
            throw new BadRequest(413, "Body is larger than " + MAX_BODY + " bytes");
        }

        byte[] body = input.readNBytes(length);

        if (body.length != length)
        {
            throw new BadRequest(400, "Body is shorter than its Content-Length");
        }

        return new String(body, StandardCharsets.UTF_8);
    }

    /** One header line, without its line ending; {@code null} at the end of the stream. */
    private static String readLine(InputStream input) throws IOException, BadRequest
    {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int read;

        while ((read = input.read()) != -1)
        {
            if (read == '\n')
            {
                return line.toString(StandardCharsets.ISO_8859_1);
            }

            if (read != '\r')
            {
                line.write(read);
            }

            if (line.size() > MAX_LINE)
            {
                throw new BadRequest(431, "Header line is too long");
            }
        }

        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseQuery(String query)
    {
        Map<String, String> result = new HashMap<>();

        for (String pair : query.split("&"))
        {
            if (pair.isEmpty())
            {
                continue;
            }

            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);

            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }

        return result;
    }

    private static void write(OutputStream output, Response response) throws IOException
    {
        byte[] body = response.json().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + response.status() + " " + reason(response.status()) + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";

        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static String reason(int status)
    {
        return switch (status)
        {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 431 -> "Request Header Fields Too Large";
            default -> "Error";
        };
    }

    /** Answers one request. Called on a bridge thread, never on the game's. */
    public interface Handler
    {
        public Response handle(Request request) throws Exception;
    }

    public record Request(String method, String path, Map<String, String> query, String body)
    {}

    public record Response(int status, String json)
    {
        public static Response ok(String json)
        {
            return new Response(200, json);
        }

        public static Response error(int status, String message)
        {
            String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");

            return new Response(status, "{\"error\":\"" + escaped + "\"}");
        }
    }

    private static class BadRequest extends Exception
    {
        private static final long serialVersionUID = 1L;

        public final int status;

        public BadRequest(int status, String message)
        {
            super(message);

            this.status = status;
        }
    }
}
