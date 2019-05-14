/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.descriptors.WrappedFieldDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedVariableDescriptor
import org.jetbrains.kotlin.backend.common.lower.replaceThisByStaticReference
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.codegen.isJvmInterface
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrAnonymousInitializerImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.JvmAbi.JVM_FIELD_ANNOTATION_FQ_NAME

internal val moveCompanionObjectFieldsPhase = makeIrFilePhase(
    ::MoveCompanionObjectFieldsLowering,
    name = "MoveCompanionObjectFields",
    description = "Move companion object fields to static fields of companion's owner"
)

private class MoveCompanionObjectFieldsLowering(val context: CommonBackendContext) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        val fieldReplacementMap = mutableMapOf<IrFieldSymbol, IrFieldSymbol>()
        if (irClass.isObject && !irClass.isCompanion && irClass.visibility != Visibilities.LOCAL) {
            handleObject(irClass, fieldReplacementMap)
        } else {
            handleClass(irClass, fieldReplacementMap)
        }
        irClass.replaceFieldReferences(fieldReplacementMap)
    }

    private fun handleObject(irObject: IrClass, fieldReplacementMap: MutableMap<IrFieldSymbol, IrFieldSymbol>) {
        irObject.declarations.replaceAll {
            when (it) {
                is IrProperty -> {
                    // The field is not actually moved, just replaced by a static field
                    movePropertyFieldToStaticParent(it, irObject, irObject, fieldReplacementMap)
                    it
                }
                is IrAnonymousInitializer -> moveAnonymousInitializerToStaticParent(it, irObject, irObject)
                else -> it
            }
        }
    }

    private fun handleClass(irClass: IrClass, fieldReplacementMap: MutableMap<IrFieldSymbol, IrFieldSymbol>) {
        val companion = irClass.declarations.find {
            it is IrClass && it.isCompanion
        } as IrClass? ?: return
        companion.declarations.mapNotNullTo(irClass.declarations) {
            when {
                it is IrProperty && shouldMoveProperty(irClass, it) ->
                    movePropertyFieldToStaticParent(it, companion, irClass, fieldReplacementMap)
                it is IrAnonymousInitializer ->
                    moveAnonymousInitializerToStaticParent(it, companion, irClass)
                else -> null
            }
        }
        companion.declarations.removeAll { it is IrAnonymousInitializer }
    }

    // Properties in companions are always copied into their declaration parents except for interfaces, which can only host
    // public static final fields. Only constants and values annotated with @JvmField are copied into interfaces. See:
    // https://kotlinlang.org/docs/reference/java-to-kotlin-interop.html#static-fields
    //
    // Note that lateinit properties (mentioned in the documentation) are implicitly non-final and cannot be copied into a parent interface.
    private fun shouldMoveProperty(irClass: IrClass, irProperty: IrProperty) =
        !irClass.isJvmInterface || irProperty.isConst || irProperty.backingField?.hasAnnotation(JVM_FIELD_ANNOTATION_FQ_NAME) == true

    private fun movePropertyFieldToStaticParent(
        irProperty: IrProperty,
        propertyParent: IrClass,
        fieldParent: IrClass,
        fieldReplacementMap: MutableMap<IrFieldSymbol, IrFieldSymbol>
    ): IrField? {
        if (irProperty.origin == IrDeclarationOrigin.FAKE_OVERRIDE) return null
        val oldField = irProperty.backingField ?: return null
        val newField = createStaticBackingField(oldField, propertyParent, fieldParent)

        irProperty.backingField = newField
        newField.correspondingPropertySymbol = irProperty.symbol

        fieldReplacementMap[oldField.symbol] = newField.symbol

        return newField
    }

    private fun moveAnonymousInitializerToStaticParent(
        oldInitializer: IrAnonymousInitializer,
        oldParent: IrClass,
        newParent: IrClass
    ): IrAnonymousInitializer =
        with(oldInitializer) {
            IrAnonymousInitializerImpl(
                startOffset, endOffset, origin, IrAnonymousInitializerSymbolImpl(newParent.symbol),
                isStatic = true
            ).apply {
                parent = newParent
                body = oldInitializer.body.transferToNewParent(oldParent, newParent)
            }
        }

    private fun IrBlockBody.transferToNewParent(oldParent: IrClass, newParent: IrClass): IrBlockBody {
        val objectInstanceField = context.declarationFactory.getFieldForObjectInstance(oldParent)
        return transform(
            data = null,
            transformer = object : IrElementTransformerVoid() {
                val variableMap = mutableMapOf<IrVariable, IrVariable>()

                override fun visitVariable(declaration: IrVariable): IrStatement {
                    if (declaration.parent == oldParent) {
                        val newDescriptor = WrappedVariableDescriptor(declaration.descriptor.annotations, declaration.descriptor.source)
                        val newVariable = IrVariableImpl(
                            declaration.startOffset, declaration.endOffset,
                            declaration.origin, IrVariableSymbolImpl(newDescriptor),
                            declaration.name, declaration.type, declaration.isVar, declaration.isConst, declaration.isLateinit
                        ).apply {
                            newDescriptor.bind(this)
                            parent = newParent
                            initializer = declaration.initializer
                            annotations.addAll(declaration.annotations)
                        }
                        variableMap[declaration] = newVariable
                        return super.visitVariable(newVariable)
                    }
                    return super.visitVariable(declaration)
                }

                override fun visitGetValue(expression: IrGetValue): IrExpression {
                    if (expression.symbol.owner == oldParent.thisReceiver) {
                        return IrGetFieldImpl(
                            expression.startOffset, expression.endOffset,
                            objectInstanceField.symbol,
                            expression.type
                        )
                    }
                    variableMap[expression.symbol.owner]?.let { newVariable ->
                        return IrGetValueImpl(
                            expression.startOffset, expression.endOffset,
                            expression.type,
                            newVariable.symbol,
                            expression.origin
                        )
                    }
                    return super.visitGetValue(expression)
                }
            }) as IrBlockBody
    }

    private fun createStaticBackingField(oldField: IrField, propertyParent: IrClass, fieldParent: IrClass): IrField {
        val descriptor = WrappedFieldDescriptor(oldField.descriptor.annotations, oldField.descriptor.source)
        val field = IrFieldImpl(
            oldField.startOffset, oldField.endOffset,
            IrDeclarationOrigin.PROPERTY_BACKING_FIELD,
            IrFieldSymbolImpl(descriptor),
            oldField.name, oldField.type, oldField.visibility,
            isFinal = oldField.isFinal,
            isExternal = oldField.isExternal,
            isStatic = true
        ).apply {
            descriptor.bind(this)
            parent = fieldParent
            annotations.addAll(oldField.annotations)
            metadata = oldField.metadata
        }
        val oldInitializer = oldField.initializer
        if (oldInitializer != null) {
            field.initializer = oldInitializer
                .replaceThisByStaticReference(context, propertyParent, propertyParent.thisReceiver!!)
                .patchDeclarationParents(field) as IrExpressionBody
        }

        return field
    }
}

private fun IrElement.replaceFieldReferences(replacementMap: Map<IrFieldSymbol, IrFieldSymbol>) {
    transformChildrenVoid(FieldReplacer(replacementMap))
}

private class FieldReplacer(val replacementMap: Map<IrFieldSymbol, IrFieldSymbol>) : IrElementTransformerVoid() {
    override fun visitGetField(expression: IrGetField): IrExpression =
        replacementMap[expression.symbol]?.let { newSymbol ->
            IrGetFieldImpl(
                expression.startOffset, expression.endOffset,
                newSymbol,
                expression.type,
                /* receiver = */ null,
                expression.origin,
                expression.superQualifierSymbol
            )
        } ?: super.visitGetField(expression)

    override fun visitSetField(expression: IrSetField): IrExpression =
        replacementMap[expression.symbol]?.let { _ ->
            IrSetFieldImpl(
                expression.startOffset, expression.endOffset,
                replacementMap.getValue(expression.symbol),
                /* receiver = */ null,
                visitExpression(expression.value),
                expression.type,
                expression.origin,
                expression.superQualifierSymbol
            )
        } ?: super.visitSetField(expression)
}
