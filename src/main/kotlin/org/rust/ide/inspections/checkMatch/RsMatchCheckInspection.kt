/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.checkMatch

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.rust.ide.inspections.RsLocalInspectionTool
import org.rust.ide.inspections.RsProblemsHolder
import org.rust.ide.inspections.checkMatch.Constructor.Companion.allConstructors
import org.rust.ide.inspections.checkMatch.Usefulness.*
import org.rust.ide.inspections.fixes.SubstituteTextFix
import org.rust.lang.core.psi.RsElementTypes.OR
import org.rust.lang.core.psi.RsMatchArm
import org.rust.lang.core.psi.RsMatchExpr
import org.rust.lang.core.psi.RsVisitor
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.infer.containsTyOfClass
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type
import org.rust.lang.utils.RsDiagnostic
import org.rust.lang.utils.addToHolder

class RsMatchCheckInspection : RsLocalInspectionTool() {
    override fun getDisplayName() = "Match Check"

    override fun buildVisitor(holder: RsProblemsHolder, isOnTheFly: Boolean) = object : RsVisitor() {
        override fun visitMatchExpr(matchExpr: RsMatchExpr) {
            val exprType = matchExpr.expr?.type ?: return
            if (exprType.containsTyOfClass(TyUnknown::class.java)) return
            try {
                checkUselessArm(matchExpr, holder)
                checkExhaustive(matchExpr, holder)
            } catch (todo: NotImplementedError) {
            } catch (e: CheckMatchException) {
            }
        }
    }
}


private fun checkUselessArm(match: RsMatchExpr, holder: RsProblemsHolder) {
    val matrix = match.arms
        .calculateMatrix()
        .takeIf { it.type !is TyUnknown }
        ?: return

    val armPats = match.arms.flatMap { it.patList }
    val seen = mutableListOf<List<Pattern>>()

    for ((i, patterns) in matrix.withIndex()) {
        val armPat = armPats[i]
        val useful = isUseful(seen, patterns, false, match.crateRoot)
        if (!useful.isUseful) {
            val arm = armPat.ancestorStrict<RsMatchArm>() ?: return

            val fix = if (arm.patList.size == 1) {
                /** if the arm consists of only one pattern, we can delete the whole arm */
                SubstituteTextFix.delete("Remove useless arm", match.containingFile, arm.rangeWithPrevSpace)
            } else {
                /** otherwise, delete only ` | <pat>` part from the arm */
                val separatorRange = (armPat.getPrevNonCommentSibling() as? LeafPsiElement)
                    ?.takeIf { it.elementType == OR }
                    ?.rangeWithPrevSpace
                    ?: TextRange.EMPTY_RANGE

                val range = armPat.rangeWithPrevSpace.union(separatorRange)
                SubstituteTextFix.delete("Remove useless pattern", match.containingFile, range)
            }

            holder.registerProblem(armPat, "Unreachable pattern", ProblemHighlightType.WARNING, fix)
        }

        /** if the arm is not guarded, we have "seen" the pattern */
        if (armPat.ancestorStrict<RsMatchArm>()?.matchArmGuard == null) {
            seen.add(patterns)
        }
    }
}

private fun checkExhaustive(match: RsMatchExpr, holder: RsProblemsHolder) {
    val matrix = match.arms
        .filter { it.matchArmGuard == null }
        .calculateMatrix()
        .takeIf { it.type !is TyUnknown }
        ?: return

    val useful = isUseful(matrix, listOf(Pattern.Wild), true, match.crateRoot)

    /** if `_` pattern is useful, the match is not exhaustive */
    if (useful is UsefulWithWitness) {
        val patterns = useful.witnesses.mapNotNull { it.patterns.firstOrNull() }
        RsDiagnostic.NonExhaustiveMatch(match, patterns).addToHolder(holder)
    }
}

/** Use algorithm from 3.1 http://moscova.inria.fr/~maranget/papers/warn/warn004.html */
private fun isUseful(matrix: Matrix, patterns: List<Pattern>, withWitness: Boolean, crateRoot: RsMod?): Usefulness {
    fun expandConstructors(constructors: List<Constructor>, type: Ty): Usefulness = constructors
        .map { isUsefulSpecialized(matrix, patterns, it, type, withWitness, crateRoot) }
        .find { it.isUseful }
        ?: Useless

    if (patterns.isEmpty()) {
        if (matrix.isEmpty()) {
            return if (withWitness) UsefulWithWitness.Empty else Useful
        }
        return Useless
    }

    val type = matrix.firstColumnType
    val constructors = patterns.first().constructors
    if (constructors != null) {
        return expandConstructors(constructors, type)
    }

    val usedConstructors = matrix.flatMap { it.firstOrNull()?.constructors ?: emptyList() }
    val allConstructors = allConstructors(type)
    val missingConstructor = allConstructors.minus(usedConstructors)

    val isPrivatelyEmpty = allConstructors.isEmpty()
    val isDeclaredNonExhaustive = type is TyAdt &&
        type.item.queryAttributes.hasAtomAttribute("non_exhaustive")
    val isInDifferentCrate = type is TyAdt && type.item.crateRoot != crateRoot

    val isNonExhaustive = isPrivatelyEmpty || (isDeclaredNonExhaustive && isInDifferentCrate)

    if (missingConstructor.isEmpty() && !isNonExhaustive) {
        return expandConstructors(allConstructors, type)
    }

    val newMatrix = matrix.mapNotNull {
        val kind = it.firstOrNull()?.kind
        if (kind is PatternKind.Wild || kind is PatternKind.Binding) {
            it.subList(1, it.size)
        } else {
            null
        }
    }
    val newPatterns = patterns.subList(1, patterns.size)
    val res = isUseful(newMatrix, newPatterns, withWitness, crateRoot)

    if (res is UsefulWithWitness) {
        val newWitness = if (isNonExhaustive || usedConstructors.isEmpty()) {
            res.witnesses.map { witness ->
                witness.patterns.add(Pattern(type, PatternKind.Wild))
                witness
            }
        } else {
            res.witnesses.flatMap { witness ->
                missingConstructor.map { witness.clone().pushWildConstructor(it, type) }
            }
        }
        return UsefulWithWitness(newWitness)
    }

    return res
}

private fun isUsefulSpecialized(
    matrix: Matrix,
    patterns: List<Pattern>,
    constructor: Constructor,
    type: Ty,
    withWitness: Boolean,
    crateRoot: RsMod?
): Usefulness {
    val newPatterns = specializeRow(patterns, constructor, type) ?: return Useless
    val newMatrix = matrix.mapNotNull { specializeRow(it, constructor, type) }

    return when (val useful = isUseful(newMatrix, newPatterns, withWitness, crateRoot)) {
        is UsefulWithWitness -> UsefulWithWitness(useful.witnesses.map { it.applyConstructor(constructor, type) })
        else -> useful
    }
}

private fun specializeRow(row: List<Pattern>, constructor: Constructor, type: Ty): List<Pattern>? {
    val pat = row.firstOrNull() ?: return emptyList()
    val wildPatterns = MutableList(constructor.arity(type)) { Pattern.Wild }

    val head: List<Pattern>? = when (val kind = pat.kind) {
        is PatternKind.Variant -> {
            if (constructor == pat.constructors?.first()) {
                wildPatterns.apply { fillWithSubPatterns(kind.subPatterns) }
            } else {
                null
            }
        }

        is PatternKind.Leaf -> wildPatterns.apply { fillWithSubPatterns(kind.subPatterns) }

        is PatternKind.Deref -> listOf(kind.subPattern)

        is PatternKind.Const -> when {
            constructor is Constructor.Slice -> TODO()
            constructor.coveredByRange(kind.value, kind.value, true) -> emptyList()
            else -> null
        }

        is PatternKind.Range -> when {
            constructor.coveredByRange(kind.lc, kind.rc, kind.isInclusive) -> emptyList()
            else -> null
        }

        is PatternKind.Slice, is PatternKind.Array -> TODO()

        PatternKind.Wild, is PatternKind.Binding -> wildPatterns
    }

    return head?.plus(row.subList(1, row.size))
}

private fun MutableList<Pattern>.fillWithSubPatterns(subPatterns: List<Pattern>) {
    for ((index, pattern) in subPatterns.withIndex()) {
        while (size <= index) add(Pattern.Wild) // TODO: maybe it's better to throw an exception?
        this[index] = pattern
    }
}
