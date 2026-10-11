package cc.meteormc.xposedkit.engine

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.XposedKit.TAG
import cc.meteormc.xposedkit.engine.LSPosed.HookIdentifier.Companion.toId
import cc.meteormc.xposedkit.hook.HookHandle
import cc.meteormc.xposedkit.hook.HookType
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.hook.InvokeInfo
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.param.HotReloadingParam
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.param.ProcessLoadedParam
import cc.meteormc.xposedkit.param.SystemServerStartingParam
import cc.meteormc.xposedkit.util.AtomicBooleanDelegate
import cc.meteormc.xposedkit.util.WeakDelegate
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.O)
class LSPosed : Engine, XposedModule() {
    private lateinit var processName: String
    private var isHotReloading by AtomicBooleanDelegate(false)
    private var systemServerClassLoader by WeakDelegate<ClassLoader>()
    private var previousHookHandles = ConcurrentHashMap<Member, MutableList<XposedInterface.HookHandle>>()
    private val appPackages = mutableMapOf<String, RuntimePackage>()

    private data class RuntimePackage(
        val classLoader: WeakReference<ClassLoader>,
        val appInfo: ApplicationInfo,
        val isFirstPackage: Boolean,
        var context: WeakReference<Application> = WeakReference(null)
    )

    private fun registerRuntimePackage(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo,
        isFirstPackage: Boolean
    ) {
        appPackages[packageName] = RuntimePackage(
            WeakReference(classLoader),
            appInfo,
            isFirstPackage
        )
        XposedKit.registerAppAttachListener(packageName) {
            appPackages[packageName]?.context = WeakReference(it)
        }
    }

    override fun getModuleSource(): String {
        return moduleApplicationInfo.sourceDir
    }

    override fun getModuleAppInfo(): ApplicationInfo {
        return moduleApplicationInfo
    }

    override fun deoptimize(member: Member): Boolean {
        if (member !is Executable) {
            throw IllegalArgumentException("Member must be an Executable in LSPosed framework")
        }

        return deoptimize(member)
    }

    override fun hook(
        member: Member,
        type: HookType,
        priority: Int,
        callback: InvokeCallback
    ): HookHandle {
        if (member !is Executable) {
            throw IllegalArgumentException("Member must be an Executable in LSPosed framework")
        }

        fun XposedInterface.HookHandle.buildHandle(): HookHandle {
            return HookHandle(
                member,
                type,
                priority,
                callback
            ) {
                this.unhook()
            }
        }

        val hooker = InterceptHooker(type, callback)
        val identifier = HookIdentifier(type, priority)
        if (isHotReloading) {
            // 当热重载时 如果存在与之前相同参数的HookHandle
            // 则可以认为它们是同一个钩子（哪怕实际上可能不是同一个）
            // 所以可以直接替换
            val previousHandle = previousHookHandles[member]?.let {
                it.indexOfFirst { handle ->
                    val id = handle.id ?: return@indexOfFirst false
                    HookIdentifier.fromId(id) == identifier
                }.takeIf { idx ->
                    idx in it.indices
                }?.let { idx ->
                    it.removeAt(idx)
                }
            }

            // 如果找到了之前的HookHandle 则替换为新的Hooker
            // 如果没找到 说明可能是新的hook 那么就走下面的逻辑创建钩子
            if (previousHandle != null) {
                return previousHandle.replaceHook(hooker).buildHandle()
            }
        }

        return hook(member)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .setPriority(identifier.priority)
            .run { if (apiVersion >= 102) setId(identifier.toId()) else this }
            .intercept(hooker)
            .buildHandle()
    }

    override fun hookClassInitializer(
        clazz: Class<*>,
        type: HookType,
        callback: InvokeCallback
    ): HookHandle {
        val hooker = InterceptHooker(type, callback, true)
        var handle: XposedInterface.HookHandle? = null
        if (isHotReloading) {
            handle = previousHookHandles.entries
                .firstOrNull { (key, _) -> key.name == "<clinit>" && key.declaringClass == clazz }
                ?.value
                ?.removeLastOrNull()
                ?.replaceHook(hooker)
        }

        if (handle == null) {
            handle = hookClassInitializer(clazz)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker)
        }

        return HookHandle(
            handle.executable,
            type,
            InvokeCallback.PRIORITY_NORMAL,
            callback
        ) {
            handle.unhook()
        }
    }

    override fun invokeOriginal(member: Member, obj: Any?, vararg args: Any?): Any? {
        return member.toInvoker()
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(obj, *args)
    }

    override fun invokeSpecial(member: Member, obj: Any, vararg args: Any?): Any? {
        return member.toInvoker().invokeSpecial(obj, *args)
    }

    override fun getRemotePrefs(name: String): SharedPreferences {
        return getRemotePreferences(name)
    }

    override fun getRemoteFile(name: String): ParcelFileDescriptor {
        return openRemoteFile(name)
    }

    override fun getRemoteFiles(): List<String> {
        return listRemoteFiles().toList()
    }

    override fun printLog(
        log: XLog.LogRecord
    ) {
        if (log.exception != null) {
            log(log.priority, log.tag, log.message, log.exception)
        } else {
            log(log.priority, log.tag, log.message)
        }
    }

    @Keep
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedKit.init(this)
        XposedKit.prepare()
        XLog.v(TAG, "Module loaded: processName=${param.processName}, isSystemServer=${param.isSystemServer}")

        processName = param.processName
        val processParam = ProcessLoadedParam(processName, param.isSystemServer)
        XposedKit.withModule { onProcessLoaded(processParam) }
    }

    @Keep
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        XLog.v(TAG, "Package ready: packageName=${param.packageName}, isFirstPackage=${param.isFirstPackage}")
        val packageParam = PackageLoadedParam(
            processName,
            param.packageName,
            param.classLoader,
            param.applicationInfo,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) param.appComponentFactory else null,
            param.isFirstPackage
        )
        XposedKit.withModule { onPackageLoaded(packageParam) }
        registerRuntimePackage(
            param.packageName,
            param.classLoader,
            param.applicationInfo,
            param.isFirstPackage
        )
    }

    @Keep
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        XLog.v(TAG, "System server starting")
        val systemParam = SystemServerStartingParam(param.classLoader)
        XposedKit.withModule { onSystemServerStarting(systemParam) }
        systemServerClassLoader = param.classLoader
    }

    @Keep
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        XLog.v(TAG, "Hot reloading: extras=${param.extras}")
        val reloadParam = HotReloadingParam(
            processName,
            param.extras,
            null
        )
        if (!XposedKit.withModule { onHotReloading(reloadParam) }) {
            return false
        }

        // !!IMPORTANT
        // 下面序列化相关的代码必须严格使用非模块ClassLoader加载的类型
        // 注意克制使用kt语法 避免编译器生成kt类
        // 否则会抛异常 且无法在另一个ClassLoader中直接反序列化

        val packages = HashMap<String, Map<String, Any?>>()
        for (entry in appPackages) {
            val pkg = entry.value
            val classLoader = pkg.classLoader.get() ?: continue
            packages[entry.key] = HashMap<String, Any?>().apply {
                put("classLoader", classLoader)
                put("appInfo", pkg.appInfo)
                put("isFirstPackage", pkg.isFirstPackage)
                put("context", pkg.context.get())
            }
        }

        param.setSavedInstanceState(
            HashMap<String, Any?>().apply {
                put("savedData", reloadParam.savedData)
                put("systemServerClassLoader", systemServerClassLoader)
                put("packages", packages)
            }
        )

        // 在此处完成清理操作
        appPackages.clear()
        NativeBridge.Cleanup()
        return true
    }

    @Keep
    @Suppress("UNCHECKED_CAST")
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        XposedKit.init(this)
        XLog.v(TAG, "Hot reloaded: extras=${param.extras}, processName=${param.processName}, isSystemServer=${param.isSystemServer}")

        processName = param.processName
        val oldHookHandles = param.oldHookHandles
        val state = param.savedInstanceState as Map<String, Any?>
        val savedData = state["savedData"]
        val systemServerClassLoader = state["systemServerClassLoader"] as? ClassLoader?
        val packages = state["packages"] as? Map<String, Map<String, Any?>>?
        if (packages == null) {
            XLog.w(TAG, "No packages found in savedInstanceState, skipping onHotReloaded")
            return
        }

        val oldHookSize = oldHookHandles.size
        XLog.v(TAG, "Found $oldHookSize old hook handles from previous module code")

        isHotReloading = true
        previousHookHandles.clear()
        previousHookHandles.putAll(oldHookHandles.groupBy { it.executable }.mapValues { it.value.toMutableList() })

        XposedKit.prepare()
        XposedKit.withModule {
            val param = ProcessLoadedParam(
                processName,
                param.isSystemServer,
                ProcessLoadedParam.HotReloadInfo(
                    param.extras,
                    savedData
                )
            )
            onProcessLoaded(param)
        }

        if (packages.isNotEmpty())
            XLog.v(TAG, "Loading new hooks for ${packages.size} packages")
        packages.forEach {
            val packageName = it.key
            val wrapper = it.value
            val classLoader = wrapper["classLoader"] as ClassLoader
            val appInfo = wrapper["appInfo"] as ApplicationInfo
            val isFirstPackage = wrapper["isFirstPackage"] as Boolean
            val context = wrapper["context"] as Application?
            if (context != null) {
                XposedKit.attachedApplications[packageName] = context
            }

            registerRuntimePackage(
                packageName,
                classLoader,
                appInfo,
                isFirstPackage
            )

            XposedKit.withModule {
                val param = PackageLoadedParam(
                    processName,
                    packageName,
                    classLoader,
                    appInfo,
                    null,
                    isFirstPackage
                )
                onPackageLoaded(param)
            }
        }

        if (systemServerClassLoader != null) {
            XLog.v(TAG, "Loading new hooks for system server")
            this.systemServerClassLoader = systemServerClassLoader
            XposedKit.withModule {
                val param = SystemServerStartingParam(systemServerClassLoader)
                onSystemServerStarting(param)
            }
        }

        isHotReloading = false
        previousHookHandles.values.flatten().apply {
            XLog.v(TAG, "Replaced ${oldHookSize - size} old hook handles")
            if (isEmpty()) {
                XLog.v(TAG, "All old hook handles have been replaced")
                return
            }

            XLog.v(TAG, "$size old hook handles remain that were not replaced:\n" +
                    joinToString("", limit = 5, truncated = "\t... ${size - 5} more") { "\t- ${it.executable}<${it.id}>\n" })
        }.forEach {
            // 清除剩余的没有被替换的旧Hook
            it.unhook()
        }
        previousHookHandles.clear()
    }

    private fun Member.toInvoker(): XposedInterface.Invoker<out XposedInterface.Invoker<*, out Executable>, out Executable> {
        return when (this) {
            is Method -> getInvoker(this)
            is Constructor<*> -> getInvoker(this)
            else -> {
                throw IllegalArgumentException("Member must be an Executable in LSPosed framework")
            }
        }
    }

    private class InterceptHooker(
        private val type: HookType,
        private val callback: InvokeCallback,
        private val ignoreResult: Boolean = false
    ) : XposedInterface.Hooker {
        @Keep
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val member: Member = chain.executable
            return when (this.type) {
                HookType.BEFORE -> {
                    val args = chain.args.toTypedArray()
                    val info = InvokeInfo(
                        member,
                        chain.thisObject,
                        args,
                        null,
                        null
                    )
                    callback(info)
                    if (info.exception != null) {
                        throw info.exception
                    } else if (!ignoreResult && info.cancelled) {
                        info.result
                    } else {
                        chain.proceed(args)
                    }
                }
                HookType.AFTER -> {
                    val result = chain.runCatching { proceed() }
                    val info = InvokeInfo(
                        member,
                        chain.thisObject,
                        chain.args.toTypedArray(),
                        result.getOrNull(),
                        result.exceptionOrNull()
                    )
                    callback(info)
                    if (info.exception != null) {
                        throw info.exception
                    }

                    if (ignoreResult) result.getOrNull()
                    else info.result
                }
            }
        }
    }

    private data class HookIdentifier(
        val type: HookType,
        val priority: Int,
        val random: String = generateRandomString()
    ) {
        companion object {
            private val sr = SecureRandom()

            fun fromId(id: String): HookIdentifier? {
                val split = id.split(':')
                val type = runCatching { split.getOrNull(0)?.let { HookType.valueOf(it) } }.getOrNull() ?: return null
                val priority = split.getOrNull(1)?.toIntOrNull() ?: return null
                val random = split.getOrNull(2).orEmpty().ifBlank { generateRandomString() }
                return HookIdentifier(type, priority, random)
            }

            fun HookIdentifier.toId(): String {
                return "${type.name}:$priority:$random"
            }

            private fun generateRandomString(): String {
                val bytes = ByteArray(16)
                sr.nextBytes(bytes)
                return bytes.joinToString("") { "%02x".format(it) }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HookIdentifier) return false

            if (type != other.type) return false
            if (priority != other.priority) return false

            return true
        }

        override fun hashCode(): Int {
            var result = 1
            result = 31 * result + type.ordinal
            result = 31 * result + priority
            return result
        }
    }
}