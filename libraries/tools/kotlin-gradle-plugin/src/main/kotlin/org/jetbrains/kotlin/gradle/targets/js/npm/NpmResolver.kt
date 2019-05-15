/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm

import com.google.gson.GsonBuilder
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJs
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmResolver.ResolutionCallResult.*

/**
 * Generates `package.json` file for projects with npm or js dependencies and
 * runs selected [NodeJsRootExtension.packageManager] to download and install all of it's dependencies.
 *
 * All [NpmDependency] for configurations related to kotlin/js will be added to `package.json`.
 * For external gradle modules, fake npm packages will be created and added to `package.json`
 * as path to directory.
 */
internal class NpmResolver private constructor(val rootProject: Project) : AutoCloseable {
    companion object {
        fun resolve(project: Project): ResolutionCallResult {
            val rootProject = project.rootProject
            val process = ProjectData[rootProject]

            if (process != null && process.resolved == null) return AlreadyInProgress

            val resolved = process?.resolved

            return if (resolved != null) AlreadyResolved(ProjectData[project]!!.resolved!!)
            else {
                val resolver = NpmResolver(rootProject)
                resolver.resolve(rootProject)!!
                resolver.close()
                ResolvedNow(ProjectData[project]!!.resolved!!)
            }
        }

        fun getAlreadyResolvedOrNull(project: Project): ResolutionCallResult? {
            val rootProject = project.rootProject
            val process = ProjectData[rootProject]
            if (process != null && process.resolved == null) return AlreadyInProgress
            val resolved = process?.resolved
            if (resolved != null) return AlreadyResolved(ProjectData[project]!!.resolved!!)

            return null
        }

        fun requireResolved(project: Project, reason: String = ""): ResolvedProject =
            ProjectData[project.rootProject]?.resolved
                ?: error("NPM dependencies should be resolved$reason")

        fun checkRequiredDependencies(project: Project, target: RequiresNpmDependencies) {
            val required = requireResolved(project, "before $target execution").taskDependencies
            val targetRequired = required[target]?.toSet() ?: setOf()

            target.requiredNpmDependencies.forEach {
                check(it in targetRequired) {
                    "$it required by $target after npm dependencies was resolved. " +
                            "This may be caused by changing $target configuration after npm dependencies resolution."
                }
            }
        }
    }

    sealed class ResolutionCallResult {
        object AlreadyInProgress : ResolutionCallResult()
        class AlreadyResolved(val resolution: ResolvedProject) : ResolutionCallResult()
        class ResolvedNow(val resolution: ResolvedProject) : ResolutionCallResult()
    }

    private val nodeJs = NodeJsPlugin.apply(rootProject).root
    private val gradleNodeModules = GradleNodeModulesCache(rootProject)
    private val packageManager = nodeJs.packageManager
    private val npmPackages = mutableListOf<NpmPackage>()
    private val byCompilation = mutableMapOf<KotlinJsCompilation, NpmPackage>()
    private val byNpmDependency = mutableMapOf<NpmDependency, NpmPackage>()
    private val requiredByTasks = mutableMapOf<RequiresNpmDependencies, Collection<RequiredKotlinJsDependency>>()
    private val requiredFromTasksByCompilation = mutableMapOf<KotlinJsCompilation, MutableList<Dependency>>()
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    class ProjectData(var resolved: ResolvedProject? = null) {
        companion object {
            private const val KEY = "npmResolverData"
            operator fun get(project: Project) = project.extensions.findByName(KEY) as ProjectData?
            operator fun set(project: Project, value: ProjectData) = project.extensions.add(KEY, value)
        }
    }

    class ResolvedProject(
        val byCompilation: Map<KotlinJsCompilation, NpmPackage>,
        val byNpmDependency: Map<NpmDependency, NpmPackage>,
        val taskDependencies: Map<RequiresNpmDependencies, Collection<RequiredKotlinJsDependency>>
    )

    class NpmPackage(
        val project: Project,
        val npmProject: NpmProject,
        val npmDependencies: Collection<NpmDependency>,
        val packageJson: PackageJson
    )

    private var packageManagerInstalled = false

    private fun requirePackageManagerSetup() {
        if (packageManagerInstalled) return
        packageManager.setup(rootProject)
        packageManagerInstalled = true
    }

    private fun getOrResolve(project: Project): ResolvedProject {
        return (ProjectData[project] ?: resolve(project)!!).resolved!!
    }

    private fun resolve(project: Project): ProjectData? {
        val result = ProjectData().also {
            ProjectData[project] = it
        }

        project.subprojects.forEach {
            getOrResolve(it)
        }

        visitTasksRequiredDependencies(project)
        visitProject(project)

        // todo: byNpmDependency, requiredByTasks should for this project only
        result.resolved = ResolvedProject(byCompilation, byNpmDependency, requiredByTasks)

        if (project == rootProject) {
            if (npmPackages.isNotEmpty()) {
                requirePackageManagerSetup()
                packageManager.resolveRootProject(rootProject, npmPackages)
            }
        }

        return result
    }

    private fun visitTasksRequiredDependencies(
        project: Project
    ) {
        project.tasks.toList().forEach { task ->
            if (task.enabled && task is RequiresNpmDependencies) {
                val list = task.requiredNpmDependencies.toList()

                requiredByTasks[task] = list
                val requiredDependenciesList = requiredFromTasksByCompilation.getOrPut(task.compilation) { mutableListOf() }
                list.forEach { requiredDependency ->
                    requiredDependenciesList.add(requiredDependency.createDependency(project))
                }
            }
        }
    }

    private fun visitProject(project: Project) {
        val kotlin = project.kotlinExtensionOrNull

        if (kotlin != null) {
            when (kotlin) {
                is KotlinSingleTargetExtension -> visitTarget(kotlin.target)
                is KotlinMultiplatformExtension -> kotlin.targets.forEach {
                    visitTarget(it)
                }
            }
        }
    }

    private fun visitTarget(target: KotlinTarget) {
        if (target.platformType == KotlinPlatformType.js) {
            target.compilations.toList().forEach { compilation ->
                if (compilation is KotlinJsCompilation) {
                    visitCompilation(compilation)
                }
            }
        }
    }

    private fun visitCompilation(compilation: KotlinJsCompilation): NpmPackage {
        val project = compilation.target.project
        val npmProject = compilation.npmProject
        val kotlin2JsCompile = compilation.compileKotlinTask
        val name = npmProject.name
        val packageJson = PackageJson(
            name,
            project.version.toString()
        )
        val npmDependencies = mutableSetOf<NpmDependency>()
        val gradleDeps = NpmProjectGradleDeps()

        compilation.kotlinSourceSets.forEach {
            it.relatedConfigurationNames.forEach {
                val configuration = project.configurations.getByName(it)
                visitConfiguration(configuration, npmDependencies, gradleDeps)
            }
        }

        val main = "kotlin/$name.js"
        kotlin2JsCompile.kotlinOptions.outputFile = npmProject.dir.resolve(main).canonicalPath
        packageJson.main = main

        val requiredByTasks = requiredFromTasksByCompilation[compilation]
        if (requiredByTasks != null && requiredByTasks.isNotEmpty()) {
            val configuration = project.configurations.create("$name-jsTools")
            requiredByTasks.forEach {
                configuration.dependencies.add(it)
            }
            configuration.resolve()
            visitConfiguration(configuration, npmDependencies, gradleDeps)
        }

        npmDependencies.forEach {
            packageJson.dependencies[it.key] = chooseVersion(packageJson.dependencies[it.key], it.version)
        }

        gradleDeps.externalModules.forEach {
            val relativePath = it.path.relativeTo(npmProject.dir)
            packageJson.dependencies[it.name] = "file:$relativePath"
        }

        gradleDeps.internalModules.forEach {
            val target = getOrResolve(it)

            val mainCompilations = target.byCompilation.entries.filter { it.key.name == KotlinCompilation.MAIN_COMPILATION_NAME }
            if (mainCompilations.isNotEmpty()) {
                if (mainCompilations.size > 1) {
                    error(
                        "Cannot resolve project dependency $project -> $it." +
                                "Dependency to project with multiple js compilation not supported yet."
                    )
                }
                val npmPackage = mainCompilations.single().value
                packageJson.dependencies[npmPackage.packageJson.name] = npmPackage.packageJson.version
            }
        }

        project.nodeJs.packageJsonHandlers.forEach {
            it(packageJson)
        }

        val npmPackage = NpmPackage(project, npmProject, npmDependencies, packageJson)
        npmPackage.packageJson.saveTo(npmProject.packageJsonFile, gson)

        requirePackageManagerSetup()
        packageManager.resolveProject(npmPackage)

        npmPackages.add(npmPackage)

        byCompilation[compilation] = npmPackage

        npmDependencies.forEach {
            byNpmDependency[it] = npmPackage
        }

        return npmPackage
    }

    private fun findDependentProjectCompilation(it: Project): KotlinJsCompilation? {
        val kotlin = it.kotlinExtensionOrNull ?: return null

        val jsTargets = (when (kotlin) {
            is KotlinSingleTargetExtension -> listOf(kotlin.target)
            is KotlinMultiplatformExtension -> kotlin.targets.toList()
            else -> error("Unsupported kotlin extension: $kotlin")
        }).filterIsInstance<KotlinJsTarget>()

        if (jsTargets.isEmpty()) return null
        if (jsTargets.size > 1) error("Cannot choose among JS targets $jsTargets")

        return jsTargets.single().compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)
    }

    private fun chooseVersion(oldVersion: String?, newVersion: String): String =
        oldVersion ?: newVersion // todo: real versions conflict resolution

    private fun visitConfiguration(
        configuration: Configuration,
        npmDependencies: MutableSet<NpmDependency>,
        gradleDeps: NpmProjectGradleDeps
    ) {
        gradleNodeModules.collectDependenciesFromConfiguration(configuration, gradleDeps)

        configuration.allDependencies.forEach { dependency ->
            when (dependency) {
                is NpmDependency -> npmDependencies.add(dependency)
            }
        }
    }

    override fun close() {
        gradleNodeModules.close()
        packageManager.hookRootPackage(rootProject, npmPackages, gson)
    }
}