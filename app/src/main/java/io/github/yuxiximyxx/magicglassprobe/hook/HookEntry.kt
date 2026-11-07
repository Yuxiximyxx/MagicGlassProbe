package io.github.yuxiximyxx.magicglassprobe.hook

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 入口：只处理微信主进程。微信的 :push / :appbrand 等子进程不装探针，
 * 免得日志被无关进程刷屏、也免得在小程序窗口里误触发。
 */
class HookEntry : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        log(Log.INFO, TAG, "ENTRY pkg=${param.packageName} first=${param.isFirstPackage}")

        val processName = runCatching {
            val appClass = Class.forName("android.app.Application")
            appClass.getMethod("getProcessName").invoke(null) as? String
        }.getOrNull()
        log(Log.INFO, TAG, "process=$processName")
        if (processName != TARGET_PACKAGE) {
            log(Log.INFO, TAG, "非主进程，跳过")
            return
        }

        try {
            GlassProbe.install(this, param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "install 失败（已吞掉，微信照常启动）", t)
        }
    }

    companion object {
        const val TAG = "MagicGlassProbe"
        const val TARGET_PACKAGE = "com.tencent.mm"

        /** 当前 Application（探针取资源/密度用），拿不到返回 null。 */
        fun currentApplication(): Application? = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }
}
