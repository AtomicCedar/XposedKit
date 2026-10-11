package cc.meteormc.xposedkit

import cc.meteormc.xposedkit.param.HotReloadingParam
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.param.ProcessLoadedParam
import cc.meteormc.xposedkit.param.SystemServerStartingParam

interface XposedModule {
    fun onProcessLoaded(param: ProcessLoadedParam) {

    }

    fun onPackageLoaded(param: PackageLoadedParam) {

    }

    fun onSystemServerStarting(param: SystemServerStartingParam) {

    }

    fun onHotReloading(param: HotReloadingParam): Boolean {
        return true
    }
}