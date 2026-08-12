package dev.dimo.paperwebconsole.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.dimo.paperwebconsole.WebConsolePlugin;
import dev.dimo.paperwebconsole.auth.AuthManager;
import dev.dimo.paperwebconsole.auth.AuthResult;
import dev.dimo.paperwebconsole.auth.SessionInfo;
import dev.dimo.paperwebconsole.config.PluginConfiguration;
import dev.dimo.paperwebconsole.console.ConsoleBuffer;
import dev.dimo.paperwebconsole.console.ConsoleEntry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class WebConsoleServer {
    private static final String SESSION_COOKIE = "paper_webconsole_session";
    private static final String ATTR_CLIENT_IP = "clientIp";
    private static final String ATTR_SESSION_TOKEN = "sessionToken";

    private final WebConsolePlugin plugin;
    private final PluginConfiguration configuration;
    private final AuthManager authManager;
    private final ConsoleBuffer consoleBuffer;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<String, WsConnectContext> webSocketClients = new ConcurrentHashMap<>();

    private Javalin app;

    public WebConsoleServer(
        WebConsolePlugin plugin,
        PluginConfiguration configuration,
        AuthManager authManager,
        ConsoleBuffer consoleBuffer
    ) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.authManager = authManager;
        this.consoleBuffer = consoleBuffer;
    }

    public void start() {
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.startup.showOldJavalinVersionWarning = false;
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/assets";
                staticFiles.directory = "/web/assets";
                staticFiles.location = Location.CLASSPATH;
            });

            config.routes.before(ctx -> {
                applySecurityHeaders(ctx);
                if (ctx.path().startsWith("/api/") && !"GET".equalsIgnoreCase(ctx.req().getMethod())) {
                    verifySameOrigin(ctx);
                }
            });

            config.routes.get("/", this::handleIndexPage);
            config.routes.get("/login", this::handleLoginPage);
            config.routes.get("/setup", this::handleSetupPage);
            config.routes.get("/assets/styles.css", this::handleStyles);
            config.routes.get("/brand/logo", this::handleBrandLogo);
            config.routes.get("/api/status", this::handleStatus);
            config.routes.post("/api/auth/login", this::handleLogin);
            config.routes.post("/api/auth/logout", this::handleLogout);
            config.routes.post("/api/setup", this::handleSetup);

            config.routes.wsBeforeUpgrade("/ws/console", ctx -> {
                verifySameOrigin(ctx);
                SessionInfo session = requireSession(ctx);
                ctx.attribute(ATTR_CLIENT_IP, ctx.ip());
                ctx.attribute(ATTR_SESSION_TOKEN, session.token());
            });
            config.routes.ws("/ws/console", ws -> {
                ws.onConnect(this::handleWebSocketConnect);
                ws.onMessage(this::handleWebSocketMessage);
                ws.onClose(this::handleWebSocketClose);
                ws.onError(ctx -> webSocketClients.remove(ctx.sessionId()));
            });

            config.routes.error(404, ctx -> {
                if (ctx.path().startsWith("/api/")) {
                    writeJson(ctx, HttpStatus.NOT_FOUND, new BasicResponse(false, "Not found."));
                } else {
                    ctx.redirect("/");
                }
            });

            config.routes.exception(IllegalArgumentException.class, (exception, ctx) -> {
                if (ctx.path().startsWith("/api/")) {
                    writeJson(ctx, HttpStatus.BAD_REQUEST, new BasicResponse(false, exception.getMessage()));
                } else {
                    ctx.status(HttpStatus.BAD_REQUEST).result(exception.getMessage());
                }
            });
            config.routes.exception(SecurityException.class, (exception, ctx) -> {
                if (ctx.path().startsWith("/api/")) {
                    writeJson(ctx, HttpStatus.FORBIDDEN, new BasicResponse(false, exception.getMessage()));
                } else {
                    ctx.status(HttpStatus.FORBIDDEN).result(exception.getMessage());
                }
            });
            config.routes.exception(Exception.class, (exception, ctx) -> {
                plugin.getLogger().log(Level.WARNING, "HTTP handler failed.", exception);
                if (ctx.path().startsWith("/api/")) {
                    writeJson(ctx, HttpStatus.INTERNAL_SERVER_ERROR, new BasicResponse(false, "Internal server error."));
                } else {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("Internal server error.");
                }
            });
        }).start(configuration.bindAddress(), configuration.port());
    }

    public void stop() {
        disconnectAll("Server stopping.");
        if (app != null) {
            app.stop();
            app = null;
        }
    }

    public void broadcastLog(ConsoleEntry entry) {
        String payload = gson.toJson(new WsEnvelope("log", entry));
        for (Map.Entry<String, WsConnectContext> client : webSocketClients.entrySet()) {
            try {
                client.getValue().send(payload);
            } catch (Exception ignored) {
                webSocketClients.remove(client.getKey());
            }
        }
    }

    public void disconnectAll(String reason) {
        for (Map.Entry<String, WsConnectContext> client : webSocketClients.entrySet()) {
            try {
                client.getValue().closeSession(4001, reason);
            } catch (Exception ignored) {
                // Best effort.
            }
        }
        webSocketClients.clear();
    }

    private void handleStyles(Context ctx) {
        String filename = configuration.ui().customCssFile();
        if (filename != null) {
            try {
                Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
                Path cssPath = dataFolder.resolve(filename).normalize();
                if (cssPath.startsWith(dataFolder) && Files.isRegularFile(cssPath)) {
                    ctx.contentType("text/css").result(Files.readAllBytes(cssPath));
                    return;
                }
                plugin.getLogger().warning("Custom CSS file '" + filename + "' not found; serving default styles.");
            } catch (IOException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not read custom CSS file; serving default styles.", exception);
            }
        }
        ctx.contentType("text/css").result(readResource("/web/assets/styles-default.css"));
    }

    private void handleBrandLogo(Context ctx) {
        String filename = configuration.ui().brandLogoFile();
        if (filename == null) {
            ctx.status(HttpStatus.NOT_FOUND).result("");
            return;
        }
        try {
            Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
            Path logoPath = dataFolder.resolve(filename).normalize();
            if (!logoPath.startsWith(dataFolder)) {
                ctx.status(HttpStatus.FORBIDDEN).result("");
                return;
            }
            if (!Files.isRegularFile(logoPath)) {
                ctx.status(HttpStatus.NOT_FOUND).result("");
                return;
            }
            ctx.contentType(logoContentType(filename)).result(Files.readAllBytes(logoPath));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not read brand logo file.", exception);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("");
        }
    }

    private static String logoContentType(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private void handleIndexPage(Context ctx) {
        if (!authManager.isConfigured()) {
            ctx.redirect("/setup");
            return;
        }

        if (resolveSession(ctx) == null) {
            ctx.redirect("/login");
            return;
        }

        ctx.html(readResource("/web/index.html"));
    }

    private void handleLoginPage(Context ctx) {
        if (!authManager.isConfigured()) {
            ctx.redirect("/setup");
            return;
        }

        if (resolveSession(ctx) != null) {
            ctx.redirect("/");
            return;
        }

        ctx.html(readResource("/web/login.html"));
    }

    private void handleSetupPage(Context ctx) {
        if (authManager.isConfigured()) {
            if (resolveSession(ctx) != null) {
                ctx.redirect("/");
            } else {
                ctx.redirect("/login");
            }
            return;
        }

        ctx.html(readResource("/web/setup.html"));
    }

    private void handleStatus(Context ctx) {
        SessionInfo session = resolveSession(ctx);
        writeJson(ctx, HttpStatus.OK, new StatusResponse(
            authManager.isConfigured(),
            session != null,
            plugin.getServer().getName(),
            plugin.getServer().getMinecraftVersion(),
            plugin.getDescription().getVersion(),
            configuration.bindAddress(),
            configuration.port(),
            authManager.activeSessions(),
            session != null ? session.expiresAtEpochMillis() : 0L,
            new UiPayload(
                configuration.ui().showTimestamps(),
                configuration.ui().defaultWrapMode(),
                configuration.ui().maxBufferedLines(),
                configuration.ui().brandLogoFile() != null ? "/brand/logo" : null,
                configuration.ui().brandEyebrow(),
                configuration.ui().brandTitle()
            )
        ));
    }

    private void handleLogin(Context ctx) {
        LoginRequest request = readJson(ctx, LoginRequest.class);
        AuthResult result = authManager.login(ctx.ip(), request.password());
        if (result.success()) {
            setSessionCookie(ctx, result.session());
        }
        writeJson(ctx, result.statusCode(), new BasicResponse(result.success(), result.message()));
    }

    private void handleLogout(Context ctx) {
        authManager.logout(ctx.cookie(SESSION_COOKIE));
        clearSessionCookie(ctx);
        writeJson(ctx, HttpStatus.OK, new BasicResponse(true, "Logged out."));
    }

    private void handleSetup(Context ctx) throws IOException {
        SetupRequest request = readJson(ctx, SetupRequest.class);
        AuthResult result = authManager.completeSetup(request.token(), request.password(), ctx.ip());
        if (result.success()) {
            setSessionCookie(ctx, result.session());
        }
        writeJson(ctx, result.statusCode(), new BasicResponse(result.success(), result.message()));
    }

    private void handleWebSocketConnect(WsConnectContext ctx) {
        ctx.enableAutomaticPings();
        webSocketClients.put(ctx.sessionId(), ctx);
        ctx.send(gson.toJson(new WsEnvelope("history", consoleBuffer.snapshot())));
        ctx.send(gson.toJson(new WsEnvelope("status", new StatusPayload(
            "connected",
            plugin.currentBaseUrl(),
            configuration.ui().showTimestamps(),
            configuration.ui().defaultWrapMode()
        ))));
    }

    private void handleWebSocketMessage(WsMessageContext ctx) {
        try {
            ClientMessage clientMessage = gson.fromJson(ctx.message(), ClientMessage.class);
            if (clientMessage == null || clientMessage.type() == null) {
                ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Invalid message."))));
                return;
            }

            String sessionToken = ctx.attribute(ATTR_SESSION_TOKEN);
            String clientIp = ctx.attribute(ATTR_CLIENT_IP);
            SessionInfo session = authManager.authenticateSession(sessionToken, clientIp);
            if (session == null) {
                ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Session expired. Log in again."))));
                ctx.closeSession(4003, "Session expired.");
                webSocketClients.remove(ctx.sessionId());
                return;
            }

            switch (clientMessage.type()) {
                case "ping" -> ctx.send(gson.toJson(new WsEnvelope("status", new StatusPayload(
                    "alive",
                    plugin.currentBaseUrl(),
                    configuration.ui().showTimestamps(),
                    configuration.ui().defaultWrapMode()
                ))));
                case "command" -> {
                    if (clientMessage.command() == null || clientMessage.command().isBlank()) {
                        ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Command cannot be empty."))));
                        return;
                    }
                    plugin.dispatchWebCommand(clientMessage.command(), clientIp);
                }
                default -> ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Unsupported message type."))));
            }
        } catch (JsonSyntaxException exception) {
            ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Malformed JSON payload."))));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "WebSocket command handling failed.", exception);
            ctx.send(gson.toJson(new WsEnvelope("error", new BasicResponse(false, "Internal server error."))));
        }
    }

    private void handleWebSocketClose(WsCloseContext ctx) {
        webSocketClients.remove(ctx.sessionId());
    }

    private void applySecurityHeaders(Context ctx) {
        ctx.header("Cache-Control", ctx.path().startsWith("/assets/") || ctx.path().startsWith("/brand/") ? "public, max-age=86400" : "no-store");
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "same-origin");
        ctx.header("Cross-Origin-Resource-Policy", "same-origin");
        ctx.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws:; img-src 'self' data:; font-src 'self'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
    }

    private void verifySameOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        String expected = "http://" + ctx.host();
        if (origin == null || !expected.equalsIgnoreCase(origin)) {
            throw new SecurityException("Forbidden origin.");
        }
    }

    private SessionInfo requireSession(Context ctx) {
        SessionInfo session = resolveSession(ctx);
        if (session == null) {
            throw new SecurityException("Unauthorized.");
        }
        return session;
    }

    private SessionInfo resolveSession(Context ctx) {
        String token = ctx.cookie(SESSION_COOKIE);
        return authManager.authenticateSession(token, ctx.ip());
    }

    private void setSessionCookie(Context ctx, SessionInfo session) {
        long seconds = Math.max(1L, (session.expiresAtEpochMillis() - System.currentTimeMillis()) / 1000L);
        ctx.res().addHeader("Set-Cookie", SESSION_COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + seconds);
    }

    private void clearSessionCookie(Context ctx) {
        ctx.res().addHeader("Set-Cookie", SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    private <T> T readJson(Context ctx, Class<T> type) {
        try {
            T body = gson.fromJson(ctx.body(), type);
            if (body == null) {
                throw new IllegalArgumentException("Request body is required.");
            }
            return body;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed JSON body.", exception);
        }
    }

    private void writeJson(Context ctx, HttpStatus status, Object payload) {
        ctx.status(status);
        ctx.contentType("application/json");
        ctx.result(gson.toJson(payload));
    }

    private void writeJson(Context ctx, int statusCode, Object payload) {
        ctx.status(statusCode);
        ctx.contentType("application/json");
        ctx.result(gson.toJson(payload));
    }

    private String readResource(String path) {
        try (InputStream inputStream = WebConsoleServer.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read resource " + path, exception);
        }
    }

    private record LoginRequest(String password) {
    }

    private record SetupRequest(String token, String password) {
    }

    private record BasicResponse(boolean ok, String message) {
    }

    private record UiPayload(boolean showTimestamps, boolean defaultWrapMode, int maxBufferedLines, String brandLogoUrl, String brandEyebrow, String brandTitle) {
    }

    private record StatusResponse(
        boolean configured,
        boolean authenticated,
        String serverName,
        String minecraftVersion,
        String pluginVersion,
        String bindAddress,
        int port,
        int activeSessions,
        long sessionExpiresAtEpochMillis,
        UiPayload ui
    ) {
    }

    private record StatusPayload(String state, String baseUrl, boolean showTimestamps, boolean wrapMode) {
    }

    private record ClientMessage(String type, String command) {
    }

    private record WsEnvelope(String type, Object payload) {
    }
}
