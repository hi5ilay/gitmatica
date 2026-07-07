package me.zly2006.rvc;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

public final class RvcGithubAuth
{
    private static final String CLIENT_ID_PROPERTY = "rvc.github.oauth.clientId";
    private static final String CLIENT_ID_ENV = "RVC_GITHUB_OAUTH_CLIENT_ID";
    private static final String TOKEN_PROPERTY = "rvc.github.oauth.token";
    private static final String TOKEN_ENV = "RVC_GITHUB_OAUTH_TOKEN";
    private static final String GITHUB_API = "https://api.github.com";
    private static final String GITHUB_DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String GITHUB_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    private static final String AUTH_DIRECTORY = "rvc-auth";
    private static final String AUTH_FILE = "github.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private RvcGithubAuth()
    {
    }

    public static boolean isConfigured()
    {
        String clientId = clientId();
        return clientId != null && !clientId.isBlank();
    }

    @Nullable
    public static String clientId()
    {
        String propertyValue = System.getProperty(CLIENT_ID_PROPERTY);

        if (propertyValue != null && !propertyValue.isBlank())
        {
            return propertyValue.trim();
        }

        String envValue = System.getenv(CLIENT_ID_ENV);
        return envValue != null && !envValue.isBlank() ? envValue.trim() : null;
    }

    public static String configurationHint()
    {
        return "Set " + CLIENT_ID_PROPERTY + " or " + CLIENT_ID_ENV + " to a GitHub OAuth app client id with Device Flow enabled.";
    }

    public static DeviceAuthorization beginDeviceAuthorization() throws IOException
    {
        String clientId = clientId();

        if (clientId == null || clientId.isBlank())
        {
            throw new IOException(configurationHint());
        }

        JsonObject response = postForm(URI.create(GITHUB_DEVICE_CODE_URL), Map.of(
                "client_id", clientId,
                "scope", "repo"
        ));

        String deviceCode = requireString(response, "device_code");
        String userCode = requireString(response, "user_code");
        URI verificationUri = URI.create(requireString(response, "verification_uri"));
        int expiresIn = getInt(response, "expires_in", 900);
        int interval = Math.max(1, getInt(response, "interval", 5));
        return new DeviceAuthorization(deviceCode, userCode, verificationUri, Instant.now().plusSeconds(expiresIn), interval);
    }

    public static LinkedAccount pollDeviceAuthorization(Path gameRunDirectory, DeviceAuthorization authorization, BooleanSupplier shouldContinue) throws IOException
    {
        String clientId = clientId();

        if (clientId == null || clientId.isBlank())
        {
            throw new IOException(configurationHint());
        }

        int interval = authorization.intervalSeconds();

        while (Instant.now().isBefore(authorization.expiresAt()))
        {
            if (!shouldContinue.getAsBoolean())
            {
                throw new IOException("GitHub authorization was cancelled.");
            }

            sleepSeconds(interval);

            if (!shouldContinue.getAsBoolean())
            {
                throw new IOException("GitHub authorization was cancelled.");
            }

            JsonObject response = postForm(URI.create(GITHUB_ACCESS_TOKEN_URL), Map.of(
                    "client_id", clientId,
                    "device_code", authorization.deviceCode(),
                    "grant_type", DEVICE_GRANT_TYPE
            ));

            if (response.has("access_token"))
            {
                String token = requireString(response, "access_token");
                String tokenType = getString(response, "token_type", "bearer");
                String scope = getString(response, "scope", "");
                LinkedAccount account = fetchCurrentAccount(token);
                writeStoredToken(gameRunDirectory, new StoredToken(token, tokenType, scope, account.login(), Instant.now().toString()));
                return account;
            }

            String error = getString(response, "error", "");

            if ("authorization_pending".equals(error))
            {
                continue;
            }

            if ("slow_down".equals(error))
            {
                interval = Math.max(interval + 5, getInt(response, "interval", interval + 5));
                continue;
            }

            if ("expired_token".equals(error) || "token_expired".equals(error))
            {
                throw new IOException("GitHub authorization code expired.");
            }

            if ("access_denied".equals(error))
            {
                throw new IOException("GitHub authorization was denied.");
            }

            throw new IOException("GitHub authorization failed: " + getString(response, "error_description", error));
        }

        throw new IOException("GitHub authorization code expired.");
    }

    @Nullable
    public static LinkedAccount readStoredAccount(Path gameRunDirectory)
    {
        StoredToken token = readStoredToken(gameRunDirectory);
        return token != null && token.login() != null && !token.login().isBlank() ? new LinkedAccount(token.login()) : null;
    }

    @Nullable
    public static String readAccessTokenForRepository(Path repositoryDirectory)
    {
        String propertyToken = System.getProperty(TOKEN_PROPERTY);

        if (propertyToken != null && !propertyToken.isBlank())
        {
            return propertyToken.trim();
        }

        String envToken = System.getenv(TOKEN_ENV);

        if (envToken != null && !envToken.isBlank())
        {
            return envToken.trim();
        }

        StoredToken token = readStoredToken(resolveGameRunDirectory(repositoryDirectory));
        return token != null && token.accessToken() != null && !token.accessToken().isBlank() ? token.accessToken() : null;
    }

    public static List<RepositoryInfo> listRepositories(Path gameRunDirectory) throws IOException
    {
        String token = readAccessTokenForRepository(resolveRepositoryTokenPath(gameRunDirectory));

        if (token == null || token.isBlank())
        {
            throw new IOException("Connect GitHub before choosing a repository.");
        }

        URI uri = URI.create(GITHUB_API + "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member");
        JsonArray array = getJsonArray(uri, token);
        List<RepositoryInfo> repositories = new ArrayList<>();

        for (JsonElement element : array)
        {
            if (!element.isJsonObject())
            {
                continue;
            }

            JsonObject repo = element.getAsJsonObject();
            JsonObject owner = repo.has("owner") && repo.get("owner").isJsonObject() ? repo.getAsJsonObject("owner") : new JsonObject();
            String fullName = getString(repo, "full_name", "");
            String[] parts = fullName.split("/", 2);

            if (parts.length != 2)
            {
                continue;
            }

            repositories.add(new RepositoryInfo(
                    getLong(repo, "id", 0L),
                    parts[0],
                    parts[1],
                    getString(owner, "login", parts[0]),
                    getString(repo, "html_url", "https://github.com/" + fullName),
                    getBoolean(repo, "private", false)
            ));
        }

        return List.copyOf(repositories);
    }

    public static void testRepositoryAccess(Path repositoryDirectory, String owner, String repository) throws IOException
    {
        String token = readAccessTokenForRepository(repositoryDirectory);

        if (token == null || token.isBlank())
        {
            throw new IOException("Connect GitHub before testing repository access.");
        }

        getJsonObject(URI.create(GITHUB_API + "/repos/" + encodePath(owner) + "/" + encodePath(repository)), token);
    }

    public static void clearStoredToken(Path gameRunDirectory) throws IOException
    {
        Files.deleteIfExists(authFile(gameRunDirectory));
    }

    public static boolean isGithubHttpsRemote(@Nullable String remoteUrl)
    {
        return remoteUrl != null && (remoteUrl.startsWith("https://github.com/") || remoteUrl.startsWith("http://github.com/"));
    }

    public static Path resolveGameRunDirectory(Path repositoryDirectory)
    {
        Path normalized = repositoryDirectory.toAbsolutePath().normalize();

        for (Path current = normalized; current != null; current = current.getParent())
        {
            Path fileName = current.getFileName();

            if (fileName != null && RvcProjectService.REPOS_DIRECTORY.equals(fileName.toString()))
            {
                Path parent = current.getParent();
                return parent != null ? parent : normalized;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.gameDirectory != null ? minecraft.gameDirectory.toPath() : normalized;
    }

    public static boolean openBrowser(URI uri)
    {
        try
        {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        }
        catch (Exception ignored)
        {
        }

        return false;
    }

    private static Path resolveRepositoryTokenPath(Path gameRunDirectory)
    {
        return gameRunDirectory.resolve(RvcProjectService.REPOS_DIRECTORY);
    }

    @Nullable
    private static StoredToken readStoredToken(Path gameRunDirectory)
    {
        Path file = authFile(gameRunDirectory);

        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new StoredToken(
                    getString(root, "access_token", ""),
                    getString(root, "token_type", "bearer"),
                    getString(root, "scope", ""),
                    getString(root, "login", ""),
                    getString(root, "linked_at", "")
            );
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void writeStoredToken(Path gameRunDirectory, StoredToken token) throws IOException
    {
        Path directory = authDirectory(gameRunDirectory);
        Files.createDirectories(directory);
        restrictOwnerAccess(directory, true);

        JsonObject root = new JsonObject();
        root.addProperty("access_token", token.accessToken());
        root.addProperty("token_type", token.tokenType());
        root.addProperty("scope", token.scope());
        root.addProperty("login", token.login());
        root.addProperty("linked_at", token.linkedAt());

        Path file = authFile(gameRunDirectory);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        restrictOwnerAccess(file, false);
    }

    private static Path authDirectory(Path gameRunDirectory)
    {
        return gameRunDirectory.resolve(AUTH_DIRECTORY);
    }

    private static Path authFile(Path gameRunDirectory)
    {
        return authDirectory(gameRunDirectory).resolve(AUTH_FILE);
    }

    private static void restrictOwnerAccess(Path path, boolean directory)
    {
        try
        {
            Files.setPosixFilePermissions(path, directory ?
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE) :
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (Exception ignored)
        {
        }
    }

    private static LinkedAccount fetchCurrentAccount(String token) throws IOException
    {
        JsonObject user = getJsonObject(URI.create(GITHUB_API + "/user"), token);
        return new LinkedAccount(requireString(user, "login"));
    }

    private static JsonObject postForm(URI uri, Map<String, String> values) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(values)))
                .build();
        return sendJsonObject(request);
    }

    private static JsonObject getJsonObject(URI uri, String token) throws IOException
    {
        HttpRequest request = bearerRequest(uri, token).GET().build();
        return sendJsonObject(request);
    }

    private static JsonArray getJsonArray(URI uri, String token) throws IOException
    {
        HttpRequest request = bearerRequest(uri, token).GET().build();
        JsonElement element = sendJson(request);

        if (!element.isJsonArray())
        {
            throw new IOException("GitHub returned an unexpected response.");
        }

        return element.getAsJsonArray();
    }

    private static HttpRequest.Builder bearerRequest(URI uri, String token)
    {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private static JsonObject sendJsonObject(HttpRequest request) throws IOException
    {
        JsonElement element = sendJson(request);

        if (!element.isJsonObject())
        {
            throw new IOException("GitHub returned an unexpected response.");
        }

        return element.getAsJsonObject();
    }

    private static JsonElement sendJson(HttpRequest request) throws IOException
    {
        try
        {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonElement element = response.body() != null && !response.body().isBlank() ? JsonParser.parseString(response.body()) : new JsonObject();

            if (response.statusCode() >= 200 && response.statusCode() < 300)
            {
                return element;
            }

            String message = element.isJsonObject() ? getString(element.getAsJsonObject(), "message", response.body()) : response.body();
            throw new IOException("GitHub request failed (" + response.statusCode() + "): " + message);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for GitHub", e);
        }
    }

    private static String formEncode(Map<String, String> values)
    {
        StringJoiner joiner = new StringJoiner("&");

        for (Map.Entry<String, String> entry : values.entrySet())
        {
            joiner.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }

        return joiner.toString();
    }

    private static String urlEncode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value)
    {
        return urlEncode(value).replace("+", "%20");
    }

    private static void sleepSeconds(int seconds) throws IOException
    {
        try
        {
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for GitHub authorization", e);
        }
    }

    private static String requireString(JsonObject obj, String key) throws IOException
    {
        String value = getString(obj, key, null);

        if (value == null || value.isBlank())
        {
            throw new IOException("GitHub response did not include " + key + ".");
        }

        return value;
    }

    @Nullable
    private static String getString(JsonObject obj, String key, @Nullable String fallback)
    {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
    }

    private static int getInt(JsonObject obj, String key, int fallback)
    {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsInt() : fallback;
    }

    private static long getLong(JsonObject obj, String key, long fallback)
    {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsLong() : fallback;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean fallback)
    {
        JsonElement element = obj.get(key);
        return element != null && !element.isJsonNull() ? element.getAsBoolean() : fallback;
    }

    private record StoredToken(String accessToken, String tokenType, String scope, String login, String linkedAt)
    {
        private StoredToken
        {
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(tokenType, "tokenType");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(login, "login");
            Objects.requireNonNull(linkedAt, "linkedAt");
        }
    }

    public record DeviceAuthorization(String deviceCode, String userCode, URI verificationUri, Instant expiresAt, int intervalSeconds)
    {
    }

    public record LinkedAccount(String login)
    {
    }

    public record RepositoryInfo(long id, String owner, String name, String ownerLogin, String htmlUrl, boolean privateRepository)
    {
        public String fullName()
        {
            return this.owner + "/" + this.name;
        }

        public String remoteUrl()
        {
            return "https://github.com/" + this.fullName() + ".git";
        }

        public String displayName()
        {
            return this.fullName() + (this.privateRepository ? " private" : "");
        }
    }
}
