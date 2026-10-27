package cc.meteormc.xposedkit.support

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.StringBlock
import android.content.res.XmlBlock
import android.content.res.XmlResourceParser
import android.util.TypedValue
import cc.meteormc.xposedkit.allocate
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.new
import cc.meteormc.xposedkit.put
import cc.meteormc.xposedkit.reflect
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Method
import kotlin.math.abs

/*
# RuntimeAttributeSet：实现原理与代码导读

这份说明对应工作区在 **2026-10-28** 的实现，供之后阅读和维护。代码入口是：

- Kotlin：`core/src/main/java/cc/meteormc/xposedkit/support/RuntimeAttributeSet.kt`
- JNI/AXML：`native/src/main/cpp/xposedkit.cxx` 中的 `CreateXmlParseState`
- 动态符号解析：项目内的 xDL，源码在 `native/src/main/cpp/external/xdl`

## 1. 要解决什么问题

`android.util.AttributeSet` 看起来只是属性集合，但很多 Android 布局代码不会只调用 `getAttributeValue()`。例如 `ViewGroup.LayoutParams` 和 ConstraintLayout 会把传入对象当成已编译 XML 的属性集，交给 `Context.obtainStyledAttributes()` / `Resources.obtainAttributes()`，再按资源 ID 从 `TypedArray` 读取尺寸、枚举、引用等类型。

`XmlBlock.Parser` 同时实现了 `XmlResourceParser` 和 `AttributeSet`。框架某些路径还会直接把 `AttributeSet` 转成 `XmlBlock.Parser`。所以本实现最终返回的是一个真实 `XmlBlock.Parser` 对象；它内部的 native parse state 指向一棵由内存 AXML 构造出来的 `ResXMLTree`。

整体数据流：

```mermaid
flowchart LR
    A[Context + Entry namespace/name/value] --> B[Kotlin: 查 attr ID]
    B --> C[Kotlin: 把文本编成 TypedValue data/type]
    C --> D[JSON: 传给 JNI]
    D --> E[Native: 建 UTF-8 字符串池和资源映射]
    E --> F[Native: 建 START_TAG / 属性 / END_TAG]
    F --> G[libandroidfw: ResXMLTree.setTo]
    G --> H[XmlBlock.Parser + StringBlock]
    H --> I[AttributeSet / TypedArray / LayoutParams]
```

## 2. Kotlin 入口 `of(context, vararg entries)`

`Entry` 只提供 `namespace`、`name` 和文本 `value`。调用者无需把 `R.attr` ID 或 `TypedValue` 类型写死在代码里。

对每个条目，`of()` 依次做这些事：

1. `resolveAttribute()` 根据 namespace 和当前 `Context` 的资源表查属性 ID。
2. `compileValue()` 把文本值转成 Android `TypedValue` 的 `(data, type)`。
3. 把属性名、namespace、资源 ID、raw 字符串及 typed value 放入 JSON。
4. 调用 `NativeBridge.CreateXmlParseState()` 创建 native XML 状态。
5. 从该状态取得字符串池，创建一个非拥有型 `StringBlock`，放入新分配的 `XmlBlock.mStrings`。
6. 反射调用隐藏的 `XmlBlock.Parser` 构造函数，并把 parser 推进到开始标签。
7. 只在事件确实为 `START_TAG` 时返回；否则关闭 parser 并抛出异常。

每个 parser 都拥有自己的 fake `XmlBlock`。这是必要的：`Parser` 通过所属 block 读取字符串。如果多个 parser 共用一个 block、但 block 的 `mStrings` 被改成另一个 XML 的字符串池，旧 parser 的索引就会错误地指向新字符串池。

### 为什么要把 `mOpenCount` 设成 `Int.MIN_VALUE`

`XmlBlock.Parser` 构造时会增加所属 block 的打开计数，`Parser.close()` 会减少它。fake block 是通过 `allocate()` 分配的，其他字段没有经过正常构造；如果打开计数归零，`XmlBlock` 可能继续执行关闭 native block 和 string block 的正常逻辑，而这些对象并未按普通 `XmlBlock` 路径建立。

实现先把计数设为很小的值，再让 Parser 构造函数递增它，使 parser 关闭时不会触发 fake block 的零计数清理路径。这个技巧依赖当前框架实现，属于需要随 Android 版本复核的隐藏实现细节。

## 3. 属性 ID：`resolveAttribute()`

`TypedArray` 的 styleable 匹配依赖属性资源 ID，而不只是属性名字符串。

- Android namespace 固定查 `android` 包。
- `http://schemas.android.com/apk/res/<package>` 形式从 URI 中取包名。
- `res-auto` URI 不以 `/res/` 后接包名的形式出现，因此回退到 `context.packageName`，即当前应用资源包。
- 其他未识别 namespace 也按当前 Context 的包查找。

最终调用 `context.resources.getIdentifier(name, "attr", packageName)`。返回 0 时直接抛出 `Resources.NotFoundException`，避免构造一个无法被 `TypedArray` 匹配的属性。

因此传入的 Context 应当能看到这些属性对应的资源表；例如解析 `res-auto` 属性时，最好传目标应用的 Context。

## 4. 文本值编译：`compileValue()`

Android 编译期的 aapt/aapt2 会根据属性声明，把 XML 文本转换成 `Res_value` 的类型与数据。例如 `wrap_content` 会变成整数 `-2`；如果运行时把它原样当作字符串，`TypedArray.getLayoutDimension()` 就无法读取它。

`compileValue()` 用 `(data, type)` 返回结果，顺序是 **data 在前、type 在后**。当前覆盖范围如下：

| 输入 | AXML TypedValue 类型 | data 的含义 |
|---|---|---|
| `true` / `false` | `TYPE_INT_BOOLEAN` | 1 / 0 |
| `@null` | `TYPE_NULL` | 0 |
| `@string/name`、`@id/name` 等 | `TYPE_REFERENCE` | 查到的资源 ID |
| `?attr/name` | `TYPE_ATTRIBUTE` | 查到的属性 ID |
| `#RGB`、`#ARGB`、`#RRGGBB`、`#AARRGGBB` | 颜色整数类型 | 展开后的 32 位颜色 |
| `12dp`、`4sp`、`8px` 等 | `TYPE_DIMENSION` | Android complex dimension 编码 |
| 十进制整数 | `TYPE_INT_DEC` | 整数值 |
| `0x...` | `TYPE_INT_HEX` | 十六进制整数值 |
| 浮点数字面量 | `TYPE_FLOAT` | IEEE-754 float 位模式 |
| 其他普通文本 | `TYPE_STRING` | native 端填入字符串池索引 |

`layout_width` / `layout_height` 的 `wrap_content`、`match_parent`、`fill_parent` 有兜底映射。ConstraintLayout 的 `layout_constraint...="parent"` 兜底映射为整数 0。正常情况下 enum/flag 属性优先通过 `resolveEnum()` 查属性资源表中的映射。

这不是完整 aapt2。比如 `@+id/name` 目前只是去掉 `+` 后尝试查找已有 ID，并不会创建新的资源 ID；主题相关解析、所有资源表达式和百分比字面量也没有完整实现。

### `resolveReference()`

它识别 `@...` 资源引用和 `?...` 主题属性引用。语法中可选包含包名、资源类型和资源名称；解析到 ID 后分别返回 `TYPE_REFERENCE` 或 `TYPE_ATTRIBUTE`。如果资源查不到，它返回 `null`，后续编译逻辑会继续尝试颜色、尺寸、数字或普通字符串。

需要留意：字符串格式正确但 ID 不存在时，当前实现不会在 `resolveReference()` 处抛错，而可能回退成字符串。`@+id` 也没有真正的运行时资源创建步骤。

## 5. 为什么 `enumValues()` 要访问 AssetManager 内部方法

例如：

```xml
android:layout_width="wrap_content"
app:layout_constraintTop_toTopOf="parent"
```

XML 中的 `wrap_content`、`parent` 是人类可读的符号。aapt 编译布局时，会读取对应 `<attr>` 声明里的 enum/flag 表，并把符号转换成整数写进二进制 XML。运行时只有原始字符串时，单靠 `Resources.getIdentifier()` 只能找到“属性本身”的 ID，无法知道该属性下某个符号对应哪个整数。

公开的 `Resources` API 没有提供“给定 attr ID 和枚举名称，返回枚举整数”的通用查询接口。当前实现因此反射调用 `AssetManager` 内部方法：

1. `getStyleAttributes(attributeId)` 取该资源包的键 ID 列表。对 attr 资源而言，enum/flag 项也存放在资源表 bag 中。
2. `getResourceEntryName(key)` 把键 ID 转成符号名，例如 `parent` 或 `wrap_content`。
3. `getResourceBagText(attributeId, key)` 读取该项的值；它会把整数 typed value 转成可读文本，再由 `toIntOrNull()` 转回整数。
4. `resolveEnum()` 对普通 enum 查一个符号；对 `a|b|c` 形式的 flags，把各项整数按位 OR。

所以它访问内部方法，是为了在运行时补做 aapt 本来会做的符号到整数转换。它不是为了读取任意系统状态。

这些方法属于隐藏 API，名称、可访问性和 bag 表示可能随 Android 版本变化。代码在反射或安全检查失败时返回空映射，然后继续尝试常见值兜底或字符串处理。对资源表结构不熟悉的 enum，反射失败就可能导致 typed value 不正确。

## 6. JSON 到 Native 的约定

Kotlin 传给 JNI 的每个对象需要描述以下信息：

- `namespace`：属性 namespace URI 或 JSON null。
- `name`：属性本地名称。
- `nameResId`：由 Context 解析出的 attr ID。
- `rawValue`：原始文本，供 `AttributeSet.getAttributeValue()` 等接口返回。
- `resourceValue`：TypedValue 的 data 部分。
- `valueType`：TypedValue 的 type 部分。

## 7. Native 端如何构造 AXML

`CreateXmlParseState()` 收到 JSON 后，用 JNI 调用 `JSONArray`、`JSONObject` 的方法把 entries 读到 C++ `Entry` 结构体。JNI 字符串通过 UTF-16 转成标准 UTF-8，避免直接使用 JNI modified UTF-8 导致补充平面字符或 NUL 处理不一致。

随后 native 端建立一个最小二进制 XML 文档：

1. **XML 文件头**：标记二进制 XML 类型并预留总长度。
2. **UTF-8 String Pool（chunk `0x0001`）**：保存根标签名 `runtime`、namespace URI、属性名和 raw value。每个字符串有偏移表、UTF-8/UTF-16 长度前缀，并按 AXML 要求对齐。
3. **Resource Map（chunk `0x0180`）**：以字符串索引为下标，把属性名字符串映射到 `nameResId`。Android native 属性解析器用这张表得到属性资源 ID。
4. **START_TAG（chunk `0x0102`）**：根标签为 `runtime`，每个属性记录 namespace/name/rawValue 索引，以及 8 字节 `Res_value`（size、dataType、data）。
5. **END_TAG（chunk `0x0103`）**：允许 parser 按普通 XML 事件继续前进。

Native 在写入属性前按 `nameResId` 升序稳定排序。Android 的 `AssetManager` 属性匹配会把 XML 属性与 styleable ID 列表顺序扫描匹配；不按资源 ID 排序可能跳过属性。

如果 value type 是 `TYPE_STRING`，typed data 必须是 raw value 在 string pool 中的索引，而不是整数数据；其他类型则使用 Kotlin 编译出的 data。

## 8. 为什么 xDL、ResXMLTree 和内存布局是这样用的

应用的 linker namespace 可能禁止普通 `dlopen("libandroidfw.so")`，即使该库已映射进进程。项目已有 xDL，因此 native 用 `xdl_open("libandroidfw.so", XDL_DEFAULT)` 搜索已加载对象，并通过 `xdl_sym()` 找 Android 13 导出的 `ResXMLTree` 默认构造函数和 `setTo()`。

`ResXMLTree` 继承 `ResXMLParser`，native 在分配区前部 placement-construct 一棵树，并把生成的 AXML 字节放在同一分配区中偏移 4096 的位置。调用 `setTo(data, size, false)` 时不复制数据，所以 AXML 字节必须在 parser 整个生命周期内有效。把对象和字节放进同一分配区，是为了 `XmlBlock.Parser.close()` 最终销毁 `ResXMLParser*` 时能够释放这一块内存。

这条路径依赖目标系统 `libandroidfw.so` 的 C++ ABI 和导出符号名。它针对用户提供的 Android 13 库核对过；其他 Android 版本/厂商实现需要重新核对符号与 ABI。

## 9. `StringBlock` 与 parser 状态

`XmlBlock.Parser` 读取标签名、属性名、namespace 和字符串值时，不只访问 native parser；它也会用所属 block 的 `mStrings` 把 native 返回的字符串池索引转成 Java 字符串。

Kotlin 调用 `XmlBlock.nativeGetStringBlock(parseState)` 取得同一 `ResXMLTree` 内部 `ResStringPool` 的 native 指针，再创建 `StringBlock(pointer, false)`。`false` 表示这个 `StringBlock` 不拥有该 native pool；真正的生命周期由 parser state 管理。然后把它写进刚 allocate 的 `XmlBlock.mStrings`。

parser 构造后，第一次 `next()` 只由 Java `XmlBlock.Parser` 返回 `START_DOCUMENT` 并设置 `mStarted`；第二次才调用 native `next()`，到达第一个 `START_TAG`。`of()` 会跳过这个初始事件并验证最终停在开始标签，因为 `TypedArray` 的 native 读取路径要求 parser 已经位于带属性的标签上。

## 10. 主要限制和维护注意点

- `Entry.value` 是文本输入；并不等于完整 AAPT 运行时编译器。
- `@+id` 当前不创建 ID；无法解析的资源引用可能回退成字符串。
- `enumValues()` 依赖隐藏 API；异常时会静默回退，未知 enum 可能成为 `TYPE_STRING`。
- namespace 到资源包的映射依赖传入 Context 的资源集合与包名。
- parser 对象、fake XmlBlock、StringBlock 和 native parse state 必须保持一致的生命周期；不要把多个 parser 的字符串池放进一个共享 fake block。
- native AXML 格式、Android 资源 ID 顺序和 `libandroidfw` 导出 ABI 都需要在支持新系统版本时复核。
- 工作区源码的 `valueType` / `typedValueType` JSON key 不一致，见第 6 节。

## 参考资料

- [Android 13 `AssetManager.java`](https://android.googlesource.com/platform/frameworks/base/%2B/refs/tags/android-13.0.0_r60/core/java/android/content/res/AssetManager.java)：`getStyleAttributes()`、`getResourceBagText()` 与资源 bag 访问。
- [AOSP `XmlBlock.java`](https://android.googlesource.com/platform/frameworks/base/%2B/1242af060bec63ec3a37512ebbbdbe545bd9f0f7/core/java/android/content/res/XmlBlock.java)：`Parser.next()`、`mStrings`、native parser state 的生命周期。
- [AOSP `StringBlock.java`](https://android.googlesource.com/platform/frameworks/base/%2B/c3bc12c484ef/core/java/android/content/res/StringBlock.java)：基于现有 native string pool 创建非拥有型 `StringBlock`。
- [AOSP `ResXMLParser` 与 `ResXMLTree`](https://android.googlesource.com/platform/frameworks/base/%2B/refs/heads/android13-release/libs/androidfw/include/androidfw/ResourceTypes.h)：parser/tree 类型和二进制 XML 结构。
- [AOSP `AttributeResolution.cpp`](https://android.googlesource.com/platform/frameworks/base/%2B/android11-release/libs/androidfw/AttributeResolution.cpp)：`RetrieveAttributes` 通过 XML 属性资源 ID 与 styleable ID 匹配，并按序扫描。
 */
object RuntimeAttributeSet {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val RESOURCE_NAMESPACE_PREFIX = "http://schemas.android.com/apk/res/"

    private val xmlBlockClass = XmlBlock::class.java
    private val stringsField = xmlBlockClass.reflect { field("mStrings")!! }
    private val openCountField = xmlBlockClass.reflect { field("mOpenCount")!! }
    private val stringBlockMethod = xmlBlockClass.reflect { method("nativeGetStringBlock")!! }

    data class Entry(
        val namespace: String? = null,
        val name: String,
        val value: String
    )

    fun of(context: Context, vararg entries: Entry): XmlResourceParser {
        val json = JSONArray()
        for (entry in entries) {
            val nameResId = resolveAttribute(context, entry)
            val compiledValue = compileValue(context.resources, nameResId, entry.name, entry.value)
            val obj = JSONObject()
            obj.put("namespace", entry.namespace ?: JSONObject.NULL)
            obj.put("name", entry.name)
            obj.put("nameResId", nameResId)
            obj.put("rawValue", entry.value)
            obj.put("resourceValue", compiledValue.first)
            obj.put("valueType", compiledValue.second)
            json.put(obj)
        }

        val parseState = NativeBridge.CreateXmlParseState(json.toString())
        val xmlBlock = xmlBlockClass.allocate().apply {
            val stringBlock = StringBlock(stringBlockMethod.call(null, parseState), false)
            stringsField.put(this, stringBlock)

            // 当这个值归零后会触发 XmlBlock.close() 的内部逻辑
            // 并且因为 XmlBlock 是一个未初始化的不稳定对象 导致这个操作必定会引发崩溃
            // 由于每次创建 XmlBlock.Parser 时都会累加 mOpenCount
            // 为了防止这种情况 我们可以为这个字段设置一个很小的值来避免
            openCountField.put(this, Int.MIN_VALUE)
        }
        val parser = XmlBlock.Parser::class.reflect {
            constructor(
                xmlBlockClass,
                Long::class.java,
                xmlBlockClass
            )!!.new<XmlBlock.Parser>(
                xmlBlock,
                parseState,
                xmlBlock
            )
        }

        var event = parser.next()
        if (event == XmlPullParser.START_DOCUMENT) {
            event = parser.next()
        }
        if (event != XmlPullParser.START_TAG) {
            parser.close()
            val eventName = when (event) {
                XmlPullParser.START_DOCUMENT -> "START_DOCUMENT"
                XmlPullParser.END_DOCUMENT -> "END_DOCUMENT"
                XmlPullParser.END_TAG -> "END_TAG"
                XmlPullParser.TEXT -> "TEXT"
                else -> "UNKNOWN($event)"
            }
            throw IllegalStateException("Expected START_TAG from generated XML, got event $eventName")
        }
        return parser
    }

    private fun resolveAttribute(context: Context, entry: Entry): Int {
        val packageName = when {
            entry.namespace == ANDROID_NAMESPACE -> "android"
            entry.namespace?.startsWith(RESOURCE_NAMESPACE_PREFIX) == true ->
                entry.namespace.removePrefix(RESOURCE_NAMESPACE_PREFIX)
            else -> context.packageName
        }
        val id = context.resources.getIdentifier(entry.name, "attr", packageName)
        if (id == 0) {
            throw Resources.NotFoundException("Unable to resolve attribute $packageName:attr/${entry.name}")
        }
        return id
    }

    private fun compileValue(
        resources: Resources,
        attributeId: Int,
        attributeName: String,
        rawValue: String?
    ): Pair<Int, Int> {
        if (rawValue == null) return 0 to TypedValue.TYPE_NULL
        val value = rawValue.trim()

        if (attributeId != 0) {
            resolveEnum(resources, attributeId, value)?.let {
                return it to TypedValue.TYPE_INT_DEC
            }
        }

        // These XML keywords are common across Android layouts. Keep them
        // usable even on releases that block reflection to AssetManager's
        // hidden enum-bag helpers.
        if (attributeName == "layout_width" || attributeName == "layout_height") {
            when (value) {
                "wrap_content" -> return -2 to TypedValue.TYPE_INT_DEC
                "match_parent", "fill_parent" -> return -1 to TypedValue.TYPE_INT_DEC
            }
        }
        if (attributeName.startsWith("layout_constraint") && value == "parent") {
            return 0 to TypedValue.TYPE_INT_DEC
        }

        when (value.lowercase()) {
            "true" -> return 1 to TypedValue.TYPE_INT_BOOLEAN
            "false" -> return 0 to TypedValue.TYPE_INT_BOOLEAN
            "@null" -> return 0 to TypedValue.TYPE_NULL
        }

        resolveReference(resources, value)?.let { return it }
        parseColor(value)?.let { return it to TypedValue.TYPE_INT_COLOR_ARGB8 }
        parseDimension(value)?.let { return it to TypedValue.TYPE_DIMENSION }
        value.toIntOrNull()?.let { return it to TypedValue.TYPE_INT_DEC }
        value.removePrefix("0x").takeIf { value.startsWith("0x", true) }
            ?.toLongOrNull(16)?.let { return it.toInt() to TypedValue.TYPE_INT_HEX }
        value.toFloatOrNull()?.let { return it.toRawBits() to TypedValue.TYPE_FLOAT }

        return 0 to TypedValue.TYPE_STRING
    }

    private fun resolveReference(resources: Resources, value: String): Pair<Int, Int>? {
        if (!value.startsWith("@") && !value.startsWith("?")) return null
        val attribute = value.startsWith("?")
        val token = value.substring(1).removePrefix("+")
        if (token == "null") return 0 to TypedValue.TYPE_NULL
        token.toIntOrNull()?.let {
            return it to (if (attribute) TypedValue.TYPE_ATTRIBUTE else TypedValue.TYPE_REFERENCE)
        }
        val colon = token.indexOf(':')
        val packageName = if (colon >= 0) token.substring(0, colon) else null
        val resourcePath = if (colon >= 0) token.substring(colon + 1) else token
        val slash = resourcePath.indexOf('/')
        if (slash <= 0 || slash == resourcePath.lastIndex) return null
        val type = resourcePath.substring(0, slash).removePrefix("attr/")
        val name = resourcePath.substring(slash + 1)
        val id = resources.getIdentifier(name, type, packageName)
        if (id == 0) return null
        return id to (if (attribute) TypedValue.TYPE_ATTRIBUTE else TypedValue.TYPE_REFERENCE)
    }

    /** Reads enum/flag symbols from the attribute's resource-table bag. */
    private fun resolveEnum(resources: Resources, attributeId: Int, rawValue: String): Int? {
        val values = enumValues(resources, attributeId)
        if (values.isEmpty()) return null
        var result = 0
        for (part in rawValue.split('|')) {
            val enumValue = values[part.trim()] ?: return null
            result = result or enumValue
        }
        return result
    }

    private fun enumValues(resources: Resources, attributeId: Int): Map<String, Int> {
        val assets = resources.assets
        val bagValue: Method
        val keys = AssetManager::class.reflect {
            bagValue = method("getResourceBagText")!!
            method("getStyleAttributes")!!.call<IntArray>(assets, attributeId)
        }
        return buildMap {
            for (key in keys) {
                val name = runCatching { resources.getResourceEntryName(key) }.getOrNull() ?: continue
                val value = bagValue.call<CharSequence>(assets, attributeId, key).toString().toIntOrNull() ?: continue
                put(name, value)
            }
        }
    }

    private fun parseColor(value: String): Int? {
        if (!value.startsWith('#')) return null
        val digits = value.substring(1)
        return runCatching {
            when (digits.length) {
                3 -> (0xff shl 24) or (digits[0].digitToInt(16) * 17 shl 16) or
                    (digits[1].digitToInt(16) * 17 shl 8) or (digits[2].digitToInt(16) * 17)
                4 -> (digits[0].digitToInt(16) * 17 shl 24) or
                    (digits[1].digitToInt(16) * 17 shl 16) or (digits[2].digitToInt(16) * 17 shl 8) or
                    (digits[3].digitToInt(16) * 17)
                6 -> (0xff000000L or digits.toLong(16)).toInt()
                8 -> digits.toLong(16).toInt()
                else -> return null
            }
        }.getOrNull()
    }

    private fun parseDimension(value: String): Int? {
        val match = Regex("^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(dp|dip|sp|px|pt|in|mm)$", RegexOption.IGNORE_CASE)
            .matchEntire(value)
            ?: return null
        val amount = match.groupValues[1].toFloatOrNull() ?: return null
        val unit = when (match.groupValues[2].lowercase()) {
            "px" -> TypedValue.COMPLEX_UNIT_PX
            "dp", "dip" -> TypedValue.COMPLEX_UNIT_DIP
            "sp" -> TypedValue.COMPLEX_UNIT_SP
            "pt" -> TypedValue.COMPLEX_UNIT_PT
            "in" -> TypedValue.COMPLEX_UNIT_IN
            "mm" -> TypedValue.COMPLEX_UNIT_MM
            else -> return null
        }
        return encodeComplex(amount, unit)
    }

    private fun encodeComplex(value: Float, unit: Int): Int {
        val abs = abs(value)
        val (mantissa, radix) = when {
            abs < 1f -> (value * (1 shl 23)).toInt() to 3
            abs < (1 shl 8) -> (value * (1 shl 15)).toInt() to 2
            abs < (1 shl 16) -> (value * (1 shl 7)).toInt() to 1
            else -> value.toInt() to 0
        }
        return ((mantissa and 0x00ffffff) shl 8) or (radix shl 4) or unit
    }
}
