/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import org.rust.lang.core.resolve2.ImportType.GLOB
import org.rust.lang.core.resolve2.ImportType.NAMED
import org.rust.lang.core.resolve2.PartialResolvedImport.*
import kotlin.reflect.KMutableProperty0

// resolves all imports (and adds to defMap) using fixed point iteration algorithm
class DefCollector(private val defMap: CrateDefMap, allImports: List<Import>) {

    // reversed glob-imports graph, that is
    // for each module (`targetMod`) store all modules which contain glob import to `targetMod`
    private val globImports: MutableMap<ModData, MutableList<Pair<ModData, Visibility>>> = mutableMapOf()
    private val unresolvedImports: MutableList<Import> = allImports.toMutableList()
    private val resolvedImports: MutableList<Import> = mutableListOf()

    // for each module records names which comes from glob-imports
    // to determine whether we can override them (usual imports overrides glob-imports)
    private val fromGlobImport: PerNsGlobImports = PerNsGlobImports()

    fun collect() {
        resolveImports()
    }

    /**
     * Import resolution
     *
     * This is a fix point algorithm. We resolve imports until no forward progress in resolving imports is made
     */
    private fun resolveImports() {
        var hasChangedImports = true
        while (hasChangedImports) {
            hasChangedImports = unresolvedImports.removeIf { import ->
                import.status = resolveImport(import)
                when (import.status) {
                    is Indeterminate -> {
                        recordResolvedImport(import)
                        // TODO: To avoid performance regression,
                        //  we consider an imported resolved if it is indeterminate (i.e not all namespace resolved)
                        resolvedImports.add(import)
                        true
                    }
                    is Resolved -> {
                        recordResolvedImport(import)
                        resolvedImports.add(import)
                        true
                    }
                    is Unresolved -> {
                        false
                    }
                }
            }
        }
    }

    private fun resolveImport(import: Import): PartialResolvedImport {
        if (import.isExternCrate) {
            val res = defMap.resolveNameInExternPrelude(import.usePath)
            return Resolved(res)
        }

        val result = defMap.resolvePathFp(import.containingMod, import.usePath, ResolveMode.IMPORT)
        val perNs = result.resolvedDef

        if (!result.reachedFixedPoint || perNs.isEmpty) return Unresolved

        // for path `mod1::mod2::mod3::foo`
        // if any of `mod1`, ... , `mod3` is from other crate
        // then it means that defMap for that crate is already completely filled
        if (result.visitedOtherCrate) return Resolved(perNs)

        return if (perNs.types != null && perNs.values != null && perNs.macros != null) {
            Resolved(perNs)
        } else {
            Indeterminate(perNs)
        }
    }

    private fun recordResolvedImport(import: Import) {
        val containingMod = import.containingMod
        val def = when (val status = import.status) {
            is Resolved -> status.perNs
            is Indeterminate -> status.perNs
            Unresolved -> error("expected resoled import")
        }

        if (import.isGlob) {
            val types = def.types ?: return  // todo log error "glob import {} didn't resolve as type"
            if (!types.isModOrEnum) return  // todo log error "glob import {} from non-module/enum {}"
            val targetMod = defMap.defDatabase.tryCastToModData(types)!!
            if (targetMod.crate == defMap.crate) {
                // glob import from same crate => we do an initial import,
                // and then need to propagate any further additions
                val items = targetMod.getVisibleItems { it.isVisibleFromMod(containingMod) }
                update(containingMod, items, import.visibility, GLOB)

                // record the glob import in case we add further items
                val globImports = globImports.getOrPut(targetMod, ::mutableListOf)
                // todo globImports - Set ?
                // todo if there are two glob imports, we should choose with widest visibility
                if (globImports.none { (mod, _) -> mod == containingMod }) {
                    globImports += containingMod to import.visibility
                }
            } else {
                // glob import from other crate => we can just import everything once
                val items = targetMod.getVisibleItems { it.isVisibleFromOtherCrate() }
                update(containingMod, items, import.visibility, GLOB)
            }
        } else {
            val name = import.nameInScope
            check(name != "_") { "underscore imports must be filtered out when collecting imports" }  // todo trait imports

            // extern crates in the crate root are special-cased to insert entries into the extern prelude
            // rust-lang/rust#54658
            if (import.isExternCrate && containingMod.isCrateRoot) {
                val externCrateDefMap = defMap.defDatabase.tryCastToModData(def)
                externCrateDefMap?.let { defMap.externPrelude[name] = it }
            }

            update(containingMod, listOf(name to def), import.visibility, NAMED)
        }
    }

    // `resolutions` were imported to `modData` with `visibility`
    // we update `modData.visibleItems` and propagate `resolutions` to modules which have glob import from `modData`
    private fun update(
        modData: ModData,
        resolutions: List<Pair<String, PerNs>>,
        visibility: Visibility,
        importType: ImportType
    ) {
        updateRecursive(modData, resolutions, visibility, importType, depth = 0)
    }

    private fun updateRecursive(
        modData: ModData,
        resolutions: List<Pair<String, PerNs>>,
        visibility: Visibility,
        importType: ImportType,
        depth: Int
    ) {
        check(depth <= 100) { "infinite recursion in glob imports!" }

        var changed = false
        for ((name, def) in resolutions) {
            if (pushResolutionFromImport(modData, name, def.withVisibility(visibility), importType)) {
                changed = true
            }
        }
        if (!changed) return

        val globImports = globImports[modData] ?: return
        for ((globImportingMod, globImportVis) in globImports) {
            // we know all resolutions have the same `visibility`, so we just need to check that once
            if (!visibility.isVisibleFromMod(globImportingMod)) continue
            updateRecursive(globImportingMod, resolutions, globImportVis, GLOB, depth + 1)
        }
    }

    private fun pushResolutionFromImport(modData: ModData, name: String, def: PerNs, importType: ImportType): Boolean {
        if (def.isEmpty) return false
        val defExisting = modData.visibleItems.getOrPut(name, { PerNs() })

        fun pushOneNs(
            visItem: VisItem?,
            visItemExisting: KMutableProperty0<VisItem?>,
            fromGlobImport: MutableSet<Pair<ModData, String>>
        ): Boolean {
            if (visItem == null) return false
            if (visItemExisting.get() == null) {
                when (importType) {
                    NAMED -> fromGlobImport.remove(modData to name)
                    GLOB -> fromGlobImport.add(modData to name)
                }
                visItemExisting.set(visItem)
                return true
            } else if (importType == NAMED) {
                if (fromGlobImport.contains(modData to name)) {
                    fromGlobImport.remove(modData to name)
                    visItemExisting.set(visItem)
                    return true
                } else {
                    // multiple named imports has same `nameInScope`
                    error("todo: handle multiresolve ?")
                }
            }
            return false
        }

        val changedTypes = pushOneNs(def.types, defExisting::types, fromGlobImport.types)
        val changedValues = pushOneNs(def.values, defExisting::values, fromGlobImport.values)
        val changedMacros = pushOneNs(def.macros, defExisting::macros, fromGlobImport.macros)
        return changedTypes || changedValues || changedMacros
        // todo else push to `unresolved` ?
    }
}

private class PerNsGlobImports {
    val types: MutableSet<Pair<ModData, String>> = mutableSetOf()
    val values: MutableSet<Pair<ModData, String>> = mutableSetOf()
    val macros: MutableSet<Pair<ModData, String>> = mutableSetOf()
}

data class Import(
    val containingMod: ModData,
    val usePath: String,  // foo::bar::baz
    val nameInScope: String,
    val visibility: Visibility,
    val isGlob: Boolean = false,
    val isExternCrate: Boolean = false,
    val isMacroUse: Boolean = false,

    var status: PartialResolvedImport = Unresolved
)

enum class ImportType { NAMED, GLOB }

sealed class PartialResolvedImport {
    // None of any namespaces is resolved
    object Unresolved : PartialResolvedImport()

    // One of namespaces is resolved
    data class Indeterminate(val perNs: PerNs) : PartialResolvedImport()

    // All namespaces are resolved, OR it is came from other crate
    data class Resolved(val perNs: PerNs) : PartialResolvedImport()
}
