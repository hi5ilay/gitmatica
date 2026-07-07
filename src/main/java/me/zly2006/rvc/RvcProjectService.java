package me.zly2006.rvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.interfaces.ICompletionListener;

public final class RvcProjectService
{
    public static final String REPOS_DIRECTORY = "rvc-projects";
    public static final String LOCAL_JSON = "local.json";
    public static final String LOCAL_SELECTION_KEY = "local_selection";
    public static final String MASTER_ORIGIN_KEY = "master_origin";
    public static final String DEFAULT_BRANCH = "main";
    private static final String GIT_CONFIG_SECTION = "rvc";
    private static final String GIT_CONFIG_HISTORY_BRANCH_KEY = "historyBranch";
    private static final List<String> DEFAULT_SSH_IDENTITY_NAMES = List.of("id_ed25519", "id_ecdsa", "id_rsa", "id_dsa");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter COMMIT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private RvcProjectService()
    {
    }

    public static Result createProject(Path gameRunDirectory, String repositoryName, RvcPlayerIdentity player, Level world, AreaSelection selection) throws Exception
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selection, "selection");

        String displayName = normalizeDisplayName(repositoryName);
        Path repositoryDirectory = repositoryDirectory(gameRunDirectory, displayName);

        if (Files.exists(repositoryDirectory))
        {
            throw new FileAlreadyExistsException(repositoryDirectory.toString());
        }

        Files.createDirectories(repositoryDirectory);

        Level captureWorld = resolveSemanticCaptureWorld(world);
        String dimensionId = RvcMinecraftWorldReader.dimensionId(captureWorld);
        RvcManifest.Site site = createMainSiteFromSelection(displayName, dimensionId, selection);
        RvcLocalState.SitePlacement placement = createSitePlacement(selection.getEffectiveOrigin(), dimensionId);
        RvcSemanticRepository.CommitResult result = runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
                RvcSemanticRepository.initProject(
                        repositoryDirectory,
                        displayName,
                        site,
                        placement,
                        new RvcMinecraftWorldReader(authoritativeWorld),
                        player
                )
        );
        RevCommit commit = result.commit();

        return new Result(repositoryDirectory, commit.getName());
    }

    public static EmptyProjectResult createEmptyProject(Path gameRunDirectory, String repositoryName, BlockPos origin, String dimensionId) throws Exception
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(dimensionId, "dimensionId");

        String displayName = normalizeDisplayName(repositoryName);
        validateProjectName(displayName);

        if (dimensionId.isBlank())
        {
            throw new IllegalArgumentException("RVC project dimension must not be blank");
        }

        Path repositoryDirectory = repositoryDirectory(gameRunDirectory, displayName);

        if (Files.exists(repositoryDirectory))
        {
            throw new FileAlreadyExistsException(repositoryDirectory.toString());
        }

        Files.createDirectories(repositoryDirectory);

        RvcManifest.Site site = new RvcManifest.Site("main", displayName, dimensionId, List.of(), Map.of());
        RvcLocalState.SitePlacement placement = createSitePlacement(origin, dimensionId);
        RvcSemanticRepository.initEmptyProject(repositoryDirectory, displayName, site, placement);

        return new EmptyProjectResult(repositoryDirectory, displayName);
    }

    @Nullable
    public static RevCommit gitCommit(Path repositoryDirectory, String projectName, RvcPlayerIdentity player, Level world, @Nullable AreaSelection currentSelectionFallback, boolean ignoreEntities, String message) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(message, "message");

        if (isSemanticProject(repositoryDirectory))
        {
            Level captureWorld = resolveSemanticCaptureWorld(world);
            RvcManifest manifest = RvcSemanticRepository.readManifest(repositoryDirectory);
            RvcLocalState localState = RvcSemanticRepository.readLocalState(repositoryDirectory);
            String siteId = localState.activeSite();
            String worldDimension = RvcMinecraftWorldReader.dimensionId(captureWorld);
            RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

            if (placement == null)
            {
                throw new IOException("Missing local placement for active RVC site: " + siteId);
            }

            if (!worldDimension.equals(placement.dimension()))
            {
                throw new IOException("Active RVC site is in " + placement.dimension() + " but current world is " + worldDimension);
            }

            RvcManifest.Site site = manifest.site(siteId);

            if (site.regions().isEmpty())
            {
                throw new IOException("Add at least one RVC sub-region before saving a version");
            }

            return runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
                    RvcSemanticRepository.commitSite(
                            repositoryDirectory,
                            manifest,
                            localState,
                            siteId,
                            new RvcMinecraftWorldReader(authoritativeWorld),
                            player,
                            normalizeCommitMessage(message)
                    ).commit()
            );
        }

        StructureTemplate structure = createStructureFromIndexSubRegionsOrFallbackToCurrentPositionUtilsGetValidBoxes(repositoryDirectory, world, currentSelectionFallback, ignoreEntities);
        return RvcRepository.commit(repositoryDirectory, projectName, structure, player, normalizeCommitMessage(message));
    }

    public static SemanticScanResult scanSemanticChanges(Path repositoryDirectory, Level world) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(world, "world");

        if (!isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Scan Changes currently supports semantic RVC projects only");
        }

        Level captureWorld = resolveSemanticCaptureWorld(world);
        RvcManifest manifest = RvcSemanticRepository.readManifest(repositoryDirectory);
        RvcLocalState localState = RvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        RvcManifest.Site site = manifest.site(siteId);
        RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for active RVC site: " + siteId);
        }

        String worldDimension = RvcMinecraftWorldReader.dimensionId(captureWorld);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new IOException("Active RVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }

        return runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
        {
            RvcCaptureEngine.Result scan = RvcCaptureEngine.scanSite(site, placement, new RvcMinecraftWorldReader(authoritativeWorld));
            return SemanticScanResult.compare(siteId, site.chunks(), scan);
        });
    }

    public static UpdateAreasResult updateSemanticAreas(Path repositoryDirectory, RvcPlayerIdentity player, Level world,
                                                        AreaSelection selection, String message) throws Exception
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(message, "message");

        if (!isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Update areas currently supports semantic RVC projects only");
        }

        Level captureWorld = resolveSemanticCaptureWorld(world);
        RvcManifest manifest = RvcSemanticRepository.readManifest(repositoryDirectory);
        RvcLocalState localState = RvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        RvcManifest.Site site = manifest.site(siteId);
        RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for active RVC site: " + siteId);
        }

        String worldDimension = RvcMinecraftWorldReader.dimensionId(captureWorld);

        if (!worldDimension.equals(placement.dimension()))
        {
            throw new IOException("Active RVC site is in " + placement.dimension() + " but current world is " + worldDimension);
        }

        List<RvcManifest.Region> updatedRegions = createRegionsFromSelection(selection, blockPosFromList(placement.origin()), site.regions());

        return runOnSemanticCaptureWorld(captureWorld, authoritativeWorld ->
        {
            RvcSemanticRepository.CommitResult result = RvcSemanticRepository.updateSiteAreas(
                    repositoryDirectory,
                    manifest,
                    localState,
                    siteId,
                    updatedRegions,
                    new RvcMinecraftWorldReader(authoritativeWorld),
                    player,
                    normalizeCommitMessage(message)
            );
            return new UpdateAreasResult(result.commit(), result.manifest().site(siteId).regions().size());
        });
    }

    public static void writeProjectMetadataWithSubRegions(Path repositoryDirectory, String projectName, AreaSelection selection) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(selection, "selection");
        validateProjectName(projectName);

        Files.createDirectories(repositoryDirectory);

        BlockPos masterOrigin = selection.getEffectiveOrigin();
        JsonObject index = new JsonObject();
        JsonArray subRegions = new JsonArray();

        index.add("rvc_version", new JsonPrimitive(RvcRepository.RVC_VERSION));
        index.add("name", new JsonPrimitive(projectName.trim()));

        for (Box box : getValidBoxes(selection))
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos min = fi.dy.masa.litematica.util.PositionUtils.getMinCorner(pos1, pos2);
            BlockPos max = fi.dy.masa.litematica.util.PositionUtils.getMaxCorner(pos1, pos2);
            JsonObject subRegion = new JsonObject();
            subRegion.add("name", new JsonPrimitive(box.getName()));
            subRegion.add("pos1", blockPosToArray(min.subtract(masterOrigin)));
            subRegion.add("pos2", blockPosToArray(max.subtract(masterOrigin)));
            subRegion.add("size", blockPosToArray(max.subtract(min).offset(1, 1, 1)));
            subRegions.add(subRegion);
        }

        index.add("sub_regions", subRegions);
        Files.writeString(repositoryDirectory.resolve(RvcRepository.INDEX_JSON), GSON.toJson(index), StandardCharsets.UTF_8);
        writeLocalMasterOrigin(repositoryDirectory, masterOrigin);
    }

    @Nullable
    public static AreaSelection readProjectAreaSelection(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        JsonObject index = readJsonObject(repositoryDirectory.resolve(RvcRepository.INDEX_JSON));
        BlockPos masterOrigin = readLocalMasterOrigin(repositoryDirectory);

        if (index == null || masterOrigin == null || !index.has("sub_regions") || !index.get("sub_regions").isJsonArray())
        {
            return readLocalSelection(repositoryDirectory);
        }

        JsonObject selection = new JsonObject();
        JsonArray boxes = new JsonArray();
        String projectName = index.has("name") ? index.get("name").getAsString() : repositoryDirectory.getFileName().toString();

        for (JsonElement element : index.get("sub_regions").getAsJsonArray())
        {
            if (!element.isJsonObject())
            {
                continue;
            }

            JsonObject subRegion = element.getAsJsonObject();
            BlockPos pos1 = readBlockPosArray(subRegion, "pos1");
            BlockPos pos2 = readBlockPosArray(subRegion, "pos2");

            if (pos1 == null || pos2 == null || !subRegion.has("name"))
            {
                continue;
            }

            JsonObject box = new JsonObject();
            box.add("name", new JsonPrimitive(subRegion.get("name").getAsString()));
            box.add("pos1", blockPosToArray(pos1.offset(masterOrigin)));
            box.add("pos2", blockPosToArray(pos2.offset(masterOrigin)));
            boxes.add(box);
        }

        selection.add("name", new JsonPrimitive(projectName));
        selection.add("boxes", boxes);

        if (!boxes.isEmpty())
        {
            selection.add("current", new JsonPrimitive(boxes.get(0).getAsJsonObject().get("name").getAsString()));
        }

        return AreaSelection.fromJson(selection);
    }

    public static void writeLocalMasterOrigin(Path repositoryDirectory, BlockPos masterOrigin) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(masterOrigin, "masterOrigin");

        Files.createDirectories(repositoryDirectory);
        JsonObject root = readJsonObject(repositoryDirectory.resolve(LOCAL_JSON));

        if (root == null)
        {
            root = new JsonObject();
        }

        root.add(MASTER_ORIGIN_KEY, blockPosToArray(masterOrigin));
        Files.writeString(repositoryDirectory.resolve(LOCAL_JSON), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Nullable
    public static BlockPos readLocalMasterOrigin(Path repositoryDirectory)
    {
        JsonObject root = readJsonObject(repositoryDirectory.resolve(LOCAL_JSON));

        if (root == null)
        {
            return null;
        }

        return readBlockPosArray(root, MASTER_ORIGIN_KEY);
    }

    public static void writeLocalSelection(Path repositoryDirectory, AreaSelection selection) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(selection, "selection");

        JsonObject root = readJsonObject(repositoryDirectory.resolve(LOCAL_JSON));

        if (root == null)
        {
            root = new JsonObject();
        }

        root.add(LOCAL_SELECTION_KEY, selection.toJson());
        root.add(MASTER_ORIGIN_KEY, blockPosToArray(selection.getEffectiveOrigin()));
        Files.createDirectories(repositoryDirectory);
        Files.writeString(repositoryDirectory.resolve(LOCAL_JSON), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    @Nullable
    public static AreaSelection readLocalSelection(Path repositoryDirectory)
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        JsonObject root = readJsonObject(repositoryDirectory.resolve(LOCAL_JSON));

        if (root != null)
        {
            if (root.has(LOCAL_SELECTION_KEY) && root.get(LOCAL_SELECTION_KEY).isJsonObject())
            {
                return AreaSelection.fromJson(root.get(LOCAL_SELECTION_KEY).getAsJsonObject());
            }
        }

        return null;
    }

    public static List<Project> listProjects(Path gameRunDirectory) throws IOException
    {
        Path reposDirectory = reposDirectory(gameRunDirectory);

        if (!Files.isDirectory(reposDirectory))
        {
            return List.of();
        }

        List<Project> projects = new ArrayList<>();

        try (var stream = Files.list(reposDirectory))
        {
            for (Path candidate : stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList())
            {
                if (isValidProjectRepository(candidate))
                {
                    projects.add(new Project(candidate.getFileName().toString(), candidate));
                }
            }
        }

        return List.copyOf(projects);
    }

    public static void deleteProjectRepository(Path gameRunDirectory, Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(gameRunDirectory, "gameRunDirectory");
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        Path reposRoot = reposDirectory(gameRunDirectory).toAbsolutePath().normalize();
        Path target = repositoryDirectory.toAbsolutePath().normalize();

        if (!target.startsWith(reposRoot) || target.equals(reposRoot))
        {
            throw new IOException("RVC project must be under " + reposRoot);
        }

        if (!isValidProjectRepository(target))
        {
            throw new IOException("Not a valid RVC project repository: " + target);
        }

        deleteRecursively(target);
        Litematica.LOGGER.debug("RvcProjectService: deleted RVC project repository '{}'", target);
    }

    public static ProjectSummary projectSummary(Project project) throws IOException, GitAPIException
    {
        Objects.requireNonNull(project, "project");
        Path repositoryDirectory = project.directory();
        String displayName = project.name();
        BlockPos origin = null;

        if (isSemanticProject(repositoryDirectory))
        {
            RvcManifest manifest = RvcSemanticRepository.readManifest(repositoryDirectory);
            RvcLocalState localState = RvcSemanticRepository.readLocalState(repositoryDirectory);
            RvcLocalState.SitePlacement placement = localState.sites().get(localState.activeSite());

            displayName = manifest.name();

            if (placement != null)
            {
                origin = blockPosFromList(placement.origin());
            }
        }
        else
        {
            try
            {
                origin = resolveSchematicWorldOrigin(repositoryDirectory);
            }
            catch (IOException e)
            {
                Litematica.LOGGER.debug("RvcProjectService: failed to resolve schematic origin for '{}', falling back to local state: {}", repositoryDirectory, e.getMessage());
                origin = readLocalMasterOrigin(repositoryDirectory);
            }
        }

        return new ProjectSummary(displayName, listCommits(repositoryDirectory).size(), origin);
    }

    public static ProjectEditorState readSemanticProjectEditorState(Path repositoryDirectory) throws IOException
    {
        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        return new ProjectEditorState(
                project.manifest().name(),
                project.siteId(),
                project.site().name(),
                project.site().dimension(),
                project.placement().dimension(),
                blockPosFromList(project.placement().origin()),
                project.placement().worldHint(),
                project.site().regions()
        );
    }

    public static void updateSemanticProjectName(Path repositoryDirectory, String projectName) throws IOException
    {
        Objects.requireNonNull(projectName, "projectName");
        validateProjectName(projectName);

        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        String normalizedName = projectName.trim();
        RvcManifest manifestWithSiteName = project.manifest().withSite(project.siteId(), project.site().withName(normalizedName));
        RvcManifest updatedManifest = new RvcManifest(
                manifestWithSiteName.format(),
                manifestWithSiteName.projectId(),
                normalizedName,
                manifestWithSiteName.content(),
                manifestWithSiteName.sites()
        ).validate();

        RvcSemanticRepository.writeVersionedProjectFiles(repositoryDirectory, updatedManifest);
    }

    public static void updateSemanticLocalOrigin(Path repositoryDirectory, BlockPos origin) throws IOException
    {
        Objects.requireNonNull(origin, "origin");
        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        Map<String, RvcLocalState.SitePlacement> placements = new TreeMap<>(project.localState().sites());
        RvcLocalState.SitePlacement updatedPlacement = new RvcLocalState.SitePlacement(
                project.placement().dimension(),
                blockPosToList(origin),
                project.placement().worldHint()
        );

        placements.put(project.siteId(), updatedPlacement);
        RvcLocalState updatedLocalState = RvcLocalState.create(project.localState().projectId(), project.localState().activeSite(), placements);
        RvcSemanticRepository.writeLocalState(repositoryDirectory, updatedLocalState);
    }

    public static void updateSemanticRegion(Path repositoryDirectory, String regionId, String name, BlockPos min, BlockPos size) throws IOException
    {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(size, "size");

        if (name.isBlank())
        {
            throw new IllegalArgumentException("RVC region name must not be blank");
        }

        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        List<RvcManifest.Region> regions = new ArrayList<>(project.site().regions().size());
        boolean replaced = false;

        for (RvcManifest.Region region : project.site().regions())
        {
            if (region.id().equals(regionId))
            {
                regions.add(new RvcManifest.Region(region.id(), name.trim(), blockPosToList(min), blockPosToList(size)));
                replaced = true;
            }
            else
            {
                regions.add(region);
            }
        }

        if (!replaced)
        {
            throw new IOException("Unknown RVC region id: " + regionId);
        }

        writeSemanticRegions(repositoryDirectory, project, regions);
    }

    public static RvcManifest.Region createSemanticRegion(Path repositoryDirectory, String name, BlockPos min, BlockPos size) throws IOException
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(size, "size");

        if (name.isBlank())
        {
            throw new IllegalArgumentException("RVC region name must not be blank");
        }

        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);
        Set<String> usedRegionIds = new HashSet<>();

        for (RvcManifest.Region region : project.site().regions())
        {
            usedRegionIds.add(region.id());
        }

        RvcManifest.Region region = new RvcManifest.Region(
                uniqueRegionId(name, usedRegionIds),
                name.trim(),
                blockPosToList(min),
                blockPosToList(size)
        );
        List<RvcManifest.Region> regions = new ArrayList<>(project.site().regions());
        regions.add(region);
        writeSemanticRegions(repositoryDirectory, project, regions);
        return region;
    }

    public static void deleteSemanticRegion(Path repositoryDirectory, String regionId) throws IOException
    {
        Objects.requireNonNull(regionId, "regionId");

        ActiveSemanticProject project = readActiveSemanticProject(repositoryDirectory);

        if (project.site().regions().size() <= 1)
        {
            throw new IOException("RVC project must keep at least one sub-region");
        }

        List<RvcManifest.Region> regions = new ArrayList<>(project.site().regions().size());
        boolean removed = false;

        for (RvcManifest.Region region : project.site().regions())
        {
            if (region.id().equals(regionId))
            {
                removed = true;
            }
            else
            {
                regions.add(region);
            }
        }

        if (!removed)
        {
            throw new IOException("Unknown RVC region id: " + regionId);
        }

        writeSemanticRegions(repositoryDirectory, project, regions);
    }

    public static List<CommitInfo> listCommits(Path repositoryDirectory) throws IOException, GitAPIException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        List<CommitInfo> commits = new ArrayList<>();

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Repository repository = git.getRepository();
            String historyBranch = historyBranchRef(repository);
            ObjectId historyStart = repository.resolve(historyBranch);

            if (historyStart == null)
            {
                return List.of();
            }

            for (RevCommit commit : git.log().add(historyStart).call())
            {
                String fullMessage = commit.getFullMessage();
                String shortMessage = commit.getShortMessage();

                commits.add(new CommitInfo(
                        commit.getName(),
                        commit.getName().substring(0, Math.min(8, commit.getName().length())),
                        shortMessage,
                        commitDescription(fullMessage, shortMessage),
                        commit.getAuthorIdent().getName(),
                        COMMIT_TIME_FORMAT.format(commit.getAuthorIdent().getWhenAsInstant()),
                        countSubRegionsAtCommit(repository, commit),
                        ""
                ));
            }
        }

        return List.copyOf(commits);
    }

    private static String commitDescription(String fullMessage, String shortMessage)
    {
        if (fullMessage == null || fullMessage.isBlank())
        {
            return "";
        }

        if (shortMessage == null || shortMessage.isBlank() || !fullMessage.startsWith(shortMessage))
        {
            return fullMessage.trim();
        }

        return fullMessage.substring(Math.min(shortMessage.length(), fullMessage.length())).strip();
    }

    private static int countSubRegionsAtCommit(Repository repository, RevCommit commit)
    {
        try
        {
            String manifestJson = readCommitTextFile(repository, commit, RvcSemanticRepository.MANIFEST);

            if (manifestJson != null)
            {
                return countSemanticSubRegions(manifestJson);
            }

            String indexJson = readCommitTextFile(repository, commit, RvcRepository.INDEX_JSON);

            if (indexJson != null)
            {
                return countLegacySubRegions(indexJson);
            }
        }
        catch (IOException | RuntimeException e)
        {
            Litematica.LOGGER.debug("RvcProjectService: failed to count sub-regions for commit '{}': {}", commit.getName(), e.getMessage());
        }

        return -1;
    }

    @Nullable
    private static String readCommitTextFile(Repository repository, RevCommit commit, String path) throws IOException
    {
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree()))
        {
            if (treeWalk == null)
            {
                return null;
            }

            return new String(repository.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countSemanticSubRegions(String manifestJson)
    {
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();

        if (!manifest.has("sites") || !manifest.get("sites").isJsonArray())
        {
            return -1;
        }

        int count = 0;

        for (JsonElement siteElement : manifest.getAsJsonArray("sites"))
        {
            if (siteElement.isJsonObject())
            {
                JsonObject site = siteElement.getAsJsonObject();

                if (site.has("regions") && site.get("regions").isJsonArray())
                {
                    count += site.getAsJsonArray("regions").size();
                }
            }
        }

        return count;
    }

    private static int countLegacySubRegions(String indexJson)
    {
        JsonObject index = JsonParser.parseString(indexJson).getAsJsonObject();
        return index.has("sub_regions") && index.get("sub_regions").isJsonArray() ? index.getAsJsonArray("sub_regions").size() : -1;
    }

    public static void checkoutCommitToWorkingTree(Path repositoryDirectory, String commitId) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(commitId, "commitId");

        if (commitId.isBlank())
        {
            throw new IllegalArgumentException("Commit id must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            rememberCurrentBranchForHistory(git.getRepository());
            git.checkout().setName(commitId.trim()).call();
        }
    }

    public static void checkoutBranchToWorkingTree(Path repositoryDirectory, String branchName) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(branchName, "branchName");

        if (branchName.isBlank())
        {
            throw new IllegalArgumentException("Branch name must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            git.checkout().setName(branchName.trim()).call();
            rememberCurrentBranchForHistory(git.getRepository());
        }
    }

    public static boolean isDetachedHead(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String fullBranch = git.getRepository().getFullBranch();
            return fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS);
        }
    }

    public static String preferredCheckoutBranchName(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String branch = historyBranchRef(git.getRepository());

            if (branch.startsWith(Constants.R_HEADS))
            {
                return branch.substring(Constants.R_HEADS.length());
            }

            throw new IOException("RVC repository has no local branch to checkout");
        }
    }

    public static boolean hasUncommittedChanges(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            Status status = git.status().call();
            // reset --hard only returns tracked paths and the index to HEAD.
            return !status.getAdded().isEmpty() ||
                    !status.getChanged().isEmpty() ||
                    !status.getConflicting().isEmpty() ||
                    !status.getMissing().isEmpty() ||
                    !status.getModified().isEmpty() ||
                    !status.getRemoved().isEmpty();
        }
    }

    public static void resetWorkingTreeToHead(Path repositoryDirectory) throws GitAPIException, IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    public static SchematicWorldRestore restoreWorkingTreeToSchematicWorld(Path repositoryDirectory, String projectName, @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(projectName, "projectName");

        if (isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Semantic RVC overlay/export restore is not implemented yet");
        }

        int trackedBoxCount = readTrackedBoxes(repositoryDirectory).size();
        BlockPos schematicWorldOrigin = resolveSchematicWorldOrigin(repositoryDirectory);
        Path structureFile = repositoryDirectory.resolve(RvcRepository.INDEX_STRUCTURE);
        LitematicaSchematic schematic = reloadLitematicaSchematic(structureFile);

        if (schematic == null)
        {
            throw new IOException("Failed to load RVC structure: " + structureFile);
        }

        SchematicPlacement placement = SchematicPlacement.createFor(schematic, schematicWorldOrigin, "RVC: " + projectName, true, true);
        TrackingOverlay overlay = addTrackingOverlay(placement, clientLevel, completionListener);
        return new SchematicWorldRestore(schematicWorldOrigin, trackedBoxCount, overlay);
    }

    public static SchematicWorldRestore checkoutCommitToSchematicWorld(Path repositoryDirectory, String projectName, String commitId, @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener) throws GitAPIException, IOException
    {
        if (isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Semantic RVC checkout restore is not implemented yet");
        }

        checkoutCommitToWorkingTree(repositoryDirectory, commitId);
        return restoreWorkingTreeToSchematicWorld(repositoryDirectory, projectName, clientLevel, completionListener);
    }

    public static SchematicWorldRestore checkoutBranchToSchematicWorld(Path repositoryDirectory, String projectName, String branchName, @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener) throws GitAPIException, IOException
    {
        if (isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Semantic RVC checkout restore is not implemented yet");
        }

        checkoutBranchToWorkingTree(repositoryDirectory, branchName);
        return restoreWorkingTreeToSchematicWorld(repositoryDirectory, projectName, clientLevel, completionListener);
    }

    public static boolean hasRemote(Path repositoryDirectory) throws IOException
    {
        String remoteUrl = remoteOriginUrl(repositoryDirectory);
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    @Nullable
    public static String remoteOriginUrl(Path repositoryDirectory) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            return git.getRepository().getConfig().getString("remote", "origin", "url");
        }
    }

    public static void setRemote(Path repositoryDirectory, String remoteUrl) throws IOException
    {
        Objects.requireNonNull(remoteUrl, "remoteUrl");

        String normalizedRemoteUrl = remoteUrl.trim();

        if (normalizedRemoteUrl.isBlank())
        {
            throw new IllegalArgumentException("Remote Git URL must not be blank");
        }

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", normalizedRemoteUrl);
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            configureRemoteTracking(git.getRepository(), config);
            config.save();
        }
    }

    public static void clearRemote(Path repositoryDirectory) throws IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            StoredConfig config = git.getRepository().getConfig();
            config.unsetSection("remote", "origin");

            String branch = historyBranchRef(git.getRepository());

            if (branch.startsWith(Constants.R_HEADS))
            {
                String shortBranchName = branch.substring(Constants.R_HEADS.length());
                config.unset("branch", shortBranchName, "remote");
                config.unset("branch", shortBranchName, "merge");
            }

            config.save();
        }
    }

    public static List<String> push(Path repositoryDirectory) throws GitAPIException, IOException
    {
        List<String> statuses = new ArrayList<>();

        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            String branch = pushBranchRef(git.getRepository());
            PushCommand command = git.push().setRemote("origin").add(branch).setTransportConfigCallback(sshTransportConfigCallback());
            CredentialsProvider credentialsProvider = githubCredentialsProvider(repositoryDirectory, git.getRepository());

            if (credentialsProvider != null)
            {
                command.setCredentialsProvider(credentialsProvider);
            }

            for (PushResult result : command.call())
            {
                result.getRemoteUpdates().forEach(update -> statuses.add(update.getRemoteName() + ": " + update.getStatus()));
            }
        }

        return List.copyOf(statuses);
    }

    public static String pull(Path repositoryDirectory) throws GitAPIException, IOException
    {
        try (Git git = Git.open(repositoryDirectory.toFile()))
        {
            currentBranch(git.getRepository());
            PullCommand command = git.pull().setRemote("origin").setTransportConfigCallback(sshTransportConfigCallback());
            CredentialsProvider credentialsProvider = githubCredentialsProvider(repositoryDirectory, git.getRepository());

            if (credentialsProvider != null)
            {
                command.setCredentialsProvider(credentialsProvider);
            }

            PullResult result = command.call();
            return result.isSuccessful() ? "OK" : "FAILED";
        }
    }

    public static String describeRemoteFailure(Throwable throwable)
    {
        String message = throwable.getMessage();
        String fullMessage = collectThrowableMessages(throwable);
        String displayMessage = message != null ? message : throwable.getClass().getSimpleName();

        if (fullMessage.contains("no keys to try"))
        {
            return displayMessage + " " + sshIdentityDiagnostic();
        }

        if (fullMessage.contains("Server key did not validate"))
        {
            return displayMessage + " Trust GitHub's SSH host key once with: ssh -T git@github.com";
        }

        if (fullMessage.contains("Permission denied (publickey)"))
        {
            return displayMessage + " GitHub rejected the SSH key. Add your public key to GitHub and verify with: ssh -T git@github.com";
        }

        if (fullMessage.contains("CredentialsProvider has been registered"))
        {
            return displayMessage + " Open Project Settings and connect GitHub, or use Advanced remote settings for SSH.";
        }

        return displayMessage;
    }

    public static TrackingOverlay loadTrackingOverlay(Path repositoryDirectory, String projectName, @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");
        Objects.requireNonNull(projectName, "projectName");

        if (isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Semantic RVC overlay/export restore is not implemented yet");
        }

        Path structureFile = repositoryDirectory.resolve(RvcRepository.INDEX_STRUCTURE);
        LitematicaSchematic schematic = reloadLitematicaSchematic(structureFile);

        if (schematic == null)
        {
            throw new IOException("Failed to load RVC structure: " + structureFile);
        }

        BlockPos origin = resolveSchematicWorldOrigin(repositoryDirectory);
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, "RVC: " + projectName, true, true);
        return addTrackingOverlay(placement, clientLevel, completionListener);
    }

    private static TrackingOverlay addTrackingOverlay(SchematicPlacement placement, @Nullable ClientLevel clientLevel, @Nullable ICompletionListener completionListener)
    {
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);

        SchematicVerifier verifier = placement.getSchematicVerifier();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        boolean verifierStarted = false;

        if (clientLevel != null && schematicWorld != null)
        {
            verifier.startVerification(clientLevel, schematicWorld, placement, completionListener);
            verifierStarted = true;
        }

        return new TrackingOverlay(placement, verifier, verifierStarted);
    }

    public static Path reposDirectory(Path gameRunDirectory)
    {
        return gameRunDirectory.resolve(REPOS_DIRECTORY);
    }

    public static Path repositoryDirectory(Path gameRunDirectory, String projectName)
    {
        String displayName = normalizeDisplayName(projectName);
        return reposDirectory(gameRunDirectory).resolve(toDirectoryName(displayName)).normalize();
    }

    private static boolean isValidProjectRepository(Path candidate)
    {
        if (!Files.isDirectory(candidate.resolve(".git")) || (!Files.isRegularFile(candidate.resolve(RvcRepository.INDEX_JSON)) && !Files.isRegularFile(candidate.resolve(RvcSemanticRepository.MANIFEST))))
        {
            return false;
        }

        try (Git git = Git.open(candidate.toFile()))
        {
            git.getRepository();
            return true;
        }
        catch (Exception e)
        {
            Litematica.LOGGER.debug("RvcProjectService: rejected invalid RVC project repository '{}': {}", candidate, e.getMessage());
            return false;
        }
    }

    public static boolean isSemanticProject(Path repositoryDirectory)
    {
        return Files.isRegularFile(repositoryDirectory.resolve(RvcSemanticRepository.MANIFEST));
    }

    public static boolean isProjectRepository(Path candidate)
    {
        Objects.requireNonNull(candidate, "candidate");
        return isValidProjectRepository(candidate);
    }

    private static ActiveSemanticProject readActiveSemanticProject(Path repositoryDirectory) throws IOException
    {
        Objects.requireNonNull(repositoryDirectory, "repositoryDirectory");

        if (!isSemanticProject(repositoryDirectory))
        {
            throw new IOException("Project editor currently supports semantic RVC projects only");
        }

        RvcManifest manifest = RvcSemanticRepository.readManifest(repositoryDirectory);
        RvcLocalState localState = RvcSemanticRepository.readLocalState(repositoryDirectory);
        String siteId = localState.activeSite();
        RvcManifest.Site site = manifest.site(siteId);
        RvcLocalState.SitePlacement placement = localState.sites().get(siteId);

        if (placement == null)
        {
            throw new IOException("Missing local placement for active RVC site: " + siteId);
        }

        return new ActiveSemanticProject(manifest, localState, siteId, site, placement);
    }

    private static void writeSemanticRegions(Path repositoryDirectory, ActiveSemanticProject project,
                                             List<RvcManifest.Region> regions) throws IOException
    {
        RvcManifest.Site updatedSite = project.site().withRegions(regions);
        RvcSemanticRepository.writeVersionedProjectFiles(repositoryDirectory, project.manifest().withSite(project.siteId(), updatedSite));
    }

    private static void deleteRecursively(Path directory) throws IOException
    {
        Files.walkFileTree(directory, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }

                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Level resolveSemanticCaptureWorld(Level currentWorld)
    {
        Minecraft minecraft = Minecraft.getInstance();

        if (!minecraft.hasSingleplayerServer())
        {
            return currentWorld;
        }

        MinecraftServer server = minecraft.getSingleplayerServer();

        if (server == null)
        {
            return currentWorld;
        }

        ServerLevel serverLevel = server.getLevel(currentWorld.dimension());
        return serverLevel != null ? serverLevel : currentWorld;
    }

    private static <T> T runOnSemanticCaptureWorld(Level captureWorld, SemanticCaptureAction<T> action) throws Exception
    {
        if (captureWorld instanceof ServerLevel serverLevel)
        {
            MinecraftServer server = serverLevel.getServer();

            if (!server.isSameThread())
            {
                try
                {
                    return server.submit(() ->
                    {
                        try
                        {
                            return action.run(serverLevel);
                        }
                        catch (Exception e)
                        {
                            throw new CompletionException(e);
                        }
                    }).get();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for server-authoritative RVC capture", e);
                }
                catch (ExecutionException e)
                {
                    throw unwrapSemanticCaptureException(e.getCause());
                }
            }
        }

        return action.run(captureWorld);
    }

    private static Exception unwrapSemanticCaptureException(Throwable throwable)
    {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;

        if (cause instanceof RuntimeException e)
        {
            throw e;
        }

        if (cause instanceof Error e)
        {
            throw e;
        }

        if (cause instanceof Exception e)
        {
            return e;
        }

        return new IOException("Server-authoritative RVC capture failed", cause);
    }

    @FunctionalInterface
    private interface SemanticCaptureAction<T>
    {
        T run(Level captureWorld) throws Exception;
    }

    static RvcManifest.Site createMainSiteFromSelection(String siteName, String dimensionId, AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        validateProjectName(siteName);

        BlockPos origin = selection.getEffectiveOrigin();
        List<RvcManifest.Region> regions = createRegionsFromSelection(selection, origin, List.of());

        return new RvcManifest.Site("main", siteName, dimensionId, regions, Map.of());
    }

    static List<RvcManifest.Region> createRegionsFromSelection(AreaSelection selection, BlockPos origin, List<RvcManifest.Region> existingRegions)
    {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(existingRegions, "existingRegions");

        List<Box> boxes = new ArrayList<>(getValidBoxes(selection));
        boxes.sort(Comparator.comparing(box -> box.getName() == null ? "" : box.getName()));

        if (boxes.isEmpty())
        {
            throw new IllegalArgumentException("RVC project has no valid area boxes");
        }

        List<RvcManifest.Region> regions = new ArrayList<>();
        Set<String> usedRegionIds = new HashSet<>();

        for (Box box : boxes)
        {
            BlockPos pos1 = box.getPos1();
            BlockPos pos2 = box.getPos2();

            if (pos1 == null || pos2 == null)
            {
                continue;
            }

            BlockPos min = fi.dy.masa.litematica.util.PositionUtils.getMinCorner(pos1, pos2);
            BlockPos max = fi.dy.masa.litematica.util.PositionUtils.getMaxCorner(pos1, pos2);
            BlockPos relativeMin = min.subtract(origin);
            BlockPos size = max.subtract(min).offset(1, 1, 1);
            String regionName = box.getName();
            String regionBoundsKey = regionBoundsKey(blockPosToList(relativeMin), blockPosToList(size));
            String regionId = matchingExistingRegionId(regionName, regionBoundsKey, existingRegions, usedRegionIds);

            if (regionId == null)
            {
                regionId = uniqueRegionId(regionName, usedRegionIds);
            }
            else
            {
                usedRegionIds.add(regionId);
            }

            regions.add(new RvcManifest.Region(
                    regionId,
                    regionName,
                    blockPosToList(relativeMin),
                    blockPosToList(size)
            ));
        }

        return List.copyOf(regions);
    }

    static int countValidSelectionRegions(AreaSelection selection)
    {
        Objects.requireNonNull(selection, "selection");
        return getValidBoxes(selection).size();
    }

    @Nullable
    private static String matchingExistingRegionId(String regionName, String regionBoundsKey, List<RvcManifest.Region> existingRegions, Set<String> usedRegionIds)
    {
        for (RvcManifest.Region region : existingRegions)
        {
            if (!usedRegionIds.contains(region.id()) && region.name().equals(regionName))
            {
                return region.id();
            }
        }

        for (RvcManifest.Region region : existingRegions)
        {
            if (!usedRegionIds.contains(region.id()) && regionBoundsKey(region).equals(regionBoundsKey))
            {
                return region.id();
            }
        }

        return null;
    }

    private static String regionBoundsKey(RvcManifest.Region region)
    {
        return regionBoundsKey(region.min(), region.size());
    }

    private static String regionBoundsKey(List<Integer> min, List<Integer> size)
    {
        return min + "|" + size;
    }

    static RvcLocalState.SitePlacement createSitePlacement(BlockPos origin, String dimensionId)
    {
        return new RvcLocalState.SitePlacement(dimensionId, blockPosToList(origin), "");
    }

    public static BlockPos resolveSchematicWorldOrigin(Path repositoryDirectory) throws IOException
    {
        List<Box> boxes = readTrackedBoxes(repositoryDirectory);
        Pair<BlockPos, BlockPos> corners = PositionUtils.getEnclosingAreaCorners(boxes);

        if (corners == null)
        {
            throw new IOException("RVC project has no tracked sub-regions");
        }

        return corners.getLeft();
    }

    private static List<Box> readTrackedBoxes(Path repositoryDirectory) throws IOException
    {
        AreaSelection selection = readProjectAreaSelection(repositoryDirectory);

        if (selection == null)
        {
            throw new IOException("RVC project has no readable area selection");
        }

        List<Box> boxes = getValidBoxes(selection);

        if (boxes.isEmpty())
        {
            throw new IOException("RVC project has no valid area boxes");
        }

        return boxes;
    }

    private static LitematicaSchematic reloadLitematicaSchematic(Path structureFile)
    {
        SchematicHolder holder = SchematicHolder.getInstance();

        for (LitematicaSchematic schematic : new ArrayList<>(holder.getAllSchematics()))
        {
            if (structureFile.equals(schematic.getFile()))
            {
                holder.removeSchematic(schematic);
            }
        }

        return holder.getOrLoad(structureFile);
    }

    private static StructureTemplate createStructureFromIndexSubRegionsOrFallbackToCurrentPositionUtilsGetValidBoxes(Path repositoryDirectory, Level world, @Nullable AreaSelection currentSelectionFallback, boolean ignoreEntities)
    {
        AreaSelection localSelection = readProjectAreaSelection(repositoryDirectory);
        List<Box> boxes;

        if (localSelection != null)
        {
            boxes = getValidBoxes(localSelection);

            if (!boxes.isEmpty())
            {
                return createStructureFromSelectionBoxes(world, boxes, ignoreEntities);
            }
        }

        boxes = fallbackToCurrentPositionUtilsGetValidBoxes(currentSelectionFallback);
        return createStructureFromSelectionBoxes(world, boxes, ignoreEntities);
    }

    private static List<Box> getValidBoxes(AreaSelection selection)
    {
        return PositionUtils.getValidBoxes(selection);
    }

    private static List<Box> fallbackToCurrentPositionUtilsGetValidBoxes(@Nullable AreaSelection currentSelectionFallback)
    {
        if (currentSelectionFallback == null)
        {
            return List.of();
        }

        return PositionUtils.getValidBoxes(currentSelectionFallback);
    }

    @Nullable
    private static JsonObject readJsonObject(Path file)
    {
        if (!Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static JsonArray blockPosToArray(BlockPos pos)
    {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    private static List<Integer> blockPosToList(BlockPos pos)
    {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos blockPosFromList(List<Integer> values)
    {
        if (values == null || values.size() != 3)
        {
            throw new IllegalArgumentException("RVC position must contain three coordinates");
        }

        return new BlockPos(values.get(0), values.get(1), values.get(2));
    }

    private static String uniqueRegionId(String name, Set<String> usedIds)
    {
        String base = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");

        if (base.isBlank())
        {
            base = "region";
        }

        String candidate = base;
        int index = 2;

        while (!usedIds.add(candidate))
        {
            candidate = base + "_" + index;
            index++;
        }

        return candidate;
    }

    @Nullable
    private static BlockPos readBlockPosArray(JsonObject obj, String key)
    {
        if (!obj.has(key) || !obj.get(key).isJsonArray())
        {
            return null;
        }

        JsonArray arr = obj.get(key).getAsJsonArray();

        if (arr.size() != 3)
        {
            return null;
        }

        return new BlockPos(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
    }

    private static StructureTemplate createStructureFromSelectionBoxes(Level world, List<Box> boxes, boolean ignoreEntities)
    {
        return RvcStructure.createFromWorld(world, boxes, ignoreEntities);
    }

    private static String normalizeDisplayName(String repositoryName)
    {
        if (repositoryName == null || repositoryName.isBlank())
        {
            return "rvc-project-" + Instant.now().toEpochMilli();
        }

        return repositoryName.trim();
    }

    private static void validateProjectName(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            throw new IllegalArgumentException("RVC project name must not be blank");
        }
    }

    private static String normalizeCommitMessage(String message)
    {
        String trimmed = message.trim();

        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException("Commit message must not be blank");
        }

        return trimmed;
    }

    private static String toDirectoryName(String displayName)
    {
        if (displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0)
        {
            throw new IllegalArgumentException("RVC repository name must not contain path separators: " + displayName);
        }

        return displayName;
    }

    private static String currentBranch(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS))
        {
            throw new IOException("RVC repository is not on a local branch");
        }

        return fullBranch;
    }

    private static String historyBranchRef(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            rememberHistoryBranch(repository, fullBranch);
            return fullBranch;
        }

        String configuredBranch = repository.getConfig().getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY);

        if (configuredBranch != null && !configuredBranch.isBlank() && repository.resolve(configuredBranch) != null)
        {
            return configuredBranch;
        }

        String defaultBranch = Constants.R_HEADS + DEFAULT_BRANCH;

        if (repository.resolve(defaultBranch) != null)
        {
            rememberHistoryBranch(repository, defaultBranch);
            return defaultBranch;
        }

        List<Ref> localBranches = repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);

        if (!localBranches.isEmpty())
        {
            String fallbackBranch = localBranches.get(0).getName();
            rememberHistoryBranch(repository, fallbackBranch);
            return fallbackBranch;
        }

        return Constants.HEAD;
    }

    private static String pushBranchRef(Repository repository) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (branch.startsWith(Constants.R_HEADS))
        {
            return branch;
        }

        throw new IOException("RVC repository has no local branch to push");
    }

    private static void configureRemoteTracking(Repository repository, StoredConfig config) throws IOException
    {
        String branch = historyBranchRef(repository);

        if (!branch.startsWith(Constants.R_HEADS))
        {
            return;
        }

        String shortBranchName = branch.substring(Constants.R_HEADS.length());
        config.setString("branch", shortBranchName, "remote", "origin");
        config.setString("branch", shortBranchName, "merge", branch);
    }

    @Nullable
    private static CredentialsProvider githubCredentialsProvider(Path repositoryDirectory, Repository repository) throws IOException
    {
        String remoteUrl = repository.getConfig().getString("remote", "origin", "url");

        if (!RvcGithubAuth.isGithubHttpsRemote(remoteUrl))
        {
            return null;
        }

        String token = RvcGithubAuth.readAccessTokenForRepository(repositoryDirectory);

        if (token == null || token.isBlank())
        {
            throw new IOException("GitHub remote needs authorization. Open Project Settings and connect GitHub.");
        }

        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    private static TransportConfigCallback sshTransportConfigCallback()
    {
        return RvcProjectService::configureSshTransport;
    }

    private static void configureSshTransport(Transport transport)
    {
        if (transport instanceof SshTransport sshTransport)
        {
            Path homeDirectory = resolveSshHomeDirectory();
            Path sshDirectory = homeDirectory.resolve(".ssh");
            SshdSessionFactory sessionFactory = new SshdSessionFactoryBuilder()
                    .setHomeDirectory(homeDirectory.toFile())
                    .setSshDirectory(sshDirectory.toFile())
                    .setDefaultIdentities(sshDir -> defaultSshIdentities(sshDir.toPath()))
                    .build(null);
            sshTransport.setSshSessionFactory(sessionFactory);
        }
    }

    private static Path resolveSshHomeDirectory()
    {
        String envHome = System.getenv("HOME");

        if (envHome != null && !envHome.isBlank())
        {
            Path home = Path.of(envHome);

            if (hasDefaultSshIdentity(home))
            {
                return home;
            }
        }

        String propertyHome = System.getProperty("user.home");

        if (propertyHome != null && !propertyHome.isBlank())
        {
            return Path.of(propertyHome);
        }

        return Path.of(".");
    }

    private static boolean hasDefaultSshIdentity(Path homeDirectory)
    {
        Path sshDirectory = homeDirectory.resolve(".ssh");

        for (String identityName : DEFAULT_SSH_IDENTITY_NAMES)
        {
            if (Files.isRegularFile(sshDirectory.resolve(identityName)))
            {
                return true;
            }
        }

        return false;
    }

    private static List<Path> defaultSshIdentities(Path sshDirectory)
    {
        List<Path> identities = new ArrayList<>();

        for (String identityName : DEFAULT_SSH_IDENTITY_NAMES)
        {
            Path identity = sshDirectory.resolve(identityName);

            if (Files.isRegularFile(identity))
            {
                identities.add(identity);
            }
        }

        return List.copyOf(identities);
    }

    private static String sshIdentityDiagnostic()
    {
        Path homeDirectory = resolveSshHomeDirectory();
        Path sshDirectory = homeDirectory.resolve(".ssh");
        List<Path> identities = defaultSshIdentities(sshDirectory);

        if (identities.isEmpty())
        {
            return "RVC could not find an SSH private key. Java home=" + homeDirectory + "; expected one of " + DEFAULT_SSH_IDENTITY_NAMES + " in " + sshDirectory + ".";
        }

        return "RVC found SSH key file(s) " + identities + " but JGit could not use them. If the key has a passphrase, RVC needs passphrase prompt support; for the current MVP use an unencrypted OpenSSH key or configure a supported key file for github.com.";
    }

    private static String collectThrowableMessages(Throwable throwable)
    {
        StringBuilder builder = new StringBuilder();

        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            if (current.getMessage() != null)
            {
                builder.append(current.getMessage()).append('\n');
            }
        }

        return builder.toString();
    }

    private static void rememberCurrentBranchForHistory(Repository repository) throws IOException
    {
        String fullBranch = repository.getFullBranch();

        if (fullBranch != null && fullBranch.startsWith(Constants.R_HEADS))
        {
            rememberHistoryBranch(repository, fullBranch);
        }
    }

    private static void rememberHistoryBranch(Repository repository, String fullBranch) throws IOException
    {
        StoredConfig config = repository.getConfig();

        if (!fullBranch.equals(config.getString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY)))
        {
            config.setString(GIT_CONFIG_SECTION, null, GIT_CONFIG_HISTORY_BRANCH_KEY, fullBranch);
            config.save();
        }
    }

    public record Project(String name, Path directory)
    {
    }

    public record ProjectSummary(String name, int versionCount, @Nullable BlockPos origin)
    {
    }

    public record ProjectEditorState(String projectName, String siteId, String siteName, String siteDimension,
                                     String localDimension, BlockPos localOrigin, String worldHint,
                                     List<RvcManifest.Region> regions)
    {
        public ProjectEditorState
        {
            regions = List.copyOf(regions);
        }
    }

    public record CommitInfo(String id, String shortId, String message, String description, String author, String time,
                             int subRegionCount, String changes)
    {
    }

    public record TrackingOverlay(SchematicPlacement placement, SchematicVerifier verifier, boolean verifierStarted)
    {
    }

    public record SchematicWorldRestore(BlockPos schematicWorldOrigin, int boxCount, TrackingOverlay overlay)
    {
    }

    public record SemanticScanResult(String siteId, int unchangedChunks, int changedChunks, int addedChunks,
                                     int removedChunks, int unknownChunks)
    {
        public static SemanticScanResult compare(String siteId, Map<String, String> expectedChunks, RvcCaptureEngine.Result scan)
        {
            Set<String> keys = new HashSet<>();
            keys.addAll(expectedChunks.keySet());
            keys.addAll(scan.chunkObjects().keySet());
            keys.addAll(scan.unknownChunks());

            int unchanged = 0;
            int changed = 0;
            int added = 0;
            int removed = 0;
            int unknown = 0;

            for (String key : keys)
            {
                if (scan.unknownChunks().contains(key))
                {
                    unknown++;
                    continue;
                }

                String expected = expectedChunks.get(key);
                String actual = scan.chunkObjects().get(key);

                if (expected == null && actual != null)
                {
                    added++;
                }
                else if (expected != null && actual == null)
                {
                    removed++;
                }
                else if (Objects.equals(expected, actual))
                {
                    unchanged++;
                }
                else
                {
                    changed++;
                }
            }

            return new SemanticScanResult(siteId, unchanged, changed, added, removed, unknown);
        }

        public boolean clean()
        {
            return this.dirtyChunks() == 0 && this.unknownChunks == 0;
        }

        public int dirtyChunks()
        {
            return this.changedChunks + this.addedChunks + this.removedChunks;
        }

        public int knownChunks()
        {
            return this.unchangedChunks + this.changedChunks + this.addedChunks + this.removedChunks;
        }
    }

    public record UpdateAreasResult(@Nullable RevCommit commit, int regionCount)
    {
    }

    public record Result(Path repositoryDirectory, String commitId)
    {
    }

    public record EmptyProjectResult(Path repositoryDirectory, String projectName)
    {
    }

    private record ActiveSemanticProject(RvcManifest manifest, RvcLocalState localState, String siteId,
                                         RvcManifest.Site site, RvcLocalState.SitePlacement placement)
    {
    }
}
