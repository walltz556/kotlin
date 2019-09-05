/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.allopen.ide

import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.useInstance
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.extensions.CodegenApplicabilityCheckerExtension
import org.jetbrains.kotlin.extensions.DeclarationAttributeAltererExtension
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.extensions.isNoInlineKtClassWithSomeAnnotations
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

class IdeAllOpenDeclarationAttributeAltererExtensionContributor : StorageComponentContainerContributor {
    override fun registerModuleComponents(
        container: StorageComponentContainer,
        platform: TargetPlatform,
        moduleDescriptor: ModuleDescriptor
    ) {
        if (!platform.isJvm()) return

        container.useInstance(object : CodegenApplicabilityCheckerExtension {
            override fun syntheticPartsCouldBeGenerated(declaration: KtDeclaration, descriptor: Lazy<DeclarationDescriptor?>): Boolean {
                if (!declaration.isNoInlineKtClassWithSomeAnnotations()) return false

                val extension = DeclarationAttributeAltererExtension
                    .getInstances(declaration.project)
                    .firstIsInstanceOrNull<IdeAllOpenDeclarationAttributeAltererExtension>()
                    ?: return false

                val classDescriptor = descriptor.value?.let {
                    it as? ClassDescriptor
                        ?: it.containingDeclaration as? ClassDescriptor
                } ?: return false

                return extension.run { classDescriptor.hasSpecialAnnotation(declaration) }
            }
        })

    }
}