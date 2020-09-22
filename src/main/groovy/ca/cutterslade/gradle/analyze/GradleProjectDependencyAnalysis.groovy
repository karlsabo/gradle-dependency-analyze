package ca.cutterslade.gradle.analyze

import org.gradle.api.artifacts.ModuleVersionIdentifier

class GradleProjectDependencyAnalysis {
    final Set<ModuleVersionIdentifier> usedUndeclared
    final Set<ModuleVersionIdentifier> unusedDeclared

    GradleProjectDependencyAnalysis(final Set<ModuleVersionIdentifier> usedUndeclared, final Set<ModuleVersionIdentifier> unusedDeclared) {
        this.usedUndeclared = usedUndeclared
        this.unusedDeclared = unusedDeclared
    }

    Set<ModuleVersionIdentifier> getUsedUndeclared() {
        return new LinkedHashSet<ModuleVersionIdentifier>(usedUndeclared)
    }

    Set<ModuleVersionIdentifier> getUnusedDeclared() {
        return new LinkedHashSet<ModuleVersionIdentifier>(unusedDeclared)
    }
}
