package software.kloud.classery.jar;

import java.io.File;

@SuppressWarnings("WeakerAccess")
public class JarStateHolder {
    private File zippedJarFile;
    private String jarFileHash;
    private File unzippedDirectory;

    public JarStateHolder() {
    }

    public JarStateHolder(File zippedJarFile) {
        this.zippedJarFile = zippedJarFile;
        this.unzippedDirectory = null;
    }

    public File getZippedJarFile() {
        return zippedJarFile;
    }

    public void setZippedJarFile(File zippedJarFile) {
        this.zippedJarFile = zippedJarFile;
    }

    public File getUnzippedDirectory() {
        return unzippedDirectory;
    }

    public void setUnzippedDirectory(File directory) {
        this.unzippedDirectory = directory;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof JarStateHolder)) return false;
        JarStateHolder other = (JarStateHolder) obj;

        if (this.unzippedDirectory == null) {
            return this.zippedJarFile.equals(other.zippedJarFile) && null == other.unzippedDirectory;
        }

        return this.zippedJarFile.equals(other.zippedJarFile)
                && this.unzippedDirectory.equals(other.unzippedDirectory);
    }

    public String getJarFileHash() {
        return jarFileHash;
    }

    public void setJarFileHash(String jarFileHash) {
        this.jarFileHash = jarFileHash;
    }
}
