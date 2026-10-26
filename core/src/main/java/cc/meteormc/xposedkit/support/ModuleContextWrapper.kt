package cc.meteormc.xposedkit.support

import android.app.Activity
import android.app.ActivityThread
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.get
import cc.meteormc.xposedkit.put
import cc.meteormc.xposedkit.reflect

@ExperimentalStdlibApi
open class ModuleContextWrapper(
    base: Context,
    val mResources: Resources = XposedKit.createModuleResources(
        base.resources.displayMetrics,
        base.resources.configuration,
        base.resources.assets
    ),
    val mThemeId: Int = XposedKit.moduleAppInfo.theme
) : ContextWrapper(base) {
    companion object {
        private const val TAG = "ModuleContextWrapper"

        private const val MAX_LOADER_NAME = 20

        val ContextWrapper.hostContext: Context?
            get() {
                if (this is ModuleContextWrapper) {
                    return baseContext
                }

                return findContextImpl(this)
            }

        private val sImplClass by lazy {
            Context::class.java
                .classLoader!!
                .reflect("android.app.ContextImpl")
                ?.type
        }

        private fun findContextImpl(wrapper: Context): Context? {
            var contextImpl = wrapper
            while (contextImpl is ContextWrapper) {
                contextImpl = contextImpl.baseContext
            }

            return contextImpl.takeIf { sImplClass?.isInstance(it) == true }
        }
    }

    private var mImpl: Context? = null
    private var mThread: ActivityThread? = null
    private var mProxyActivity: ComponentName? = null
    private val mInflater by lazy {
        LayoutInflater.from(baseContext).cloneInContext(this)
    }
    private val mTheme by lazy {
        resources.newTheme()
    }

    init {
        findContextImpl(base)?.let {
            this.mImpl = it
            this.mThread = sImplClass?.reflect {
                field("mMainThread")?.get(it) as? ActivityThread?
            }
        }
    }

    override fun getAssets(): AssetManager {
        return mResources.assets
    }

    override fun getResources(): Resources {
        return mResources
    }

    override fun getApplicationContext(): Context {
        return baseContext
    }

    override fun setTheme(resid: Int) {

    }

    override fun getTheme(): Resources.Theme {
        if (mThemeId <= 0) return super.theme
        mTheme.applyStyle(mThemeId, true)
        return mTheme
    }

    override fun getClassLoader(): ClassLoader {
        return XposedKit.engine.javaClass.classLoader!!
    }

    override fun getPackageName(): String {
        return XposedKit.modulePackageName
    }

    override fun getPackageResourcePath(): String? {
        return XposedKit.moduleSource
    }

    override fun getPackageCodePath(): String? {
        return XposedKit.moduleSource
    }

    override fun getSystemService(name: String): Any? {
        if (name == LAYOUT_INFLATER_SERVICE) {
            return mInflater
        }

        return super.getSystemService(name)
    }

    override fun startActivity(intent: Intent) {
        startActivity(intent, null)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        XLog.d(TAG, "startActivity: ${intent.component}")
        val targetActivityInfo = XposedKit.modulePackageInfo.activities?.firstOrNull {
            intent.component == ComponentName(it.packageName, it.name)
        }

        if (targetActivityInfo == null) {
            XLog.d(TAG, "startActivity: target activity not found, fallback to default implementation")
            super.startActivity(intent, options)
            return
        }

        val runningActivities = ActivityThread::class.reflect {
            val records = field("mActivities")?.get<Map<*, ActivityThread.ActivityClientRecord>>(mThread)
                ?.values
                ?: return@reflect null
            ActivityThread.ActivityClientRecord::class.reflect {
                records.filter {
                    field("paused")?.get<Boolean>(it) == false
                }.mapNotNull {
                    field("activity")?.get<Activity>(it)?.javaClass?.name
                }
            }
        } ?: throw IllegalStateException("Failed to get running activities from ActivityThread!")

        fun List<ActivityInfo>.filterVaild(): List<ActivityInfo>? {
            return filter {
                // 过滤activity-alias
                if (it.targetActivity != null) return@filter false
                // 过滤已经在运行的Activity
                if (it.name in runningActivities) return@filter false
                // 过滤launchMode与targetInfo不一致的Activity
                if (it.launchMode != targetActivityInfo.launchMode) return@filter false
                return@filter true
            }.ifEmpty { null }?.apply {
                XLog.d(TAG, "Found $size valid activities for ${targetActivityInfo.name}:\n" +
                        joinToString(separator = "\n", limit = 4, truncated = "\nand more...") { it.name })
            }
        }

        val pm = baseContext.packageManager
        val basePackage = baseContext.packageName
        if (mProxyActivity == null) {
            val pendingActivities = pm.queryIntentActivities(
                // 先查Launcher Activity
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(basePackage)
                },
                PackageManager.MATCH_ALL
            ).map { it.activityInfo }.filterVaild() ?: pm.queryIntentActivities(
                // 再查主Activity
                Intent(Intent.ACTION_MAIN).apply {
                    setPackage(basePackage)
                },
                PackageManager.MATCH_ALL
            ).map { it.activityInfo }.filterVaild() ?: pm.getPackageInfo(
                // 最后查找全部
                basePackage,
                PackageManager.GET_ACTIVITIES
            ).activities?.toList()?.filterVaild() ?: throw IllegalStateException(
                "Failed to find any valid Activity in package '$basePackage'!"
            )
            val info = pendingActivities.firstOrNull {
                // 优先取exported=true并且未设置taskAffinity的Activity
                it.exported && it.taskAffinity == null
            } ?: pendingActivities.first()
            mProxyActivity = ComponentName(info.packageName, info.name)
            XLog.d(TAG, "startActivity: proxy activity is ${mProxyActivity?.className}, real activity is ${intent.component?.className}")
        }

        val newIntent = Intent()
        newIntent.flags = intent.flags
        newIntent.putExtra("is_proxy_activity", true)
        newIntent.putExtra("intent_wrapper", intent)
        newIntent.putExtra("target_activity_info", targetActivityInfo)
        newIntent.setComponent(mProxyActivity)

        val activityThread = mThread!!
        var instrumentation = activityThread.instrumentation
        if (instrumentation !is InstrumentationWrapper) {
            @Suppress("DEPRECATION")
            instrumentation = object : InstrumentationWrapper(instrumentation) {
                override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?) {
                    prepareActivity(activity, icicle)
                    super.callActivityOnCreate(activity, icicle)
                }

                override fun callActivityOnCreate(activity: Activity?, icicle: Bundle?, persistentState: PersistableBundle?) {
                    prepareActivity(activity, icicle)
                    super.callActivityOnCreate(activity, icicle, persistentState)
                }

                override fun newActivity(
                    cl: ClassLoader?,
                    className: String?,
                    intent: Intent?
                ): Activity? {
                    return tryNewModuleActivity(intent) ?: super.newActivity(cl, className, intent)
                }

                override fun newActivity(
                    clazz: Class<*>?,
                    context: Context?,
                    token: IBinder?,
                    application: Application?,
                    intent: Intent?,
                    info: ActivityInfo?,
                    title: CharSequence?,
                    parent: Activity?,
                    id: String?,
                    lastNonConfigurationInstance: Any?
                ): Activity? {
                    return tryNewModuleActivity(intent) ?: super.newActivity(
                        clazz,
                        context,
                        token,
                        application,
                        intent,
                        info,
                        title,
                        parent,
                        id,
                        lastNonConfigurationInstance
                    )
                }

                override fun onException(obj: Any?, e: Throwable?): Boolean {
                    if (obj !is Activity) return super.onException(obj, e)

                    val intent = obj.intent
                    val component = intent.component ?: return false
                    if (!this.isProxyActivity(intent) && component.packageName != this@ModuleContextWrapper.packageName) {
                        return super.onException(obj, e)
                    }

                    XLog.e(
                        TAG,
                        "Unable to start activity '${this.getRealIntent(intent)?.component ?: component}'" +
                                " from module '${this@ModuleContextWrapper.packageName}'" +
                                " in package '${baseContext.packageName}'",
                        e
                    )
                    return true
                }

                private fun prepareActivity(activity: Activity?, icicle: Bundle?) {
                    if (activity == null) return

                    val intent = activity.intent
                    if (!this.isProxyActivity(intent)) return
                    XLog.d(TAG, "prepareActivity: preparing activity ${activity.javaClass.name} for module context")

                    val moduleContext = this@ModuleContextWrapper
                    icicle?.classLoader = moduleContext.classLoader

                    activity.intent = this.getRealIntent(intent) ?: intent
                    ContextThemeWrapper::class.reflect {
                        field("mBase")?.put(activity, moduleContext)
                        field("mResources")?.put(activity, mResources)
                    }

                    // 配置Activity的主题 如果没有可用主题则使用默认主题 (一般取决于宿主)
                    val targetActivityInfo = this.getTargetActivityInfo(intent)
                    val themeId = targetActivityInfo?.theme ?: 0
                    // 优先使用模块自定义的主题
                    if (themeId != 0) {
                        activity.setTheme(themeId)
                    } else if (moduleContext.mThemeId > 0) {
                        // 其次跟随ModuleContextWrapper设置的主题
                        val themeId = moduleContext.mThemeId
                        activity.setTheme(themeId)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            activity.theme = moduleContext.theme
                        } else {
                            ContextThemeWrapper::class.reflect {
                                field("mTheme")?.put(activity, moduleContext.theme)
                            }
                        }
                    }
                }

                private fun tryNewModuleActivity(intent: Intent?): Activity? {
                    if (intent == null) return null
                    if (!this.isProxyActivity(intent)) return null
                    XLog.d(TAG, "tryNewModuleActivity: current component is ${intent.component}")

                    val realContext = this@ModuleContextWrapper
                    val realClassLoader = realContext.classLoader
                    val realIntent = this.getRealIntent(intent) ?: return null
                    val realComponent = realIntent.component ?: return null
                    XLog.d(TAG, "tryNewModuleActivity: real component is $realComponent")

                    val activityInfo = this.getTargetActivityInfo(intent)
                    val className = activityInfo?.targetActivity ?: activityInfo?.name ?: realComponent.className
                    return try {
                        var loaderName = realClassLoader.toString()
                        if (loaderName.length > MAX_LOADER_NAME) {
                            loaderName = loaderName.take(MAX_LOADER_NAME) + "..."
                        }

                        XLog.d(TAG, "tryNewModuleActivity: trying use classloader '$loaderName' to load activity '$className'")
                        super.newActivity(
                            realClassLoader,
                            className,
                            realIntent
                        )
                    } catch (e: ClassNotFoundException) {
                        XLog.e(
                            TAG,
                            "Failed to find Activity '$className'" +
                                    " from module '${realContext.packageName}'" +
                                    " in package '${baseContext.packageName}'",
                            e
                        )
                        null
                    }
                }

                private fun isProxyActivity(intent: Intent): Boolean {
                    return intent.getBooleanExtra("is_proxy_activity", false)
                }

                private fun getRealIntent(intent: Intent): Intent? {
                    return intent.getParcelableExtra("intent_wrapper")
                }

                private fun getTargetActivityInfo(intent: Intent): ActivityInfo? {
                    return intent.getParcelableExtra("target_activity_info")
                }
            }

            ActivityThread::class.reflect {
                field("mInstrumentation")?.apply {
                    put(activityThread, instrumentation)
                    XLog.d(TAG, "startActivity: instrumentation replaced ${this[activityThread].javaClass}")
                }
            }
        }

        XLog.d(TAG, "startActivity: calling execStartActivity with component ${newIntent.component}")
        val result = Instrumentation::class.reflect {
            method(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType!!,
                Bundle::class.java
            )!!
        }.call<Instrumentation.ActivityResult?>(
            instrumentation,
            this,
            activityThread.applicationThread,
            null,
            null,
            newIntent,
            -1,
            options
        ) ?: return
        XLog.d(TAG, "startActivity: execStartActivity result code=${result.resultCode}, data=${result.resultData}")
    }

    override fun startActivities(intents: Array<Intent>) {
        startActivities(intents, null)
    }

    override fun startActivities(intents: Array<Intent>, options: Bundle?) {
        throw UnsupportedOperationException("startActivities is not supported in ModuleContextWrapper")
    }

    fun setProxyActivity(component: ComponentName) {
        mProxyActivity = component
    }
}