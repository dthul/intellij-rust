/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.psi.util.parentOfType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.ext.RsCachedItems.CachedNamedImport
import org.rust.lang.core.psi.ext.RsCachedItems.CachedStarImport
import org.rust.lang.core.resolve.namespaces

fun buildCrateDefMapContainingExplicitItems(crate: Crate): Pair<CrateDefMap, List<Import>>? {
    val crateRoot = crate.rootModule ?: return null
    val crateId = crate.id ?: return null

    val (rootModuleData, imports) = ModCollector(crateId).collect(crateRoot)

    // todo if dependency have prelude, then we should overwrite our prelude
    //  https://github.com/rust-analyzer/rust-analyzer/blob/d47834ee1b8fce7956272e27c9403021940fec8e/crates/ra_hir_def/src/nameres/collector.rs#L55

    // todo вынести в отдельный метод
    val externPrelude = crate.dependencies
        .mapNotNull {
            val defMap = it.crate.defMap ?: return@mapNotNull null
            it.normName to defMap.root
        }
        .toMap(mutableMapOf())
    val allDefMaps = crate.flatDependencies
        .mapNotNull {
            val id = it.id ?: return@mapNotNull null
            val defMap = it.defMap ?: return@mapNotNull null
            id to defMap
        }
        .toMap()
    val defMap = CrateDefMap(crateId, crate.edition, rootModuleData, externPrelude, allDefMaps)

    return Pair(defMap, imports)
}

class ModCollector(private val crate: CratePersistentId) {

    private val imports: MutableList<Import> = mutableListOf()

    fun collect(root: RsMod): Pair<ModData, List<Import>> {
        val rootData = collectRecursively(root, null)
        val imports = imports.filter { it.isGlob || it.nameInScope != "_" }  // todo unnamed trait imports
        return Pair(rootData, imports)
    }

    private fun collectRecursively(mod: RsMod, parent: ModData?): ModData {
        val modPath = parent?.path?.append(mod.modName!! /* todo */) ?: ModPath(crate, emptyList())
        val modData = ModData(mutableMapOf(), mutableMapOf(), parent, crate, modPath)

        val expandedItems = mod.expandedItemsCached
        modData.visibleItems += collectVisibleItems(expandedItems.rest, modData)
        collectImports(modData, expandedItems)

        // todo maybe ignore mods and enums in `collectVisibleItems` and add `childModules.map { it.asPerNs() }` to `visibleItems` ?
        // todo use `expandedItemsExceptImplsAndUses` directly ?
        modData.childModules += mod.childModules
            .associate { it.modName!! /* todo */ to collectRecursively(it, modData) }
        modData.childModules += expandedItems.rest
            .filterIsInstance<RsEnumItem>()
            .associate { it.name!! /* todo */ to collectEnumAsModData(it, modData) }
        return modData
    }

    private fun collectVisibleItems(expandedItems: List<RsItemElement>, modData: ModData): MutableMap<String, PerNs> {
        val visibleItems = mutableMapOf<String, PerNs>()
        for (item in expandedItems.filterIsInstance<RsNamedElement>()) {
            if (item is RsExternCrateItem) continue
            val visItem = convertToVisItem(item, modData) ?: continue
            val perNsExisting = visibleItems[visItem.name]
            val perNs = PerNs(visItem, item.namespaces)
            if (perNsExisting == null) {
                visibleItems[visItem.name] = perNs
            } else {
                // todo check performance
                perNsExisting.update(perNs)
            }
        }
        return visibleItems
    }

    private fun collectImports(modData: ModData, expandedItems: RsCachedItems) {
        imports += expandedItems.namedImports.map { it.convertToImport(modData) }
        imports += expandedItems.starImports.mapNotNull { it.convertToImport(modData) }

        val externCrates = expandedItems.rest.filterIsInstance<RsExternCrateItem>()
        imports += externCrates.map { it.convertToImport(modData) }
    }

    private fun collectEnumAsModData(enum: RsEnumItem, parent: ModData): ModData {
        val enumPath = parent.path.append(enum.name!! /* todo */)
        val visibleItems = enum.variants
            .mapNotNull { variant ->
                val variantName = variant.name ?: return@mapNotNull null
                val variantPath = enumPath.append(variantName)
                val visItem = VisItem(variantPath, Visibility.Public, isModOrEnum = false)
                variantName to PerNs(visItem, variant.namespaces)
            }
            .toMap(mutableMapOf())
        return ModData(mutableMapOf(), visibleItems, parent, crate, enumPath)
    }
}

private fun convertToVisItem(item: RsNamedElement, containingMod: ModData): VisItem? {
    val visibility = (item as? RsVisibilityOwner).getVisibility(containingMod)
    val itemName = item.name ?: return null
    val itemPath = containingMod.path.append(itemName)
    val isModOrEnum = item is RsMod || item is RsModDeclItem || item is RsEnumItem
    return VisItem(itemPath, visibility, isModOrEnum)
}

private fun CachedNamedImport.convertToImport(containingMod: ModData): Import {
    val visibility = path.parentOfType<RsUseItem>().getVisibility(containingMod)
    return Import(containingMod, path.fullPath, nameInScope, visibility, isGlob = false)
}

private fun CachedStarImport.convertToImport(containingMod: ModData): Import? {
    val usePath = speck.path?.fullPath
        ?: when (val parent = speck.parent) {
            // `use ::*;`
            //        ^ speck
            is RsUseItem -> "crate"
            // `use aaa::{self, *};`
            //                  ^ speck
            is RsUseGroup -> (parent.parent as? RsUseSpeck)?.path?.fullPath ?: return null
            else -> return null
        }
    val visibility = speck.parentOfType<RsUseItem>().getVisibility(containingMod)
    return Import(containingMod, usePath, "_" /* todo */, visibility, isGlob = true)
}

private fun RsExternCrateItem.convertToImport(containingMod: ModData): Import {
    return Import(
        containingMod,
        referenceName,
        nameWithAlias,
        getVisibility(containingMod),
        isExternCrate = true,
        isMacroUse = hasMacroUse
    )
}

private fun RsVisibilityOwner?.getVisibility(containingMod: ModData): Visibility {
    if (this == null) return Visibility.Public
    return when (val visibility = visibility) {
        RsVisibility.Public -> Visibility.Public
        RsVisibility.Private -> Visibility.Restricted(containingMod)
        is RsVisibility.Restricted -> {
            val psiSuperMods = visibility.inMod.superMods.asSequence()
            val ourSuperMods = containingMod.parents
            val (_, inMod) = (psiSuperMods zip ourSuperMods)
                .find { (psiSuperMod, _) -> psiSuperMod == visibility.inMod }
                ?: return Visibility.Public
            // todo optimization: store visibility objects in ModData
            Visibility.Restricted(inMod)
        }
    }
}
