/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.extensions

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration

interface CodegenApplicabilityCheckerExtension {
    fun syntheticPartsCouldBeGenerated(declaration: KtDeclaration, descriptor: Lazy<DeclarationDescriptor?>): Boolean
}

fun KtDeclaration.isNoInlineKtClassWithSomeAnnotations(): Boolean {
    if (this !is KtClass) return false
    if (this.hasModifier(KtTokens.INLINE_KEYWORD)) return false
    if (this.isAnnotation() || this.isInterface()) return false
    if (this.annotationEntries.isEmpty()) return false

    return true
}