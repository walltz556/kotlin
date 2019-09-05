/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.SLRUCache
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForLibrariesName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForModulesName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForScriptDependenciesName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForScriptDependenciesSourcesName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForScriptsName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForSdkName
import org.jetbrains.kotlin.analyzer.ResolverForProject.Companion.resolverForSpecialInfoName
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.caches.project.cacheByClass
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.context.GlobalContext
import org.jetbrains.kotlin.context.GlobalContextImpl
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.idea.caches.project.*
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.resolve.util.contextWithCompositeExceptionTracker
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener
import org.jetbrains.kotlin.idea.caches.trackers.outOfBlockModificationCount
import org.jetbrains.kotlin.idea.compiler.IDELanguageSettingsProvider
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesModificationTracker
import org.jetbrains.kotlin.idea.core.script.dependencies.ScriptAdditionalIdeaDependenciesProvider
import org.jetbrains.kotlin.idea.project.TargetPlatformDetector
import org.jetbrains.kotlin.idea.project.useCompositeAnalysis
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.diagnostics.KotlinSuppressCache
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.kotlin.utils.addToStdlib.sumByLong
import java.util.concurrent.ConcurrentHashMap

internal val LOG = Logger.getInstance(KotlinCacheService::class.java)

/**
 * Regulates what sources should be analyzed together.
 *
 * There are exactly two descendants, which are in string one-to-one correspondence with [ResolutionModeComponent.Mode] pick (meaning
 * that after checking value of ResolutionMode, it's safe to downcast settings instance to the respective type):
 * - [PlatformAnalysisSettingsImpl] should be used iff we're working under [Mode.SEPARATE], and will create separate
 *   facade for each platforms, sdk, builtIns settings and other stuff.
 *   This is the old and stable mode, which should be used by default.
 *
 * - [CompositeAnalysisSettings] should be used iff we're working under [Mode.COMPOSITE], and will analyze all sources
 *   together, in one facade.
 *   This mode is new and experimental, and works only together with TypeRefinement facilities in the compiler's frontend.
 *   This mode is currently enabled only for HMPP projects
 */
sealed class PlatformAnalysisSettings {
    // Effectively unused as a property. Needed only to distinguish different modes when being put in a map
    abstract val isReleaseCoroutines: Boolean

    companion object {
        fun create(
            project: Project,
            platform: TargetPlatform,
            sdk: Sdk?,
            isAdditionalBuiltInFeaturesSupported: Boolean,
            isReleaseCoroutines: Boolean
        ) = if (project.useCompositeAnalysis)
            CompositeAnalysisSettings(isReleaseCoroutines)
        else
            PlatformAnalysisSettingsImpl(platform, sdk, isAdditionalBuiltInFeaturesSupported, isReleaseCoroutines)
    }
}

data class PlatformAnalysisSettingsImpl(
    val platform: TargetPlatform,
    val sdk: Sdk?,
    val isAdditionalBuiltInFeaturesSupported: Boolean,
    override val isReleaseCoroutines: Boolean
) : PlatformAnalysisSettings()

data class CompositeAnalysisSettings(override val isReleaseCoroutines: Boolean) : PlatformAnalysisSettings() {
}

class KotlinCacheServiceImpl(val project: Project) : KotlinCacheService {
    override fun getResolutionFacade(elements: List<KtElement>): ResolutionFacade {
        val files = getFilesForElements(elements)
        val platform = TargetPlatformDetector.getPlatform(files.first())
        return getFacadeToAnalyzeFiles(files, platform)
    }

    override fun getResolutionFacade(elements: List<KtElement>, platform: TargetPlatform): ResolutionFacade {
        val files = getFilesForElements(elements)
        return getFacadeToAnalyzeFiles(files, platform)
    }

    private fun getFilesForElements(elements: List<KtElement>): List<KtFile> {
        return elements.map {
            // in theory `containingKtFile` is `@NotNull` but in practice EA-114080
            @Suppress("USELESS_ELVIS")
            it.containingKtFile ?: throw IllegalStateException("containingKtFile was null for $it of ${it.javaClass}")
        }
    }

    override fun getSuppressionCache(): KotlinSuppressCache = kotlinSuppressCache.value

    private val globalFacadesPerPlatformAndSdk: SLRUCache<PlatformAnalysisSettings, GlobalFacade> =
        object : SLRUCache<PlatformAnalysisSettings, GlobalFacade>(2 * 3 * 2, 2 * 3 * 2) {
            override fun createValue(settings: PlatformAnalysisSettings): GlobalFacade {
                return GlobalFacade(settings)
            }
        }

    private val globalScriptFacadesPerPlatformAndSdk: SLRUCache<PlatformAnalysisSettings, ScriptsGlobalFacade> =
        object : SLRUCache<PlatformAnalysisSettings, ScriptsGlobalFacade>(2, 2) {
            override fun createValue(settings: PlatformAnalysisSettings): ScriptsGlobalFacade {
                return ScriptsGlobalFacade(settings)
            }
        }

    private fun createFacadeForScriptDependencies(moduleInfo: ScriptDependenciesInfo.ForFile): ProjectResolutionFacade {
        val sdk = moduleInfo.sdk
        val platform = JvmPlatforms.defaultJvmPlatform // TODO: Js scripts?
        val settings = PlatformAnalysisSettings.create(
            project, platform, sdk, true,
            LanguageFeature.ReleaseCoroutines.defaultState == LanguageFeature.State.ENABLED
        )

        val dependenciesForScriptDependencies = listOf(
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )

        val relatedModules = ScriptAdditionalIdeaDependenciesProvider.getRelatedModules(moduleInfo.scriptFile, project)

        val globalFacade =
            if (relatedModules.isNotEmpty()) {
                getOrBuildGlobalFacade(settings).facadeForModules
            } else {
                getOrBuildGlobalFacade(settings).facadeForSdk
            }

        val globalContext =
            globalFacade.globalContext.contextWithCompositeExceptionTracker(project, "facade for script dependencies of script")
        return ProjectResolutionFacade(
            "facade for script dependencies of script",
            resolverForScriptDependenciesName,
            project, globalContext, settings,
            reuseDataFrom = globalFacade,
            allModules = moduleInfo.dependencies(),
            //TODO: provide correct trackers
            dependencies = dependenciesForScriptDependencies,
            moduleFilter = { it is ScriptDependenciesInfo.ForFile },
            invalidateOnOOCB = false,
            builtInsCache = globalFacade.builtInsCache
        )
    }

    private inner class ScriptsGlobalFacade(settings: PlatformAnalysisSettings) {
        private val trackers = listOf(
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )

        private val dependenciesContext = getOrBuildGlobalFacade(settings).facadeForSdk.globalContext
            .contextWithCompositeExceptionTracker(project, resolverForScriptDependenciesName)

        val facadeForDependencies = ProjectResolutionFacade(
            debugString = "facade for script dependencies",
            resolverDebugName = "$resolverForScriptDependenciesName with settings=${settings}",
            project = project,
            globalContext = dependenciesContext,
            settings = settings,
            moduleFilter = { it is ScriptDependenciesInfo.ForProject },
            allModules = ScriptDependenciesInfo.ForProject(project).dependencies(),
            dependencies = trackers,
            invalidateOnOOCB = false,
            reuseDataFrom = getOrBuildGlobalFacade(settings).facadeForSdk,
            builtInsCache = getOrBuildGlobalFacade(settings).facadeForSdk.builtInsCache
        )

        private val sourcesContext = dependenciesContext
            .contextWithCompositeExceptionTracker(project, resolverForScriptDependenciesSourcesName)

        val facadeForSources = ProjectResolutionFacade(
            debugString = "facade for script dependencies sources",
            resolverDebugName = "$resolverForScriptDependenciesSourcesName with settings=${settings}",
            project = project,
            globalContext = sourcesContext,
            settings = settings,
            reuseDataFrom = facadeForDependencies,
            moduleFilter = { it is ScriptDependenciesSourceInfo.ForProject },
            allModules = ScriptDependenciesSourceInfo.ForProject(project).dependencies(),
            invalidateOnOOCB = false,
            dependencies = trackers,
            builtInsCache = facadeForDependencies.builtInsCache
        )

        fun wrapWitSyntheticFiles(
            reuseDataFrom: ProjectResolutionFacade,
            debugNamePrefix: String,
            files: Set<KtFile>
        ): ProjectResolutionFacade {
            val debugName = debugNamePrefix + " with synthetic files ${files.joinToString { it.name }}"
            val globalContext = reuseDataFrom.globalContext.contextWithCompositeExceptionTracker(project, debugName)

            return ProjectResolutionFacade(
                "facade for $debugNamePrefix", debugName, project, globalContext, reuseDataFrom.settings,
                syntheticFiles = files,
                reuseDataFrom = reuseDataFrom,
                moduleFilter = reuseDataFrom.moduleFilter,
                dependencies = listOf(
                    KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker
                ),
                invalidateOnOOCB = true,
                builtInsCache = reuseDataFrom.builtInsCache
            )
        }
    }

    private inner class GlobalFacade(settings: PlatformAnalysisSettings) {
        private val sdkContext = GlobalContext(resolverForSdkName)
        val facadeForSdk = ProjectResolutionFacade(
            "facadeForSdk", "$resolverForSdkName with settings=$settings",
            project, sdkContext, settings,
            moduleFilter = { it is SdkInfo },
            dependencies = listOf(
                LibraryModificationTracker.getInstance(project),
                ProjectRootModificationTracker.getInstance(project)
            ),
            invalidateOnOOCB = false,
            reuseDataFrom = null,
            builtInsCache = BuiltInsCache(project)
        )

        private val librariesContext = sdkContext.contextWithCompositeExceptionTracker(project, resolverForLibrariesName)
        val facadeForLibraries = ProjectResolutionFacade(
            "facadeForLibraries", "$resolverForLibrariesName with settings=$settings",
            project, librariesContext, settings,
            reuseDataFrom = facadeForSdk,
            moduleFilter = { it is LibraryInfo },
            invalidateOnOOCB = false,
            dependencies = listOf(
                LibraryModificationTracker.getInstance(project),
                ProjectRootModificationTracker.getInstance(project)
            ),
            builtInsCache = facadeForSdk.builtInsCache
        )

        private val modulesContext = librariesContext.contextWithCompositeExceptionTracker(project, resolverForModulesName)
        val facadeForModules = ProjectResolutionFacade(
            "facadeForModules", "$resolverForModulesName with settings=$settings",
            project, modulesContext, settings,
            reuseDataFrom = facadeForLibraries,
            moduleFilter = { !it.isLibraryClasses() },
            dependencies = listOf(
                LibraryModificationTracker.getInstance(project),
                ProjectRootModificationTracker.getInstance(project)
            ),
            invalidateOnOOCB = true,
            builtInsCache = facadeForLibraries.builtInsCache
        )
    }

    private fun IdeaModuleInfo.platformSettings(targetPlatform: TargetPlatform) = PlatformAnalysisSettings.create(
        project, targetPlatform, sdk,
        supportsAdditionalBuiltInsMembers(project),
        isReleaseCoroutines()
    )

    private fun IdeaModuleInfo.isReleaseCoroutines(): Boolean {
        return IDELanguageSettingsProvider
            .getLanguageVersionSettings(this, project)
            .supportsFeature(LanguageFeature.ReleaseCoroutines)
    }

    private fun globalFacade(settings: PlatformAnalysisSettings) =
        getOrBuildGlobalFacade(settings).facadeForModules

    private fun librariesFacade(settings: PlatformAnalysisSettings) =
        getOrBuildGlobalFacade(settings).facadeForLibraries

    @Synchronized
    private fun getOrBuildGlobalFacade(settings: PlatformAnalysisSettings) =
        globalFacadesPerPlatformAndSdk[settings]

    @Synchronized
    private fun getOrBuildScriptsGlobalFacade(): ScriptsGlobalFacade {
        val settings = PlatformAnalysisSettings.create(
            project = project,
            platform = JvmPlatforms.defaultJvmPlatform, // TODO: Js scripts?
            sdk = ScriptDependenciesInfo.ForProject(project).sdk,
            isAdditionalBuiltInFeaturesSupported = true,
            isReleaseCoroutines = LanguageFeature.ReleaseCoroutines.defaultState == LanguageFeature.State.ENABLED
        )
        return globalScriptFacadesPerPlatformAndSdk[settings]
    }

    private fun createFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        // we assume that all files come from the same module
        val scriptModuleInfo = files.map(KtFile::getModuleInfo).toSet().single()
        val platform = scriptModuleInfo.platform
        val settings = scriptModuleInfo.platformSettings(platform)

        if (scriptModuleInfo is ModuleSourceInfo) {
            return globalFacade(settings)
        }

        check(scriptModuleInfo is ScriptModuleInfo) {
            "Unknown ModuleInfo for scripts ${scriptModuleInfo::class.java}"
        }

        val facadeForScriptDependencies = createFacadeForScriptDependencies(
            ScriptDependenciesInfo.ForFile(project, scriptModuleInfo.scriptFile, scriptModuleInfo.scriptDefinition)
        )

        val globalContext = facadeForScriptDependencies.globalContext.contextWithCompositeExceptionTracker(
            project,
            "facadeForScriptModuleInfo"
        )
        return ProjectResolutionFacade(
            "facadeForScriptModuleInfo",
            "$resolverForScriptsName ${files.joinToString { it.name }} for platform $platform",
            project,
            globalContext,
            settings,
            syntheticFiles = files,
            reuseDataFrom = facadeForScriptDependencies,
            moduleFilter = { it == scriptModuleInfo },
            dependencies = listOf(KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker),
            invalidateOnOOCB = true,
            allModules = scriptModuleInfo.dependencies(),
            builtInsCache = facadeForScriptDependencies.builtInsCache
        )
    }

    private fun createFacadeForFilesWithSpecialModuleInfo(files: Set<KtFile>): ProjectResolutionFacade {
        // we assume that all files come from the same module
        val specialModuleInfo = files.map(KtFile::getModuleInfo).toSet().single()

        // Files are from script dependencies or script dependencies sources
        if (specialModuleInfo is ScriptDependenciesInfo || specialModuleInfo is ScriptDependenciesSourceInfo) {
            val scriptsFacade = getOrBuildScriptsGlobalFacade()
            when (specialModuleInfo) {
                is ScriptDependenciesInfo -> scriptsFacade.wrapWitSyntheticFiles(
                    scriptsFacade.facadeForDependencies,
                    resolverForScriptDependenciesName,
                    files
                )
                is ScriptDependenciesSourceInfo -> scriptsFacade.wrapWitSyntheticFiles(
                    scriptsFacade.facadeForSources,
                    resolverForScriptDependenciesSourcesName,
                    files
                )
            }
        }
        val settings = specialModuleInfo.platformSettings(specialModuleInfo.platform)

        // File copies are created during completion and receive correct modification events through POM.
        // Dummy files created e.g. by J2K do not receive events.
        val filesModificationTracker = if (files.all { it.originalFile != it }) {
            ModificationTracker {
                files.sumByLong { it.outOfBlockModificationCount }
            }
        } else {
            ModificationTracker {
                files.sumByLong { it.outOfBlockModificationCount + it.modificationStamp }
            }
        }

        val dependenciesForSyntheticFileCache =
            listOf(
                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker,
                filesModificationTracker
            )

        val resolverDebugName =
            "$resolverForSpecialInfoName $specialModuleInfo for files ${files.joinToString { it.name }} with settings=$settings"

        fun makeProjectResolutionFacade(
            debugName: String,
            globalContext: GlobalContextImpl,
            reuseDataFrom: ProjectResolutionFacade? = null,
            moduleFilter: (IdeaModuleInfo) -> Boolean = { true },
            allModules: Collection<IdeaModuleInfo>? = null
        ): ProjectResolutionFacade {
            return ProjectResolutionFacade(
                debugName,
                resolverDebugName,
                project,
                globalContext,
                settings,
                syntheticFiles = files,
                reuseDataFrom = reuseDataFrom,
                moduleFilter = moduleFilter,
                dependencies = dependenciesForSyntheticFileCache,
                invalidateOnOOCB = true,
                allModules = allModules,
                builtInsCache = reuseDataFrom?.builtInsCache ?: BuiltInsCache(project)
            )
        }

        return when {
            specialModuleInfo is ModuleSourceInfo -> {
                val dependentModules = specialModuleInfo.getDependentModules()
                val modulesFacade = globalFacade(settings)
                val globalContext =
                    modulesFacade.globalContext.contextWithCompositeExceptionTracker(
                        project,
                        "facadeForSpecialModuleInfo (ModuleSourceInfo)"
                    )
                makeProjectResolutionFacade(
                    "facadeForSpecialModuleInfo (ModuleSourceInfo)",
                    globalContext,
                    reuseDataFrom = modulesFacade,
                    moduleFilter = { it in dependentModules }
                )
            }

            specialModuleInfo is LibrarySourceInfo || specialModuleInfo === NotUnderContentRootModuleInfo -> {
                val librariesFacade = librariesFacade(settings)
                val debugName = "facadeForSpecialModuleInfo (LibrarySourceInfo or NotUnderContentRootModuleInfo)"
                val globalContext = librariesFacade.globalContext.contextWithCompositeExceptionTracker(project, debugName)
                makeProjectResolutionFacade(
                    debugName,
                    globalContext,
                    reuseDataFrom = librariesFacade,
                    moduleFilter = { it == specialModuleInfo }
                )
            }

            specialModuleInfo.isLibraryClasses() -> {
                //NOTE: this code should not be called for sdk or library classes
                // currently the only known scenario is when we cannot determine that file is a library source
                // (file under both classes and sources root)
                LOG.warn("Creating cache with synthetic files ($files) in classes of library $specialModuleInfo")
                val globalContext = GlobalContext("facadeForSpecialModuleInfo for file under both classes and root")
                makeProjectResolutionFacade(
                    "facadeForSpecialModuleInfo for file under both classes and root",
                    globalContext
                )
            }

            else -> throw IllegalStateException("Unknown IdeaModuleInfo ${specialModuleInfo::class.java}")
        }
    }

    private val kotlinSuppressCache: CachedValue<KotlinSuppressCache> = CachedValuesManager.getManager(project).createCachedValue(
        {
            CachedValueProvider.Result<KotlinSuppressCache>(
                object : KotlinSuppressCache() {
                    override fun getSuppressionAnnotations(annotated: KtAnnotated): List<AnnotationDescriptor> {
                        if (annotated.annotationEntries.none {
                                it.calleeExpression?.text?.endsWith(SUPPRESS_ANNOTATION_SHORT_NAME) == true
                            }
                        ) {
                            // Avoid running resolve heuristics
                            // TODO: Check aliases in imports
                            return emptyList()
                        }

                        val context =
                            when (annotated) {
                                is KtFile -> {
                                    annotated.fileAnnotationList?.analyze(BodyResolveMode.PARTIAL)
                                        ?: return emptyList()
                                }
                                is KtModifierListOwner -> {
                                    annotated.modifierList?.analyze(BodyResolveMode.PARTIAL)
                                        ?: return emptyList()
                                }
                                else ->
                                    annotated.analyze(BodyResolveMode.PARTIAL)
                            }

                        val annotatedDescriptor = context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, annotated)

                        if (annotatedDescriptor != null) {
                            return annotatedDescriptor.annotations.toList()
                        }

                        return annotated.annotationEntries.mapNotNull {
                            context.get(
                                BindingContext.ANNOTATION,
                                it
                            )
                        }
                    }
                },
                LibraryModificationTracker.getInstance(project),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        },
        false
    )

    private val specialFilesCacheProvider = CachedValueProvider {
        // NOTE: computations inside createFacadeForFilesWithSpecialModuleInfo depend on project root structure
        // so we additionally drop the whole slru cache on change
        CachedValueProvider.Result(
            object : SLRUCache<Set<KtFile>, ProjectResolutionFacade>(2, 3) {
                override fun createValue(files: Set<KtFile>) = createFacadeForFilesWithSpecialModuleInfo(files)
            },
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project)
        )
    }

    private fun getFacadeForSpecialFiles(files: Set<KtFile>): ProjectResolutionFacade {
        val cachedValue: SLRUCache<Set<KtFile>, ProjectResolutionFacade> =
            CachedValuesManager.getManager(project).getCachedValue(project, specialFilesCacheProvider)

        // In Upsource, we create multiple instances of KotlinCacheService, which all access the same CachedValue instance (UP-8046)
        // This is so because class name of provider is used as a key when fetching cached value, see CachedValueManager.getKeyForClass.
        // To avoid race conditions, we can't use any local lock to access the cached value contents.
        return synchronized(cachedValue) {
            cachedValue.get(files)
        }
    }

    private val scriptsCacheProvider = CachedValueProvider {
        CachedValueProvider.Result(
            object : SLRUCache<Set<KtFile>, ProjectResolutionFacade>(10, 5) {
                override fun createValue(files: Set<KtFile>) = createFacadeForScripts(files)
            },
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )
    }

    private fun getFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        val cachedValue: SLRUCache<Set<KtFile>, ProjectResolutionFacade> =
            CachedValuesManager.getManager(project).getCachedValue(project, scriptsCacheProvider)

        return synchronized(cachedValue) {
            cachedValue.get(files)
        }
    }

    private fun getFacadeToAnalyzeFiles(files: Collection<KtFile>, platform: TargetPlatform): ResolutionFacade {
        val file = files.first()
        val moduleInfo = file.getModuleInfo()
        val specialFiles = files.filterNotInProjectSource(moduleInfo)
        val scripts = specialFiles.filterScripts()
        if (scripts.isNotEmpty()) {
            val projectFacade = getFacadeForScripts(scripts)
            return ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(scripts, moduleInfo)
        }

        if (specialFiles.isNotEmpty()) {
            val projectFacade = getFacadeForSpecialFiles(specialFiles)
            return ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(specialFiles, moduleInfo)
        }

        return getResolutionFacadeByModuleInfo(moduleInfo, platform).createdFor(emptyList(), moduleInfo, platform)
    }

    override fun getResolutionFacadeByFile(file: PsiFile, platform: TargetPlatform): ResolutionFacade? {
        if (!ProjectRootsUtil.isInProjectOrLibraryContent(file)) {
            return null
        }

        assert(file !is PsiCodeFragment)

        val moduleInfo = file.getModuleInfo()
        return getResolutionFacadeByModuleInfo(moduleInfo, platform)
    }

    private fun getResolutionFacadeByModuleInfo(moduleInfo: IdeaModuleInfo, platform: TargetPlatform): ResolutionFacade {
        val settings = moduleInfo.platformSettings(platform)
        val projectFacade = when (moduleInfo) {
            is ScriptDependenciesInfo.ForProject -> getOrBuildScriptsGlobalFacade().facadeForDependencies
            is ScriptDependenciesSourceInfo.ForProject -> getOrBuildScriptsGlobalFacade().facadeForSources
            is ScriptDependenciesInfo.ForFile -> createFacadeForScriptDependencies(moduleInfo)
            else -> globalFacade(settings)
        }
        return ModuleResolutionFacadeImpl(projectFacade, moduleInfo)
    }

    override fun getResolutionFacadeByModuleInfo(moduleInfo: ModuleInfo, platform: TargetPlatform): ResolutionFacade? =
        (moduleInfo as? IdeaModuleInfo)?.let { getResolutionFacadeByModuleInfo(it, platform) }

    private fun Collection<KtFile>.filterNotInProjectSource(moduleInfo: IdeaModuleInfo): Set<KtFile> {
        return mapNotNull {
            if (it is KtCodeFragment) it.getContextFile() else it
        }.filter {
            !ProjectRootsUtil.isInProjectSource(it) || !moduleInfo.contentScope().contains(it)
        }.toSet()
    }

    private fun Collection<KtFile>.filterScripts(): Set<KtFile> {
        return mapNotNull {
            if (it is KtCodeFragment) it.getContextFile() else it
        }.filter { it.isScript() }.toSet()
    }

    private fun KtCodeFragment.getContextFile(): KtFile? {
        val contextElement = context ?: return null
        val contextFile = (contextElement as? KtElement)?.containingKtFile
            ?: throw AssertionError("Analyzing kotlin code fragment of type ${this::class.java} with java context of type ${contextElement::class.java}")
        return if (contextFile is KtCodeFragment) contextFile.getContextFile() else contextFile
    }

    private companion object {
        private val SUPPRESS_ANNOTATION_SHORT_NAME = KotlinBuiltIns.FQ_NAMES.suppress.shortName().identifier
    }
}

class BuiltInsCache(private val project: Project) {
    private val innerCache
        get() = project.cacheByClass(
            BuiltInsCache::class.java,
            ProjectRootModificationTracker.getInstance(project),
            LibraryModificationTracker.getInstance(project)
        ) { ConcurrentHashMap<BuiltInsCacheKey, KotlinBuiltIns>() }

    init {
        innerCache[BuiltInsCacheKey.DefaultBuiltInsKey] = DefaultBuiltIns.Instance
    }

    fun getOrPut(key: BuiltInsCacheKey, ifAbsent: (BuiltInsCacheKey) -> KotlinBuiltIns): KotlinBuiltIns {
        return innerCache.computeIfAbsent(key, ifAbsent)
    }

    operator fun get(key: BuiltInsCacheKey): KotlinBuiltIns? {
        return innerCache[key]
    }

    operator fun set(key: BuiltInsCacheKey, value: KotlinBuiltIns) {
        innerCache[key] = value
    }
}

interface BuiltInsCacheKey {
    object DefaultBuiltInsKey : BuiltInsCacheKey
}

fun IdeaModuleInfo.supportsAdditionalBuiltInsMembers(project: Project): Boolean {
    return IDELanguageSettingsProvider
        .getLanguageVersionSettings(this, project)
        .supportsFeature(LanguageFeature.AdditionalBuiltInsMembers)
}

val IdeaModuleInfo.sdk: Sdk? get() = dependencies().firstIsInstanceOrNull<SdkInfo>()?.sdk