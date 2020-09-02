package ca.cutterslade.gradle.analyze

import groovy.transform.CompileStatic
import org.apache.maven.artifact.Artifact
import org.apache.maven.shared.dependency.analyzer.ClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.logging.Logger

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@CompileStatic
class ProjectDependencyResolver {
  static final String CACHE_NAME = 'ca.cutterslade.gradle.analyze.ProjectDependencyResolver.artifactClassCache'

  private final ClassAnalyzer classAnalyzer = new DefaultClassAnalyzer()
  private final DependencyAnalyzer dependencyAnalyzer = new ASMDependencyAnalyzer()

  private final ConcurrentHashMap<File, Set<String>> artifactClassCache
  private final Logger logger
  private final List<Configuration> require
  private final List<Configuration> allowedToUse
  private final List<Configuration> allowedToDeclare
  private final Iterable<File> classesDirs
  private final boolean logDependencyInformationToFile
  private final Path buildDirPath

  ProjectDependencyResolver(final Project project, final List<Configuration> require,
                            final List<Configuration> allowedToUse, final List<Configuration> allowedToDeclare,
                            final Iterable<File> classesDirs, final boolean logDependencyInformationToFile) {
    try {
      this.artifactClassCache =
          project.rootProject.extensions.getByName(CACHE_NAME) as ConcurrentHashMap<File, Set<String>>
    }
    catch (UnknownDomainObjectException e) {
      throw new IllegalStateException('Dependency analysis plugin must also be applied to the root project', e)
    }
    this.logger = project.logger
    this.require = removeNulls(require) as List
    this.allowedToUse = removeNulls(allowedToUse) as List
    this.allowedToDeclare = removeNulls(allowedToDeclare) as List
    this.classesDirs = classesDirs
    this.logDependencyInformationToFile = logDependencyInformationToFile
    this.buildDirPath = project.buildDir.toPath()
  }

  static <T> Collection<T> removeNulls(final Collection<T> collection) {
    if (null == collection) {
      []
    }
    else {
      collection.removeAll {it == null}
      collection
    }
  }

  ProjectDependencyAnalysis analyzeDependencies() {
    Set<ResolvedDependency> allowedToUseDeps = allowedToUseDependencies
    Set<ResolvedDependency> allowedToDeclareDeps = allowedToDeclareDependencies
    Set<ResolvedDependency> requiredDeps = requiredDependencies - allowedToUseDeps
    Set<File> dependencyArtifacts = findModuleArtifactFiles(requiredDeps)


    Set<File> allDependencyArtifacts = findAllModuleArtifactFiles(requiredDeps)
    Map<File, Set<String>> fileClassMap = buildArtifactClassMap(allDependencyArtifacts)
    Set<String> dependencyClasses = analyzeClassDependencies()
    Set<File> usedArtifacts = buildUsedArtifacts(fileClassMap, dependencyClasses)
    Set<File> usedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    usedDeclaredArtifacts.retainAll(usedArtifacts)

    Set<File> usedUndeclaredArtifacts = new LinkedHashSet<File>(usedArtifacts)
    usedUndeclaredArtifacts.removeAll(dependencyArtifacts)

    Set<File> unusedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    unusedDeclaredArtifacts.removeAll(usedArtifacts)

    Set<ResolvedArtifact> allowedToUseArtifacts = allowedToUseDeps*.moduleArtifacts?.flatten() as Set<ResolvedArtifact>
    Set<ResolvedArtifact> allowedToDeclareArtifacts = allowedToDeclareDeps*.moduleArtifacts?.
        flatten() as Set<ResolvedArtifact>

    Set<ResolvedArtifact> allArtifacts = (((require
        .collect {it.resolvedConfiguration}
        .collect {it.firstLevelModuleDependencies}.flatten()) as Set<ResolvedDependency>)
        .collect {it.allModuleArtifacts}.flatten()) as Set<ResolvedArtifact>

    if (logDependencyInformationToFile) {
      final def outputDirectoryPath = buildDirPath.resolve(AnalyzeDependenciesTask.DEPENDENCY_ANALYZE_DEPENDENCY_DIRECTORY_NAME)
      Files.createDirectories(outputDirectoryPath)
      final Path analyzeOutputPath = outputDirectoryPath.resolve("analyzeDependencies.log")
        new PrintWriter(Files.newOutputStream(analyzeOutputPath)).withCloseable { final analyzeWriter ->
          analyzeWriter.println('dependencyArtifacts:')
          dependencyArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
          analyzeWriter.println()

          analyzeWriter.println("allDependencyArtifacts:")
          allDependencyArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
          analyzeWriter.println()

          analyzeWriter.println("fileClassMap:")
          for (final def classMapEntry : fileClassMap) {
            analyzeWriter.print("${classMapEntry.key}=")
            for (final def theClass : classMapEntry.value) {
              analyzeWriter.print(theClass)
              analyzeWriter.print(', ')
            }
              analyzeWriter.println()
          }
          analyzeWriter.println()

          analyzeWriter.println("dependencyClasses:")
          dependencyClasses.forEach({ final dependencyClass -> analyzeWriter.println(dependencyClass) })
          analyzeWriter.println()

          analyzeWriter.println("usedArtifacts:")
          usedArtifacts.forEach({ final usedArtifact -> analyzeWriter.println(usedArtifact) })
          analyzeWriter.println()

          analyzeWriter.println("usedDeclaredArtifacts:")
          usedDeclaredArtifacts.forEach({ final usedDeclaredArtifact -> analyzeWriter.println(usedDeclaredArtifact) })
          analyzeWriter.println()

          analyzeWriter.println("usedUndeclaredArtifacts:")
          usedUndeclaredArtifacts.forEach({ final usedUndeclared -> analyzeWriter.println(usedUndeclared) })
          analyzeWriter.println()

          analyzeWriter.println("unusedDeclaredArtifacts:")
          unusedDeclaredArtifacts.forEach({ final unusedDeclared -> analyzeWriter.println(unusedDeclared) })
          analyzeWriter.println()

          analyzeWriter.println("allowedToUseArtifacts:")
          allowedToUseArtifacts.forEach({ final allowedToUse -> analyzeWriter.println(allowedToUse) })
          analyzeWriter.println()

          analyzeWriter.println("allowedToDeclareArtifacts:")
          allowedToDeclareArtifacts.forEach({ final allowedToDeclare -> analyzeWriter.println(allowedToDeclare) })
          analyzeWriter.println()

          analyzeWriter.println("allArtifacts:")
          allArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
          analyzeWriter.println()
        }
    } else {
      logger.info "dependencyArtifacts = $dependencyArtifacts"
      logger.info "allDependencyArtifacts = $allDependencyArtifacts"
      logger.info "fileClassMap = $fileClassMap"
      logger.info "dependencyClasses = $dependencyClasses"
      logger.info "usedArtifacts = $usedArtifacts"
      logger.info "usedDeclaredArtifacts = $usedDeclaredArtifacts"
      logger.info "usedUndeclaredArtifacts = $usedUndeclaredArtifacts"
      logger.info "unusedDeclaredArtifacts = $unusedDeclaredArtifacts"
      logger.info "allowedToUseArtifacts = $allowedToUseArtifacts"
      logger.info "allowedToDeclareArtifacts = $allowedToDeclareArtifacts"
      logger.info "allArtifacts = $allArtifacts"
    }

    def usedDeclared = allArtifacts.findAll {ResolvedArtifact artifact -> artifact.file in usedDeclaredArtifacts}
    def usedUndeclared = allArtifacts.findAll {ResolvedArtifact artifact -> artifact.file in usedUndeclaredArtifacts}
    if (allowedToUseArtifacts) {
      usedUndeclared -= allowedToUseArtifacts
    }
    def unusedDeclared = allArtifacts.findAll {ResolvedArtifact artifact -> artifact.file in unusedDeclaredArtifacts}
    if (allowedToDeclareArtifacts) {
      unusedDeclared -= allowedToDeclareArtifacts
    }

    return new ProjectDependencyAnalysis(
        usedDeclared.unique {it.file} as Set<Artifact>,
        usedUndeclared.unique {it.file} as Set<Artifact>,
        unusedDeclared.unique {it.file} as Set<Artifact>)
  }

  private Set<ResolvedDependency> getRequiredDependencies() {
    getFirstLevelDependencies(require)
  }

  private Set<ResolvedDependency> getAllowedToUseDependencies() {
    getFirstLevelDependencies(allowedToUse)
  }

  private Set<ResolvedDependency> getAllowedToDeclareDependencies() {
    getFirstLevelDependencies(allowedToDeclare)
  }

  static Set<ResolvedDependency> getFirstLevelDependencies(final List<Configuration> configurations) {
    configurations.collect {it.resolvedConfiguration.firstLevelModuleDependencies}.flatten() as Set<ResolvedDependency>
  }

  /**
   * Map each of the files declared on all configurations of the project to a collection of the class names they
   * contain.
   * @param project the project we're working on
   * @return a Map of files to their classes
   * @throws IOException
   */
  private Map<File, Set<String>> buildArtifactClassMap(Set<File> dependencyArtifacts) throws IOException {
    final Map<File, Set<String>> artifactClassMap = [:]

    int hits = 0
    int misses = 0
    dependencyArtifacts.each {File file ->
      def classes = artifactClassCache[file]
      if (null == classes) {
        logger.debug "Artifact class cache miss for $file"
        misses++
        classes = classAnalyzer.analyze(file.toURI().toURL()).asImmutable()
        artifactClassCache.putIfAbsent(file, classes)
      }
      else {
        logger.debug "Artifact class cache hit for $file"
        hits++
      }
      artifactClassMap.put(file, classes)
    }
    logger.info "Built artifact class map with $hits hits and $misses misses; cache size is ${artifactClassCache.size()}"
    return artifactClassMap
  }

  private Set<File> findModuleArtifactFiles(Set<ResolvedDependency> dependencies) {
    ((dependencies
        .collect {it.moduleArtifacts}.flatten()) as Set<ResolvedArtifact>)
        .collect {it.file}.unique() as Set<File>
  }

  private Set<File> findAllModuleArtifactFiles(Set<ResolvedDependency> dependencies) {
    ((dependencies
        .collect {it.allModuleArtifacts}.flatten()) as Set<ResolvedArtifact>)
        .collect {it.file}.unique() as Set<File>
  }

  /**
   * Find and analyze all class files to determine which external classes are used.
   * @param project
   * @return a Set of class names
   */
  private Set<String> analyzeClassDependencies() {
    classesDirs.collect {File it -> dependencyAnalyzer.analyze(it.toURI().toURL())}
        .flatten() as Set<String>
  }

  /**
   * Determine which of the project dependencies are used.
   *
   * @param artifactClassMap a map of Files to the classes they contain
   * @param dependencyClasses all classes used directly by the project
   * @return a set of project dependencies confirmed to be used by the project
   */
  private Set<File> buildUsedArtifacts(Map<File, Set<String>> artifactClassMap, Set<String> dependencyClasses) {
    Set<File> usedArtifacts = new HashSet()

    dependencyClasses.each {String className ->
      File artifact = artifactClassMap.find {it.value.contains(className)}?.key
      if (artifact) {
        usedArtifacts << artifact
      }
    }
    return usedArtifacts
  }
}
