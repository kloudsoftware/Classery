package software.kloud.classery.loader;

import software.kloud.classery.jar.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ClasseryLoader extends ClassLoader {
    private final JarFileScanner jarFileScanner;

    public ClasseryLoader(File baseDirectory, List<File> pluginDirectory) throws IOException {
        assert baseDirectory.isDirectory();
        this.jarFileScanner = new JarFileScanner(baseDirectory);
        jarFileScanner.init();
        for (File directory : pluginDirectory) {
            assert directory.isDirectory();
            this.jarFileScanner.addDirectory(directory);
        }
    }

    public List<Class<?>> load() throws IOException, JarUnpackingException {
        this.jarFileScanner.scan(JarFileScanner.ScanMode.SKIP_ALREADY_SCANNED);
        Set<JarStateHolder> foundJars = jarFileScanner.getAll();

        Set<ClassFileHolder> foundClassFiles = foundJars.stream()
                .map(jar -> new PackageAwareJarDirectoryTraverser(jar.getUnzippedDirectory()))
                .map(PackageAwareJarDirectoryTraverser::traverse)
                .flatMap(List::stream)
                .collect(Collectors.toSet());

        List<Class<?>> loadedClazzes = new ArrayList<>();
        for (ClassFileHolder foundClassFile : foundClassFiles) {
            File classFile = foundClassFile.getClassFile();
            String className = foundClassFile.getAbsoluteClassName();
            int length = (int) classFile.length();
            byte[] buf = new byte[length];

            try (InputStream is = new FileInputStream(classFile)) {
                var read = is.read(buf);
                if (read < 1) {
                    throw new IOException("Corrupt class file found");
                }
            }
            Class<?> clazz = this.defineClass(className, buf, 0, length);
            loadedClazzes.add(clazz);
        }
        return loadedClazzes;
    }
}
