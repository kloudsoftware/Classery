package software.kloud.classery.jar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.kloud.classery.utils.FileHasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JarFileScanner {
    private static final Logger logger = LoggerFactory.getLogger(JarFileScanner.class);
    private static final CacheTypeReference CACHE_TYPE = new CacheTypeReference();
    private static final ObjectMapper objm = new ObjectMapper();
    private static int DEFAULT_THREAD_COUNT = 4;
    private final List<File> pluginDirectories;
    private final CompletionService<JarStateHolder> completionService;
    private Map<File, Set<JarStateHolder>> jarFileTmpMap;
    private Map<File, FileHasher> jarFileHasherMap;
    private boolean hasScanned = false;
    private Supplier<File> baseDirectory;
    private boolean initialized = false;

    // DEBUG fields
    // Not part of public API
    // Override this class in a test and call setDebug() to start recording these metrics
    private boolean debug = false;
    private AtomicInteger debugCountOfUnpackedJarEntries = new AtomicInteger(0);
    private AtomicInteger debugCountOfUnpackedJarFiles = new AtomicInteger(0);
    private AtomicInteger debugCountOfSkippedJarEntries = new AtomicInteger(0);
    private AtomicInteger debugCountOfSkippedJarFiles = new AtomicInteger(0);

    public JarFileScanner(File baseDir, int threadCount) throws IOException {
        this.jarFileTmpMap = new HashMap<>();
        this.jarFileHasherMap = new HashMap<>();
        this.pluginDirectories = new ArrayList<>();
        this.completionService = new ExecutorCompletionService<>(Executors.newFixedThreadPool(threadCount));
        this.baseDirectory = () -> baseDir;
    }

    public JarFileScanner(File baseDir) throws IOException {
        this(baseDir, DEFAULT_THREAD_COUNT);
    }

    private File getCacheFile() {
        return new File(this.baseDirectory.get(), "plugins.cache");
    }

    public void init() throws IOException {
        File cacheFile = getCacheFile();
        if (!cacheFile.isFile()) {
            if (!cacheFile.createNewFile()) {
                logger.error("Could not create fresh cache file. Filesystem corrupt or not enough permissions?");
                throw new IOException("Could not create fresh cache file. Filesystem corrupt or not enough permissions?");
            }
            initialized = true;
            return;
        }
        if (cacheFile.length() == 0) {
            initialized = true;
            return;
        }
        this.jarFileTmpMap = objm.readValue(cacheFile, CACHE_TYPE);
        initialized = true;
    }

    private void writeCacheToDisk() throws IOException {
        File cacheFile = getCacheFile();
        if (!cacheFile.isFile()) {
            if (!cacheFile.createNewFile()) {
                logger.error("Could not create fresh cache file. Filesystem corrupt or not enough permissions?");
                throw new IOException("Could not create fresh cache file. Filesystem corrupt or not enough permissions?");
            }
        }
        objm.writeValue(cacheFile, this.jarFileTmpMap);
    }

    public void addDirectory(File directory) {
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("File is not a directory");
        }

        pluginDirectories.add(directory);
    }

    public void setUnpackingBaseDir(File baseDir) {
        this.baseDirectory = () -> baseDir;
    }

    public void scan(ScanMode scanMode) throws JarUnpackingException, IOException {
        if (!initialized) {
            throw new IllegalStateException("Scanner is not initialized, call init() first!");
        }
        try {
            if (scanMode == ScanMode.FORCE) {
                jarFileTmpMap.clear();
            }

            for (File pluginDirectory : pluginDirectories) {
                Set<JarStateHolder> scanResult = scanDirectory(pluginDirectory);
                jarFileTmpMap.put(pluginDirectory, scanResult);
            }
        } finally {
            this.writeCacheToDisk();
        }

        hasScanned = true;
    }

    private Set<JarStateHolder> scanDirectory(File directory) throws JarUnpackingException, IOException {
        var jarFiles = directory.listFiles((dir, name) -> name.endsWith(".jar") | name.endsWith(".war"));
        if (null == jarFiles) return Collections.emptySet();

        var res = new HashSet<JarStateHolder>();
        int futuresSpawned = 0;
        for (File zippedJarFile : jarFiles) {
            if (!zippedJarFile.isFile()) continue;

            var hasher = new FileHasher(zippedJarFile);
            var hash = hasher.hashMD5();

            var hasAlreadyScannedByName = this.getAllScannedJars()
                    .anyMatch(j -> j.getZippedJarFile().getName().equals(zippedJarFile.getName()));

            var hasAlreadyScannedByHash = this.getAllScannedJars()
                    .filter(j -> j.getJarFileHash() != null)
                    .anyMatch(j -> j.getJarFileHash().equals(hash));

            if (hasAlreadyScannedByHash && hasAlreadyScannedByName) {
                logger.info(String.format(
                        "Skipping file %s. Hasn't changed since last scan. Use ScanMode.FORCE to force"
                        , zippedJarFile.getAbsolutePath())
                );
                if (debug) debugCountOfSkippedJarFiles.incrementAndGet();
                this.getAllScannedJars()
                        .filter(j -> j.getJarFileHash().equals(hash))
                        .findFirst()
                        .ifPresent(res::add);
                continue;
            } else if (hasAlreadyScannedByName) {
                Optional<File> unzippedDirectory = this.getAllScannedJars()
                        .filter(j -> j.getZippedJarFile().getName().equals(zippedJarFile.getName()))
                        .findFirst()
                        .map(JarStateHolder::getUnzippedDirectory);

                if (unzippedDirectory.isPresent()) {
                    FileUtils.cleanDirectory(unzippedDirectory.get());
                    if (!unzippedDirectory.get().delete()) {
                        throw new IOException("Could not delete old unzipped directory. Check filesystem");
                    }
                }
            }

            Callable<JarStateHolder> unpackFuture = () -> {
                if (debug) debugCountOfUnpackedJarFiles.incrementAndGet();

                File innerZipperJarFile = new File(zippedJarFile.getAbsolutePath());
                var holder = new JarStateHolder(innerZipperJarFile);
                res.add(holder);
                try (JarFile jarFile = new JarFile(innerZipperJarFile)) {
                    String cleanJarFileName = innerZipperJarFile.getName().replace(".jar", "");
                    File unzippedDirectory = unpackJarFileToDiskStorage(jarFile, cleanJarFileName);
                    holder.setUnzippedDirectory(unzippedDirectory);
                    holder.setJarFileHash(hash);
                } catch (IOException e) {
                    throw new JarUnpackingException("Failed to unpack Jar", e);
                }
                return holder;
            };
            completionService.submit(unpackFuture);
            futuresSpawned++;
        }

        int deltaReceived = futuresSpawned;
        try {
            while (deltaReceived > 0) {
                Future<JarStateHolder> maybeRanFuture = completionService.take();
                res.add(maybeRanFuture.get());
                deltaReceived--;
            }
        } catch (InterruptedException e) {
            throw new JarUnpackingException("Future was interrupted", e);
        } catch (ExecutionException e) {
            throw new JarUnpackingException("Future did not complete successfully", e);
        }

        return res;
    }

    /**
     * Unpacks a jar into a directory under the KMS-Store (see {@link LocalDiskStorage}). Returns directory for further processing
     * Runs in its own future
     *
     * @param jarfile JarFile to unpack
     * @return Directory in which the jarFile was unpacked
     * @throws IOException If not able to unpack jar
     */
    private File unpackJarFileToDiskStorage(JarFile jarfile, String name) throws IOException {
        var tmpDir = Files.createDirectory(new File(this.baseDirectory.get(), String.format("KMS-Plugin-%s", name)).toPath());

        Iterator<JarEntry> entryIterator = jarfile.entries().asIterator();

        while (entryIterator.hasNext()) {
            var entry = entryIterator.next();
            var destFile = new File(tmpDir.toFile(), entry.getName());
            final String destFilePath = destFile.getAbsolutePath();
            if (entry.getName().endsWith("/") && !destFile.isDirectory()) {
                if (!destFile.mkdir()) {
                    throw new IOException(String.format("Failed to create tmp directory: %s", destFilePath));
                }
                logger.info(String.format("Creating directory %s", destFilePath));
                continue;
            }

            if (debug) debugCountOfUnpackedJarEntries.incrementAndGet();

            logger.info(String.format("Unzipping file %s", destFilePath));
            try (var is = jarfile.getInputStream(entry)) {
                try (var fout = new FileOutputStream(destFile)) {
                    while (is.available() > 0) {
                        fout.write(is.read());
                    }
                }
            }
        }

        logger.info("Finished unzipping " + tmpDir.toString());
        return new File(tmpDir.toString());
    }

    public Set<JarStateHolder> getAll() {
        return this.streamAll()
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("WeakerAccess")
    public Stream<JarStateHolder> streamAll() {
        guardAccess();
        return this.jarFileTmpMap
                .values()
                .stream()
                .flatMap(Set::stream);
    }

    private void guardAccess() {
        if (!hasScanned)
            throw new IllegalStateException("Hasn't scanned yet");
    }

    private Set<JarStateHolder> getAllScannedJarForDirectory(File directory) {
        var found = this.jarFileTmpMap.get(directory);

        if (found == null) return Collections.emptySet();

        return found;
    }

    private Stream<JarStateHolder> getAllScannedJars() {
        return this.jarFileTmpMap
                .values()
                .stream()
                .flatMap(Set::stream);
    }

    public enum ScanMode {
        SKIP_ALREADY_SCANNED,
        FORCE;
    }

    /**
     * Sole reason for this class is use it as a type token when reading / writing to cache.
     * Using {@link TypeReference} inline leads to weird autoformatting with Intellij :(
     */
    private static final class CacheTypeReference extends TypeReference<Map<File, Set<JarStateHolder>>> {

    }

    // DEBUG methods
    // Not part of public API
    // Override this class in a test and call setDebug() to start recording these metrics

    void setDebug() {
        this.debug = true;
    }

    DebugHolder getDebugInfo() {
        return new DebugHolder(
                debugCountOfUnpackedJarEntries.get(),
                debugCountOfUnpackedJarFiles.get(),
                debugCountOfSkippedJarEntries.get(),
                debugCountOfSkippedJarFiles.get()

        );
    }

    protected static final class DebugHolder {
        final int countOfUnpackedJarEntries;
        final int countOfUnpackedJarFiles;
        final int countOfSkippedJarEntries;
        final int countOfSkippedJarFiles;

        DebugHolder(
                int countOfUnpackedJarEntries,
                int countOfUnpackedJarFiles,
                int countOfSkippedJarEntries,
                int countOfSkippedJarFiles
        ) {
            this.countOfUnpackedJarEntries = countOfUnpackedJarEntries;
            this.countOfUnpackedJarFiles = countOfUnpackedJarFiles;
            this.countOfSkippedJarEntries = countOfSkippedJarEntries;
            this.countOfSkippedJarFiles = countOfSkippedJarFiles;
        }
    }
}
