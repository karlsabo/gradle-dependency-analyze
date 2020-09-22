package ca.cutterslade.gradle.analyze

import groovy.transform.CompileStatic
import org.apache.maven.shared.dependency.analyzer.ClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.logging.Logger

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.stream.Collectors

@CompileStatic
class ProjectDependencyResolver {
  static final String CACHE_NAME = 'ca.cutterslade.gradle.analyze.ProjectDependencyResolver.qualifiedClassNameByArtifactIdentifier'

  private final ClassAnalyzer classAnalyzer = new DefaultClassAnalyzer()
  private final DependencyAnalyzer dependencyAnalyzer = new ASMDependencyAnalyzer()

  private final ConcurrentHashMap<ComponentArtifactIdentifier, Set<String>> qualifiedClassNameByArtifactIdentifier
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
      this.qualifiedClassNameByArtifactIdentifier =
              project.rootProject.extensions.getByName(CACHE_NAME) as ConcurrentHashMap<ComponentArtifactIdentifier, Set<String>>
    } catch (final UnknownDomainObjectException exception) {
      throw new IllegalStateException('Dependency analysis plugin must also be applied to the root project', exception)
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
    } else {
      collection.removeAll { it == null }
      collection
    }
  }

  /**
   * Find undeclared and unused dependencies
   *
   * Build a set of classes used by this Gradle Module (project)
   * <br><br>
   * Build set of declared dependencies (Set<ModuleVersionIdentifier>)<br>
   *  requiredDependencies -> configuration.resolvedConfiguration.firstLevelModuleDependencies
   * <br><br>
   * Build a map of classes Modules, scan all dependencies and their children (Map<String, Set<ModuleVersionIdentifier>>)
   * <br><br>
   * Build a set of needed dependencies, scan all classes and map to ModuleVersionIdentifier (Set<ModuleVersionIdentifier>)
   * <br><br>
   * Unused declared - Take the set of declared dependencies and remove needed dependencies. (Set<ModuleVersionIdentifier>)<br>
   *  Remove permitUnusedDeclared (allowedToDeclare)<br>
   *  Remove permitUsedUndeclared (allowedToUse)
   * <br><br>
   * Used undeclared - Take the set of needed dependencies and remove declared dependencies. (Set<ModuleVersionIdentifier>)<br>
   *    Remove permitUsedUndeclared (allowedToUse)
   */
  GradleProjectDependencyAnalysis analyzeDependencies(final String taskName) {
    final Set<String> classesUsedByClassesDir = analyzeClassDependencies()
    final Set<ModuleVersionIdentifier> declaredDependencies = getRequiredDependencies().collect { it.module.id } as Set<ModuleVersionIdentifier>
    final Map<String, Set<ModuleVersionIdentifier>> moduleByQualifiedClassName = getAllModulesByQualifiedClassName()
    final Set<ModuleVersionIdentifier> neededDependencies = getNeededModules(classesUsedByClassesDir, moduleByQualifiedClassName)
    final def allowedToDeclaredModules = allowedToDeclareDependencies.collect { it.module.id } as Set<ModuleVersionIdentifier>
    final def allowedToUseModules = allowedToUseDependencies.collect { it.module.id } as Set<ModuleVersionIdentifier>

    final Set<ModuleVersionIdentifier> unusedDeclaredDependencies = new LinkedHashSet<>(declaredDependencies)
    unusedDeclaredDependencies.removeAll(neededDependencies)
    unusedDeclaredDependencies.removeAll(allowedToDeclaredModules)
    unusedDeclaredDependencies.removeAll(allowedToUseModules)

    final Set<ModuleVersionIdentifier> usedUndeclaredDependencies = new LinkedHashSet<>(neededDependencies)
    usedUndeclaredDependencies.removeAll(declaredDependencies)
    usedUndeclaredDependencies.removeAll(allowedToUseModules)

    if (logDependencyInformationToFile) {
      final def outputDirectoryPath = buildDirPath.resolve(AnalyzeDependenciesTask.DEPENDENCY_ANALYZE_DEPENDENCY_DIRECTORY_NAME)
      Files.createDirectories(outputDirectoryPath)
      final Path analyzeOutputPath = outputDirectoryPath.resolve("${taskName}.log")
      new PrintWriter(Files.newOutputStream(analyzeOutputPath)).withCloseable { final analyzeWriter ->
        analyzeWriter.println("classesUsedByClassesDir:")
        classesUsedByClassesDir.each { analyzeWriter.println("'" + it + "'") }
        analyzeWriter.println()

        analyzeWriter.println("declaredDependencies:")
        declaredDependencies.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("moduleByQualifiedClassName:")
        moduleByQualifiedClassName.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("allowedToDeclaredModules:")
        allowedToDeclaredModules.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("allowedToUseModules:")
        allowedToUseModules.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("neededDependencies:")
        neededDependencies.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("unusedDeclaredDependencies:")
        unusedDeclaredDependencies.each { analyzeWriter.println(it) }
        analyzeWriter.println()

        analyzeWriter.println("usedUndeclaredDependencies:")
        usedUndeclaredDependencies.each { analyzeWriter.println(it) }
        analyzeWriter.println()
      }
    } else {
      logger.info "classesUsedByClassesDir = $classesUsedByClassesDir"
      logger.info "declaredDependencies = $declaredDependencies"
      logger.info "moduleByQualifiedClassName = $moduleByQualifiedClassName"
      logger.info("allowedToDeclaredModules = $allowedToDeclaredModules")
      logger.info("allowedToUseModules = $allowedToUseModules")
      logger.info "neededDependencies = $neededDependencies"
      logger.info "unusedDeclaredDependencies = $unusedDeclaredDependencies"
      logger.info "usedUndeclaredDependencies = $usedUndeclaredDependencies"
    }

    return new GradleProjectDependencyAnalysis(usedUndeclaredDependencies, unusedDeclaredDependencies)
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
    configurations.collect { it.resolvedConfiguration.firstLevelModuleDependencies }.flatten() as Set<ResolvedDependency>
  }

  /**
   * Map each of the artifacts in {@code resolvedArtifacts} to a collection of the class names they
   * contain.
   * @param resolvedArtifacts the artifacts to get all classes of
   * @return a Map of artifacts to their classes
   * @throws IOException if an IO error occurs
   */
  private Set<String> getQualifiedClassNamesByArtifact(final ResolvedArtifact resolvedArtifact) throws IOException {
    int hits = 0
    int misses = 0
    Set<String> classes = qualifiedClassNameByArtifactIdentifier[resolvedArtifact.id]
    if (null == classes) {
      logger.debug "qualifiedClassNameByArtifactCache miss for $resolvedArtifact"
      misses++
      classes = classAnalyzer.analyze(resolvedArtifact.file.toURI().toURL()).asImmutable()
      qualifiedClassNameByArtifactIdentifier.putIfAbsent(resolvedArtifact.id, classes)
    } else {
      logger.debug "qualifiedClassNameByArtifactCache hit for $resolvedArtifact"
      hits++
    }
    logger.debug "Built qualifiedClassNameByArtifactCache with $hits hits and $misses misses; cache size is ${qualifiedClassNameByArtifactIdentifier.size()}"
    return classes
  }

  /**
   * Find and analyze all class files to determine which external classes are used.
   * @param project
   * @return a Set of class names
   */
  private Set<String> analyzeClassDependencies() {
    final Pattern WHITE_SPACE_PATTERN = Pattern.compile("\\s")
    final Set<String> dependencyAnalyzer = classesDirs.collect{final File it-> dependencyAnalyzer.analyze(it.toURI().toURL())}.flatten() as Set<String>
    dependencyAnalyzer.stream().filter({!it.isEmpty() && !it.isAllWhitespace() && !WHITE_SPACE_PATTERN.matcher(it).find()}).collect(Collectors.toSet())
  }

  /**
   * Loads all known dependencies and their children from {@link #getRequiredDependencies()},
   * {@link #getAllowedToDeclareDependencies()}, and {@link #getAllowedToUseDependencies()} and builds a map from every
   * qualified classname found to the set of {@link ModuleVersionIdentifier} that have an artifact with that class.
   *
   * @return a map from a fully qualified classname to a set of modules which have that class
   */
  private Map<String, Set<ModuleVersionIdentifier>> getAllModulesByQualifiedClassName() {
    final Map<String, Set<ModuleVersionIdentifier>> moduleVersionsByQualifiedClassname = new HashMap<>()

    getRequiredDependencies().each {
      addModuleByQualifiedClassname(moduleVersionsByQualifiedClassname, it)
    }
    getAllowedToDeclareDependencies().each {
      addModuleByQualifiedClassname(moduleVersionsByQualifiedClassname, it)
    }
    getAllowedToUseDependencies().each {
      addModuleByQualifiedClassname(moduleVersionsByQualifiedClassname, it)
    }

    return moduleVersionsByQualifiedClassname
  }

  /**
   * Scans all artifacts in {@code resolvedDependency} and their children, adding to the map {@code moduleVersionsByQualifiedClassname}
   * for every class found in the resolvedDependency artifacts.
   * <br>
   * This method avoids dependency loops by keeping track of dependencies it has already acquired children for.
   *
   * @param moduleVersionsByQualifiedClassname
   * @param resolvedDependency
   */
  private void addModuleByQualifiedClassname(final Map<String, Set<ModuleVersionIdentifier>> moduleVersionsByQualifiedClassname, final ResolvedDependency resolvedDependency) {
    final Set<ResolvedDependency> dependenciesToScan = new LinkedHashSet<ResolvedDependency>()

    final Stack<ResolvedDependency> dependenciesToLoadChildrenFor = new Stack<ResolvedDependency>()
    dependenciesToLoadChildrenFor.push(resolvedDependency)

    while (dependenciesToLoadChildrenFor.size() > 0) {
      def currentDependency = dependenciesToLoadChildrenFor.pop()
      if (!dependenciesToScan.contains(currentDependency)) {
        dependenciesToScan.add(currentDependency)
        currentDependency.children.each { final ResolvedDependency childDependency ->
          dependenciesToLoadChildrenFor.push(childDependency)
        }
      }
    }

    dependenciesToScan.each { final resolvedDependencyToScan ->
      resolvedDependencyToScan.moduleArtifacts.each { final ResolvedArtifact resolvedArtifact ->
        getQualifiedClassNamesByArtifact(resolvedArtifact).each { final String qualifiedClassname ->
          moduleVersionsByQualifiedClassname.compute(qualifiedClassname, { final String key, Set<ModuleVersionIdentifier> value ->
            if (value == null) {
              value = new HashSet<ModuleVersionIdentifier>()
            }
            value.add(resolvedDependencyToScan.module.id)
            return value
          })
        }
      }
    }
  }

  /**
   * Scans every class in {@code neededQualifiedClassnames} looking for an entry in {@code allModulesByQualifiedClassname}
   * as entries are found they are added to the set that will be returned.
   *
   * @param neededQualifiedClassnames the classnames to look for in {@code allModulesByQualifiedClassname}
   * @param allModulesByQualifiedClassname every dependency classname and it's module version information
   * @return a set of all known ModuleVersionIdentifier (dependencies) for the passed in classes {@code neededQualifiedClassnames}
   */
  private Set<ModuleVersionIdentifier> getNeededModules(final Set<String> neededQualifiedClassnames, final Map<String, Set<ModuleVersionIdentifier>> allModulesByQualifiedClassname) {
    final Set<ModuleVersionIdentifier> neededModuleSet = new LinkedHashSet<>()

    neededQualifiedClassnames.each { final String neededClass ->
      final def moduleVersionIdentifiers = allModulesByQualifiedClassname.get(neededClass)
      if (moduleVersionIdentifiers == null) {
        return
      }
      if (moduleVersionIdentifiers.size() > 1) {
        logger.warn("More than one dependency ($moduleVersionIdentifiers) includes the class $neededClass")
      }
      neededModuleSet.addAll(moduleVersionIdentifiers)
    }

    return neededModuleSet
  }
}
