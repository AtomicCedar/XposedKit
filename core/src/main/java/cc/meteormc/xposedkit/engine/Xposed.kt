package cc.meteormc.xposedkit.engine

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.XposedKit.TAG
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.hook.InvokeInfo
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.param.ProcessLoadedParam
import cc.meteormc.xposedkit.param.SystemServerStartingParam
import cc.meteormc.xposedkit.reflect
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileNotFoundException
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class Xposed : Engine, IXposedHookZygoteInit, IXposedHookLoadPackage {
    init {
        XposedKit.init(this)
    }

    private var moduleSource: String? = null

    override fun getApiVersion(): Int {
        return XposedBridge.getXposedVersion()
    }

    override fun getFrameworkName(): String {
        return runCatching {
            XposedBridge::class.java.getDeclaredField("TAG").get(null) as String
        }.getOrDefault("Xposed").filter {
            it == ' ' || it.isLetterOrDigit()
        }
    }

    override fun getFrameworkVersion(): String {
        return "Unknown"
    }

    override fun getFrameworkVersionCode(): Long {
        return -1L
    }

    override fun getFrameworkProperties(): Long {
        return 0x00
    }

    override fun getModuleSource(): String {
        return moduleSource ?: throw IllegalStateException("Module source is not set!")
    }

    override fun getModuleAppInfo(): ApplicationInfo {
        return XposedKit.parsedModulePackage.applicationInfo
    }

    override fun deoptimize(member: Member): Boolean {
        if (Modifier.isNative(member.modifiers)) {
            XLog.w(TAG, "Deoptimizing native method is not supported: $member")
            return false
        }

        if (member !is Constructor<*> && member !is Method) {
            throw IllegalArgumentException("Member must be a Constructor or Method!")
        }

        return XposedBridge::class.reflect {
            // 尝试反射LSPosed框架额外提供的方法
            method("deoptimizeMethod")?.runCatching {
                invoke(null, member)
            }?.isSuccess ?: false
        }
    }

    override fun hook(
        member: Member,
        type: HookType,
        priority: Int,
        callback: InvokeCallback
    ): HookHandle {
        val unhook = XposedBridge.hookMethod(
            member,
            MethodHook(priority, type, callback)
        )

        return HookHandle(
            member,
            type,
            priority,
            callback
        ) {
            unhook.unhook()
        }
    }

    override fun hookClassInitializer(
        clazz: Class<*>,
        type: HookType,
        callback: InvokeCallback
    ): HookHandle {
        class ClassInitializer : Member {
            override fun getDeclaringClass() = clazz

            override fun getModifiers() = 0x0

            override fun getName() = "<clinit>"

            override fun isSynthetic() = false
        }

        if (!NativeBridge.isLoaded) {
            throw IllegalStateException("NativeBridge is not available!")
        }

        return hook(
            NativeBridge.FindClassInitializer(clazz) ?: throw IllegalArgumentException("Cannot find class initializer for $clazz"),
            type,
            InvokeCallback.PRIORITY_NORMAL,
            callback
        ).copy(member = ClassInitializer())
    }

    override fun invokeOriginal(member: Member, obj: Any?, vararg args: Any?): Any? {
        return XposedBridge.invokeOriginalMethod(member, obj, args)
    }

    override fun invokeSpecial(member: Member, obj: Any, vararg args: Any?): Any? {
        if (member !is Constructor<*> && member !is Method) {
            throw IllegalArgumentException("Member must be a Constructor or Method!")
        }

        if (!NativeBridge.isLoaded) {
            var result: Any? = obj
            val builder = StringBuilder("NativeBridge is not available!")
            if (member is Method) {
                builder.append(" The method will be invoked directly instead.")
                result = member.invoke(obj, *args)
            }

            XLog.w(TAG, builder.toString())
            return result
        }

        return NativeBridge.CallNonvirtualMethod(member, obj, *args)
    }

    override fun getRemotePrefs(name: String): SharedPreferences {
        return XSharedPreferences(
            XposedKit.modulePackageName,
            name
        ).apply {
            if (all.isNotEmpty()) return@apply
            makeWorldReadable()
            reload()
        }
    }

    override fun getRemoteFile(name: String): ParcelFileDescriptor {
        throw FileNotFoundException("RemotePreferences is not implemented in Xposed API!")
    }

    override fun getRemoteFiles(): List<String> {
        return emptyList()
    }

    override fun printLog(log: XLog.LogRecord) {
        val level = when (log.priority) {
            Log.VERBOSE -> "VERBOSE"
            Log.DEBUG -> "DEBUG"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            Log.ERROR -> "ERROR"
            Log.ASSERT -> "ASSERT"
            else -> ""
        }
        val values = mapOf(
            "level" to level,
            "level_short" to (level.firstOrNull() ?: "").toString(),
            "module_package" to XposedKit.modulePackageName,
            "tag" to log.tag,
            "message" to log.message
        )
        var formated = "%(\\w+)%".toRegex().replace(XLog.pattern) {
            values[it.groupValues[1]] ?: it.value
        }
        if (log.exception != null) {
            formated += "\n${Log.getStackTraceString(log.exception)}"
        }

        XposedBridge.log(formated)
    }

    @Keep
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        XLog.v(TAG, "Zygote initialized: modulePath=${param.modulePath}")
        moduleSource = param.modulePath
    }

    @Keep
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        XLog.v(TAG, "Package loaded: processName=${param.processName}, packageName=${param.packageName}, isFirstApplication=${param.isFirstApplication}")

        if (param.packageName == XposedKit.modulePackageName) {
            XLog.d(TAG, "Skipping module package: ${param.packageName}")
            return
        }

        if (param.processName == "android") {
            XLog.v(TAG, "The process is system_server, calling onSystemServerStarting only")
            val processParam = ProcessLoadedParam(param.processName, true)
            val systemParam = SystemServerStartingParam(param.classLoader)
            XposedKit.withModule {
                onProcessLoaded(processParam)
                onSystemServerStarting(systemParam)
            }
            return
        }

        if (param.isFirstApplication) {
            XLog.v(TAG, "The process is first application, means its a newly started process, calling onProcessLoaded")
            XposedKit.prepare()
            val processParam = ProcessLoadedParam(param.processName, false)
            XposedKit.withModule { onProcessLoaded(processParam) }
        }

        val packageParam = PackageLoadedParam(
            param.processName,
            param.packageName,
            param.classLoader,
            param.appInfo,
            null,
            param.isFirstApplication
        )
        XposedKit.withModule { onPackageLoaded(packageParam) }
    }

    private class MethodHook(
        priority: Int,
        private val type: HookType,
        private val callback: InvokeCallback
    ) : XC_MethodHook(priority) {
        @Keep
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (type != HookType.BEFORE) return
            executeCallback(param)
        }

        @Keep
        override fun afterHookedMethod(param: MethodHookParam) {
            if (type != HookType.AFTER) return
            executeCallback(param)
        }

        private fun executeCallback(param: MethodHookParam) {
            val info = InvokeInfo(
                param.method,
                param.thisObject,
                param.args,
                param.result,
                param.throwable
            )
            runCatching { callback(info) }.onFailure {
                param.throwable = it
                return
            }
            if (info.cancelled) {
                param.result = info.result
            }
        }
    }
}