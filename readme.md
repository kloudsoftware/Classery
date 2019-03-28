# Classery
Library for loading arbitrary jars into a jvm classpath on runtime. Takes multiple directories, searches them for jars, unpacks them into a given directory and loads all .class Files


### Warning

The classloader does not check for dependencies currently. If it encounters unresolved classes it will crash.

In general, you can shoot yourself in the foot very easily. Use caution!
