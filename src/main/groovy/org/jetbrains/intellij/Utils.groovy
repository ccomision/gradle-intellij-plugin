package org.jetbrains.intellij

import com.google.common.base.Predicate
import com.jetbrains.plugin.structure.intellij.utils.StringUtil
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.AbstractFileFilter
import org.apache.commons.io.filefilter.FalseFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.ivy.internal.artifact.DefaultIvyArtifact
import org.gradle.api.tasks.SourceSet
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.JavaForkOptions
import org.jetbrains.annotations.NotNull
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

import java.util.regex.Pattern

class Utils {
    public static final Pattern VERSION_PATTERN = Pattern.compile('^([A-Z]{2})-([0-9.A-z]+)\\s*$')

    @NotNull
    static SourceSet mainSourceSet(@NotNull Project project) {
        JavaPluginConvention javaConvention = project.convention.getPlugin(JavaPluginConvention)
        javaConvention.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
    }

    @NotNull
    static DefaultIvyArtifact createJarDependency(File file, String configuration, File baseDir) {
        return createDependency(baseDir, file, configuration, "jar", "jar")
    }

    @NotNull
    static DefaultIvyArtifact createDirectoryDependency(File file, String configuration, File baseDir) {
        return createDependency(baseDir, file, configuration, "", "directory")
    }

    private static DefaultIvyArtifact createDependency(File baseDir, File file, String configuration,
                                                       String extension, String type) {
        def relativePath = baseDir.toURI().relativize(file.toURI()).getPath()
        def name = extension ? relativePath - ".$extension" : relativePath
        def artifact = new DefaultIvyArtifact(file, name, extension, type, null)
        artifact.conf = configuration
        return artifact
    }

    @NotNull
    static FileCollection sourcePluginXmlFiles(@NotNull Project project) {
        Set<File> result = new HashSet<>()
        mainSourceSet(project).resources.srcDirs.each {
            def pluginXml = new File(it, "META-INF/plugin.xml")
            if (pluginXml.exists()) {
                try {
                    if (parseXml(pluginXml).name() == 'idea-plugin') {
                        result += pluginXml
                    }
                } catch (SAXParseException ignore) {
                    IntelliJPlugin.LOG.warn("Cannot read ${pluginXml}. Skipping.")
                    IntelliJPlugin.LOG.debug("Cannot read ${pluginXml}", ignore)
                }
            }
        }
        project.files(result)
    }

    @NotNull
    static Map<String, Object> getIdeaSystemProperties(@NotNull File configDirectory,
                                                       @NotNull File systemDirectory,
                                                       @NotNull File pluginsDirectory,
                                                       @NotNull List<String> requirePluginIds) {
        def result = ["idea.config.path" : configDirectory.absolutePath,
                      "idea.system.path" : systemDirectory.absolutePath,
                      "idea.plugins.path": pluginsDirectory.absolutePath]
        if (!requirePluginIds.empty) {
            result.put("idea.required.plugins.id", requirePluginIds.join(","))
        }
        result
    }

    static def configDir(@NotNull String sandboxDirectoryPath, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$sandboxDirectoryPath/config$suffix"
    }

    static def systemDir(@NotNull String sandboxDirectoryPath, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$sandboxDirectoryPath/system$suffix"
    }

    static def pluginsDir(@NotNull String sandboxDirectoryPath, boolean inTests) {
        def suffix = inTests ? "-test" : ""
        "$sandboxDirectoryPath/plugins$suffix"
    }

    @NotNull
    static List<String> getIdeaJvmArgs(@NotNull JavaForkOptions options,
                                       @NotNull List<String> originalArguments,
                                       @NotNull File ideaDirectory) {
        if (options.maxHeapSize == null) options.maxHeapSize = "512m"
        if (options.minHeapSize == null) options.minHeapSize = "256m"
        boolean hasPermSizeArg = false
        List<String> result = []
        for (String arg : originalArguments) {
            if (arg.startsWith("-XX:MaxPermSize")) {
                hasPermSizeArg = true
            }
            result += arg
        }

        def bootJar = new File(ideaDirectory, "lib/boot.jar")
        if (bootJar.exists()) result += "-Xbootclasspath/a:$bootJar.absolutePath"
        if (!hasPermSizeArg) result += "-XX:MaxPermSize=250m"
        return result
    }

    @NotNull
    static File ideaSdkDirectory(@NotNull IntelliJPluginExtension extension) {
        def path = extension.alternativeIdePath
        if (path) {
            def dir = ideaDir(path)
            if (!dir.exists()) {
                def ideaDirectory = extension.ideaDependency.classes
                IntelliJPlugin.LOG.error("Cannot find alternate SDK path: $dir. Default IDEA will be used : $ideaDirectory")
                return ideaDirectory
            }
            return dir
        }
        return extension.ideaDependency.classes
    }

    @NotNull
    static String ideaBuildNumber(@NotNull File ideaDirectory) {
        if (OperatingSystem.current().isMacOsX()) {
            def file = new File(ideaDirectory, "Resources/build.txt")
            if (file.exists()) {
                return file.getText('UTF-8').trim()
            }
        }
        return new File(ideaDirectory, "build.txt").getText('UTF-8').trim()
    }

    @NotNull
    static File ideaDir(@NotNull String path) {
        File dir = new File(path)
        return dir.name.endsWith(".app") ? new File(dir, "Contents") : dir
    }

    // todo: collect all ids for multiproject configuration
    static def getPluginIds(@NotNull Project project) {
        Set<String> ids = new HashSet<>()
        sourcePluginXmlFiles(project).files.each {
            def pluginXml = parseXml(it)
            ids += pluginXml.id*.text()
        }
        return ids.size() == 1 ? [ids.first()] : Collections.emptyList()
    }

    static Node parseXml(File file) {
        def parser = new XmlParser(false, true, true)
        parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        parser.setErrorHandler(new ErrorHandler() {
            @Override
            void warning(SAXParseException e) throws SAXException {

            }

            @Override
            void error(SAXParseException e) throws SAXException {
                throw e
            }

            @Override
            void fatalError(SAXParseException e) throws SAXException {
                throw e
            }
        })
        InputStream inputStream = new FileInputStream(file)
        InputSource input = new InputSource(new InputStreamReader(inputStream, "UTF-8"))
        input.setEncoding("UTF-8")
        try {
            return parser.parse(input)
        }
        finally {
            inputStream.close()
        }
    }

    static boolean isJarFile(@NotNull File file) {
        return StringUtil.endsWithIgnoreCase(file.name, ".jar")
    }

    static boolean isZipFile(@NotNull File file) {
        return StringUtil.endsWithIgnoreCase(file.name, ".zip")
    }

    @NotNull
    static parsePluginDependencyString(@NotNull String s) {
        if (new File(s).exists()) {
            return new Tuple(s, null, null)
        }

        def id = null, version = null, channel = null
        def idAndVersion = s.split('[:]', 2)
        if (idAndVersion.length == 1) {
            def idAndChannel = idAndVersion[0].split('[@]', 2)
            id = idAndChannel[0]
            channel = idAndChannel.length > 1 ? idAndChannel[1] : null
        } else if (idAndVersion.length == 2) {
            def versionAndChannel = idAndVersion[1].split('[@]', 2)
            id = idAndVersion[0]
            version = versionAndChannel[0]
            channel = versionAndChannel.length > 1 ? versionAndChannel[1] : null
        }
        return new Tuple(id ?: null, version ?: null, channel ?: null)
    }

    static String stringInput(input) {
        input = input instanceof Closure ? (input as Closure).call() : input
        return input?.toString()
    }

    @NotNull
    static Collection<File> collectJars(@NotNull File directory, @NotNull final Predicate<File> filter,
                                        boolean recursively) {
        return FileUtils.listFiles(directory, new AbstractFileFilter() {
            @Override
            boolean accept(File file) {
                return StringUtil.endsWithIgnoreCase(file.getName(), ".jar") && filter.apply(file)
            }
        }, recursively ? TrueFileFilter.INSTANCE : FalseFileFilter.FALSE)
    }

    static String getBuiltinJbreVersion(@NotNull File ideaDirectory) {
        def dependenciesFile = new File(ideaDirectory, "dependencies.txt")
        if (dependenciesFile.exists()) {
            def properties = new Properties()
            def reader = new FileReader(dependenciesFile)
            try {
                properties.load(reader)
                return properties.getProperty('jdkBuild')
            }
            catch (IOException ignore) {
            }
            finally {
                reader.close()
            }
        }
        return null
    }
}
