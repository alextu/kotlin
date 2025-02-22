/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.components.KtDeclarationRendererOptions
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.SymbolTestDirectives.DO_NOT_CHECK_SYMBOL_RESTORE
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.SymbolTestDirectives.DO_NOT_CHECK_SYMBOL_RESTORE_K1
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.SymbolTestDirectives.DO_NOT_CHECK_SYMBOL_RESTORE_K2
import org.jetbrains.kotlin.analysis.api.impl.base.test.cases.symbols.SymbolTestDirectives.PRETTY_RENDERING_MODE
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.analysis.test.framework.base.AbstractAnalysisApiSingleFileTest
import org.jetbrains.kotlin.analysis.test.framework.test.configurators.FrontendKind
import org.jetbrains.kotlin.analysis.test.framework.utils.executeOnPooledThreadInReadAction
import org.jetbrains.kotlin.analysis.utils.printer.prettyPrint
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.Directive
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import kotlin.test.fail

abstract class AbstractSymbolTest : AbstractAnalysisApiSingleFileTest() {
    private val renderingOptions = KtDeclarationRendererOptions.DEFAULT

    open val prettyRenderMode: PrettyRenderingMode = PrettyRenderingMode.RENDER_SYMBOLS_LINE_BY_LINE

    override fun configureTest(builder: TestConfigurationBuilder) {
        super.configureTest(builder)
        with(builder) {
            useDirectives(SymbolTestDirectives)
        }
    }

    abstract fun KtAnalysisSession.collectSymbols(ktFile: KtFile, testServices: TestServices): SymbolsData

    override fun doTestByFileStructure(ktFile: KtFile, module: TestModule, testServices: TestServices) {
        val directives = module.directives
        val directiveToIgnore = DO_NOT_CHECK_SYMBOL_RESTORE.takeIf { it in directives }
            ?: DO_NOT_CHECK_SYMBOL_RESTORE_K1.takeIf { configurator.frontendKind == FrontendKind.Fe10 && it in directives }
            ?: DO_NOT_CHECK_SYMBOL_RESTORE_K2.takeIf { configurator.frontendKind == FrontendKind.Fir && it in directives }

        val renderMode = directives[PRETTY_RENDERING_MODE].singleOrNull()
        val prettyRenderOptions = when (renderMode ?: prettyRenderMode) {
            PrettyRenderingMode.RENDER_SYMBOLS_LINE_BY_LINE -> renderingOptions
            PrettyRenderingMode.RENDER_SYMBOLS_NESTED -> renderingOptions.copy(renderClassMembers = true)
        }

        fun KtSymbol.safePointer(): KtSymbolPointer<KtSymbol>? {
            val result = kotlin.runCatching { createPointer() }
            return if (directiveToIgnore == null) result.getOrThrow() else result.getOrNull()
        }

        val pointersWithRendered = executeOnPooledThreadInReadAction {
            analyseForTest(ktFile) {
                val (symbols, symbolForPrettyRendering) = collectSymbols(ktFile, testServices)

                val pointerWithRenderedSymbol = symbols.map { symbol ->
                    PointerWithRenderedSymbol(
                        symbol.safePointer(),
                        renderSymbolForComparison(symbol),
                    )
                }

                val pointerWithPrettyRenderedSymbol = symbolForPrettyRendering.map { symbol ->
                    PointerWithRenderedSymbol(
                        symbol.safePointer(),
                        when (symbol) {
                            is KtDeclarationSymbol -> symbol.render(prettyRenderOptions)
                            is KtFileSymbol -> prettyPrint {
                                printCollection(symbol.getFileScope().getAllSymbols().asIterable(), separator = "\n\n") {
                                    append((it as KtDeclarationSymbol).render(prettyRenderOptions))
                                }
                            }

                            is KtReceiverParameterSymbol -> DebugSymbolRenderer.render(symbol)
                            else -> error(symbol::class.toString())
                        }
                    )
                }

                SymbolPointersData(pointerWithRenderedSymbol, pointerWithPrettyRenderedSymbol)
            }
        }

        compareResults(pointersWithRendered, testServices)

        configurator.doOutOfBlockModification(ktFile)

        restoreSymbolsInOtherReadActionAndCompareResults(directiveToIgnore, ktFile, pointersWithRendered.pointers, testServices)
    }

    private fun compareResults(
        data: SymbolPointersData,
        testServices: TestServices,
    ) {
        val actual = data.pointers.renderDeclarations()
        testServices.assertions.assertEqualsToTestDataFileSibling(actual)

        val actualPretty = data.pointersForPrettyRendering.renderDeclarations()
        testServices.assertions.assertEqualsToTestDataFileSibling(actualPretty, extension = ".pretty.txt")
    }

    private fun List<PointerWithRenderedSymbol>.renderDeclarations(): String =
        map { it.rendered }.renderAsDeclarations()

    private fun List<String>.renderAsDeclarations(): String =
        if (isEmpty()) "NO_SYMBOLS"
        else joinToString(separator = "\n\n")

    private fun restoreSymbolsInOtherReadActionAndCompareResults(
        directiveToIgnore: Directive?,
        ktFile: KtFile,
        pointersWithRendered: List<PointerWithRenderedSymbol>,
        testServices: TestServices,
    ) {
        var failed = false
        try {
            val restored = analyseForTest(ktFile) {
                pointersWithRendered.map { (pointer, expectedRender) ->
                    val restored = pointer!!.restoreSymbol() ?: error("Symbol $expectedRender was not restored")

                    renderSymbolForComparison(restored)
                }
            }
            val actual = restored.renderAsDeclarations()
            testServices.assertions.assertEqualsToTestDataFileSibling(actual)
        } catch (e: Throwable) {
            if (directiveToIgnore == null) throw e
            failed = true
        }

        if (failed || directiveToIgnore == null) return

        testServices.assertions.assertEqualsToTestDataFileSibling(
            actual = ktFile.text.lines().filterNot { it == "// ${directiveToIgnore.name}" }.joinToString(separator = "\n"),
            extension = "kt",
        )

        fail("Redundant // ${directiveToIgnore.name} directive")
    }

    protected open fun KtAnalysisSession.renderSymbolForComparison(symbol: KtSymbol): String {
        return with(DebugSymbolRenderer) { renderExtra(symbol) }
    }
}

object SymbolTestDirectives : SimpleDirectivesContainer() {
    val DO_NOT_CHECK_SYMBOL_RESTORE by directive(
        description = "Symbol restoring for some symbols in current test is not supported yet",
    )

    val DO_NOT_CHECK_SYMBOL_RESTORE_K1 by directive(
        description = "Symbol restoring for some symbols in current test is not supported yet in K1",
    )

    val DO_NOT_CHECK_SYMBOL_RESTORE_K2 by directive(
        description = "Symbol restoring for some symbols in current test is not supported yet in K2",
    )

    val PRETTY_RENDERING_MODE by enumDirective(description = "Explicit rendering mode") { PrettyRenderingMode.valueOf(it) }
}

enum class PrettyRenderingMode {
    RENDER_SYMBOLS_LINE_BY_LINE,
    RENDER_SYMBOLS_NESTED,
}

data class SymbolsData(
    val symbols: List<KtSymbol>,
    val symbolsForPrettyRendering: List<KtSymbol> = symbols,
)

private data class SymbolPointersData(
    val pointers: List<PointerWithRenderedSymbol>,
    val pointersForPrettyRendering: List<PointerWithRenderedSymbol>,
)

private data class PointerWithRenderedSymbol(
    val pointer: KtSymbolPointer<*>?,
    val rendered: String,
)
