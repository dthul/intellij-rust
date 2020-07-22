/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.project.Project
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.crateGraph
import org.rust.lang.core.psi.RsEnumItem
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*

fun buildCrateDefMap(crate: Crate): CrateDefMap? {
    // todo read action
    val (defMap, imports) = buildCrateDefMapContainingExplicitItems(crate) ?: return null
    DefCollector(defMap, imports).collect()
    return defMap
}

fun processItemDeclarations2(
    scope: RsMod,
    ns: Set<Namespace>,
    originalProcessor: RsResolveProcessor,
    ipm: ItemProcessingMode  // todo
): Boolean {
    val project = scope.project
    val crate = scope.containingCrate ?: return false
    val defMap = crate.defMap ?: return false
    val modData = defMap.getModData(scope) ?: return false

    // todo optimization: попробовать избавиться от цикла и передавать name как параметр
    val namesInTypesNamespace = mutableSetOf<String>()
    for ((name, perNs) in modData.visibleItems) {
        // todo refactor ?
        val types = perNs.types?.takeIf { Namespace.Types in ns }?.toPsi(project, Namespace.Types)
        val values = perNs.values?.takeIf { Namespace.Values in ns }?.toPsi(project, Namespace.Values)
        val macros = perNs.macros?.takeIf { Namespace.Macros in ns }?.toPsi(project, Namespace.Macros)
        for (element in setOf(types, values, macros)) {
            if (element == null) continue
            val entry = SimpleScopeEntry(name, element)
            originalProcessor(entry) && return true
        }

        if (types != null) namesInTypesNamespace += name
    }

    if (ipm.withExternCrates && Namespace.Types in ns) {
        for ((name, externCrateModData) in defMap.externPrelude) {
            if (name in namesInTypesNamespace) continue
            val externCratePsi = externCrateModData.asVisItem().toPsi(project, Namespace.Types)!!  // todo
            val entry = SimpleScopeEntry(name, externCratePsi)
            originalProcessor(entry) && return true
        }
    }

    return false
}

private fun VisItem.toPsi(project: Project, ns: Namespace): RsNamedElement? {
    if (isModOrEnum) return path.toRsModOrEnum(project)
    val containingModOrEnum = containingMod.toRsModOrEnum(project) ?: return null
    return when (containingModOrEnum) {
        is RsMod -> containingModOrEnum.expandedItemsExceptImplsAndUses
            .filterIsInstance<RsNamedElement>()
            .find { it.name == name && ns in it.namespaces }
        is RsEnumItem -> containingModOrEnum.variants.find { it.name == name && ns in it.namespaces }
        else -> error("unreachable")
    }
}

private fun ModPath.toRsModOrEnum(project: Project): RsNamedElement? /* RsMod or RsEnumItem */ {
    val crate = project.crateGraph.findCrateById(crate) ?: return null
    val crateRoot = crate.rootModule ?: return null
    if (segments.isEmpty()) return crateRoot
    val parentMod = segments
        .subList(0, segments.size - 1)
        .fold(crateRoot as RsMod) { mod, segment ->
            val childMod = mod.childModules.find { it.modName == segment }
            childMod ?: return null
        }

    val lastSegment = segments.last()
    val mod = parentMod.childModules.find { it.modName == lastSegment }
    // todo performance
    val enum = parentMod.expandedItemsExceptImplsAndUses
        .find { it.name == lastSegment && it is RsEnumItem }
        as RsNamedElement?
    return mod ?: enum
}
