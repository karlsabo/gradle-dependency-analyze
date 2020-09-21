//==============================================================================
// This software is developed by Stellar Science Ltd Co and the U.S. Government.
// Copyright (C) 2020 Stellar Science; U.S. Government has Unlimited Rights.
// Warning: May contain EXPORT CONTROLLED, FOUO, ITAR, or sensitive information.
//==============================================================================
package ca.cutterslade.gradle.analyze

import org.gradle.api.artifacts.ModuleVersionIdentifier

class GradleProjectDependencyAnalysis {
    final Set<ModuleVersionIdentifier> usedDeclaredDependencies
    final Set<ModuleVersionIdentifier> usedUndeclared
    final Set<ModuleVersionIdentifier> unusedDeclared

    GradleProjectDependencyAnalysis(final Set<ModuleVersionIdentifier> usedDeclaredDependencies, final Set<ModuleVersionIdentifier> usedUndeclared, final Set<ModuleVersionIdentifier> unusedDeclared) {
        this.usedDeclaredDependencies = usedDeclaredDependencies
        this.usedUndeclared = usedUndeclared
        this.unusedDeclared = unusedDeclared
    }

    Set<ModuleVersionIdentifier> getUsedDeclaredDependencies() {
        return new LinkedHashSet<ModuleVersionIdentifier>(usedDeclaredDependencies)
    }

    Set<ModuleVersionIdentifier> getUsedUndeclared() {
        return new LinkedHashSet<ModuleVersionIdentifier>(usedUndeclared)
    }

    Set<ModuleVersionIdentifier> getUnusedDeclared() {
        return new LinkedHashSet<ModuleVersionIdentifier>(unusedDeclared)
    }
}
