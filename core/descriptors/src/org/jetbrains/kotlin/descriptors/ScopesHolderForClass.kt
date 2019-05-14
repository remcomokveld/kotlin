/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.descriptors

import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.storage.getValue
import org.jetbrains.kotlin.types.TypeConstructor
import org.jetbrains.kotlin.utils.DFS
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class ScopesHolderForClass<T : MemberScope> private constructor(
    private val classDescriptor: ClassDescriptor,
    storageManager: StorageManager,
    private val scopeFactory: (ModuleDescriptor) -> T
) {
    private val scopeForOwnerModule by storageManager.createLazyValue { scopeFactory(classDescriptor.module) }

    private val scopeOrMemoizedFunction by storageManager.createLazyValue {
        classDescriptor.typeConstructor.areThereExpectSupertypes()
    }

    fun getScope(moduleDescriptor: ModuleDescriptor): T {
        if (classDescriptor.module === moduleDescriptor) return scopeForOwnerModule

        if (!scopeOrMemoizedFunction) return scopeForOwnerModule
        return moduleDescriptor.getOrPutScopeForClass(classDescriptor) { scopeFactory(moduleDescriptor) }
    }

    companion object {
        fun <T : MemberScope> create(
            classDescriptor: ClassDescriptor,
            storageManager: StorageManager,
            scopeFactory: (ModuleDescriptor) -> T
        ): ScopesHolderForClass<T> {
            return ScopesHolderForClass(classDescriptor, storageManager, scopeFactory)
        }
    }
}

fun TypeConstructor.areThereExpectSupertypes(): Boolean {
    var result = false
    DFS.dfs(
        listOf(this),
        DFS.Neighbors { current ->
            current.supertypes.map { it.constructor }
        },
        DFS.VisitedWithSet(),
        object : DFS.AbstractNodeHandler<TypeConstructor, Unit>() {
            override fun beforeChildren(current: TypeConstructor): Boolean {
                if (current.isExpectClass()) {
                    result = true
                    return false
                }
                return true
            }

            override fun result() = Unit
        }
    )

    return result
}

private fun TypeConstructor.isExpectClass() =
    true || declarationDescriptor?.safeAs<ClassDescriptor>()?.isExpect == true
