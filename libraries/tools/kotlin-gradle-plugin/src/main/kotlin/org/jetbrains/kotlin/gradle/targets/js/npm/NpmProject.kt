/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm

import org.gradle.api.Project
import org.gradle.process.ExecSpec
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJs
import java.io.File

open class NpmProject(
    val compilation: KotlinJsCompilation,
    val name: String,
    val dir: File
) {
    val project: Project
        get() = compilation.target.project

    val nodeModulesDir
        get() = dir.resolve(NODE_MODULES)

    val packageJsonFile: File
        get() = dir.resolve(PACKAGE_JSON)

    open val compileOutputCopyDest: File
        get() = nodeModulesDir

    private val modules = object : NpmProjectModules(dir, nodeModulesDir) {
        override val parent get() = rootNodeModules
    }

    private val rootNodeModules: NpmProjectModules?
        get() = NpmProjectModules(project.nodeJs.root.nodeJsWorldDir)

    fun useTool(exec: ExecSpec, tool: String, vararg args: String) {
        exec.workingDir = dir
        exec.executable = project.nodeJs.root.environment.nodeExecutable
        exec.args = listOf(require(tool)) + args
    }

    /**
     * Require [request] nodejs module and return canonical path to it's main js file.
     */
    fun require(request: String): String {
        NpmResolver.requireResolved(project)
        return modules.require(request)
    }

    /**
     * Find node module according to https://nodejs.org/api/modules.html#modules_all_together,
     * with exception that instead of traversing parent folders, we are traversing parent projects
     */
    internal fun resolve(name: String): File? = modules.resolve(name)

    override fun toString() = "NpmProject($dir)"

    companion object {
        const val PACKAGE_JSON = "package.json"
        const val NODE_MODULES = "node_modules"
    }
}

val KotlinJsCompilation.npmProject: NpmProject
    get() {
        val project = target.project
        val nodeJsWorldDir = project.nodeJs.root.nodeJsWorldDir

        var name = project.path.removePrefix(":").replace(":", "-")
        if (target.name.isNotEmpty() && target.name.toLowerCase() != "js") name += "-" + target.name
        name += "-" + compilationName

        val dir = nodeJsWorldDir.resolve("packages").resolve(name)

        return NpmProject(this, name, dir)
    }