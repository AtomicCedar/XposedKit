# 保留各个 Engine 的实现类名称 供框架定位实现类
-keepnames class * extends cc.meteormc.xposedkit.engine.Engine
# 防止删除或混淆 Engine 接口类成员 以避免破坏继承关系（此规则可传播至子类）
-keepclassmembers,allowoptimization class cc.meteormc.xposedkit.engine.Engine { *; }
# 保留 Engine 实现类的构造函数以便框架创建入口
-keepclassmembers,allowoptimization class * extends cc.meteormc.xposedkit.engine.Engine { <init>(...); }

# 保留模块的实现类
-keep,allowoptimization,allowobfuscation class * extends cc.meteormc.xposedkit.XposedModule
# 禁止删除模块基类的成员（此规则可传播至子类）
-keepclassmembers,allowoptimization,allowobfuscation class cc.meteormc.xposedkit.XposedModule { *; }
# 保留模块实现类的构造函数 供内部获取模块实例
-keepclassmembers,allowoptimization class * extends cc.meteormc.xposedkit.XposedModule { <init>(); }

-dontwarn android.app.AndroidAppHelper
-dontwarn android.content.res.XModuleResources
-dontwarn android.content.res.XResForwarder
-dontwarn android.content.res.XResources
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.api.**

-dontwarn android.app.ActivityThread
-dontwarn android.app.ActivityThread$ApplicationThread
-dontwarn android.app.ActivityThread$ActivityClientRecord
-dontwarn android.content.pm.PackageParser
-dontwarn android.content.pm.PackageParser$Package
-dontwarn android.content.pm.PackageParser$Component
-dontwarn android.content.pm.PackageParser$Activity
-dontwarn android.content.pm.PackageParser$Service
-dontwarn android.content.pm.PackageParser$Provider
-dontwarn android.content.pm.PackageParser$Instrumentation
-dontwarn android.content.pm.PackageParser$Permission
-dontwarn android.content.pm.PackageParser$PackageParserException
-dontwarn android.content.res.ApkAssets