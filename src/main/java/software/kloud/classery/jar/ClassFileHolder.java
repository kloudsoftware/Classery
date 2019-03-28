package software.kloud.classery.jar;

import java.io.File;
import java.util.Objects;

public class ClassFileHolder {
    /**
     * File descriptor pointing to the .class file on the file system
     */
    private File classFile;
    /**
     * Fully qualified class name in Binary format, consisting of package and class name
     * see: https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html
     */
    private String absoluteClassName;

    public ClassFileHolder(File classFile, String absoluteClassName) {
        this.classFile = classFile;
        this.absoluteClassName = absoluteClassName;
    }

    public File getClassFile() {
        return classFile;
    }

    public String getAbsoluteClassName() {
        return absoluteClassName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClassFileHolder that = (ClassFileHolder) o;
        return Objects.equals(classFile, that.classFile) &&
                Objects.equals(absoluteClassName, that.absoluteClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classFile, absoluteClassName);
    }
}
