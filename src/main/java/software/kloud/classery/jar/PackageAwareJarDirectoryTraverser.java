package software.kloud.classery.jar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Traverses the given base directory for .class files with multiple threads.
 * Use a thread pool internally. Starts as many futures as first level directories,
 * seen from the root directory
 * <p>
 * TODO: Add more filtering?
 */
@SuppressWarnings("WeakerAccess")
public class PackageAwareJarDirectoryTraverser {
    public static final int DEFAULT_N_THREADS = 4;
    private static final List<String> commonJarDirectoriesNames = List.of("BOOT-INF/classes/", "META-INF/");
    private static final Logger logger = LoggerFactory.getLogger(PackageAwareJarDirectoryTraverser.class);
    private final File rootNode;
    private final CompletionService<Set<ClassFileHolder>> completionService;

    /**
     * Create a new traverser starting at {@code rootNode} with {@link PackageAwareJarDirectoryTraverser#DEFAULT_N_THREADS} threads
     *
     * @param rootNode root directory for traverser
     */
    public PackageAwareJarDirectoryTraverser(File rootNode) {
        this(rootNode, DEFAULT_N_THREADS);
    }

    /**
     * Create a new traverser starting at {@code rootNode} with {@code nThreads} threads
     *
     * @param rootNode root directory for traverser
     * @param nThreads number of threads to use
     */
    public PackageAwareJarDirectoryTraverser(File rootNode, int nThreads) {
        if (!rootNode.isDirectory()) {
            throw new IllegalArgumentException("rootNode is not a directory");
        }
        this.rootNode = rootNode;
        this.completionService = new ExecutorCompletionService<>(Executors.newFixedThreadPool(nThreads));
    }

    /**
     * Starts the traversing process
     *
     * @return All found .class files
     */
    public List<ClassFileHolder> traverse() {
        assert rootNode.isDirectory();
        int futuresCount = 0;

        for (File file : Objects.requireNonNull(rootNode.listFiles())) {
            if (file.isFile() && filterForClassFile(file)) {
                completionService.submit(() ->
                        Collections.singleton(new ClassFileHolder(file, "")));
                futuresCount++;
            } else if (file.isDirectory()) {
                completionService.submit(() -> traverseIntern(file, new HashSet<>()));
                futuresCount++;
            }
        }

        int deltaReceived = futuresCount;
        List<Set<ClassFileHolder>> pathPackageHolders = new ArrayList<>();
        while (deltaReceived > 0) {
            try {
                var future = completionService.take();
                pathPackageHolders.add(future.get());
                deltaReceived--;
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("skipped future");
            }
        }

        return pathPackageHolders
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toList());
    }

    /**
     * Traverses directories. Gets called recursively. Runs in its own future.
     *
     * @param node       Filesystem node to start looking from
     * @param foundSoFar Keeps track of already found classes
     * @return {@link Set} containing all already and newly found classes
     */
    private Set<ClassFileHolder> traverseIntern(File node, Set<ClassFileHolder> foundSoFar) {
        if (node.isFile() && filterForClassFile(node)) {
            var name = getBinaryClassNameFromFile(node);
            foundSoFar.add(new ClassFileHolder(node, name));
        } else if (node.isDirectory()) {
            for (File file : Objects.requireNonNull(node.listFiles())) {
                traverseIntern(file, foundSoFar);
            }
        }
        return foundSoFar;
    }

    private String getBinaryClassNameFromFile(File node) {
        var abs = node.getAbsolutePath();
        abs = abs.replace(rootNode.getAbsolutePath(), "");

        for (String commonJarDirectory : commonJarDirectoriesNames) {
            if (abs.contains(commonJarDirectory)) {
                abs = abs.replace(commonJarDirectory, "");
            }
        }

        abs = abs.replace(".class", "");
        abs = abs.replaceAll("/", ".");
        if (abs.startsWith(".")) {
            abs = abs.substring(1);
        }
        return abs;
    }

    private boolean filterForClassFile(File file) {
        return file.getName().endsWith(".class");
    }
}
