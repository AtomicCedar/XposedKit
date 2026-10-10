@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalContracts::class)

package cc.meteormc.xposedkit

import android.os.Build
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.util.Primitives
import sun.misc.Unsafe
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import kotlin.Byte
import kotlin.Float
import kotlin.Long
import kotlin.Short
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.reflect.KClass

private val cache = WeakHashMap<Class<*>, Reflect<*>>()
private val theUnsafe by lazy {
    Unsafe::class.reflect.field("theUnsafe")!!.get<Unsafe>(null)
}

val <T : Any> Class<T>.reflect: Reflect<T>
    get() = cache.getOrPut(this) { Reflect(this) } as Reflect<T>

inline fun <T : Any, R> Class<T>.reflect(block: Reflect<T>.() -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return reflect.run(block)
}

val <T : Any> KClass<T>.reflect: Reflect<T>
    get() = this.java.reflect

inline fun <T : Any, R> KClass<T>.reflect(block: Reflect<T>.() -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return this.java.reflect(block)
}

fun ClassLoader.reflect(className: String): Reflect<*>? {
    fun tryLoad(name: String): Class<*> {
        val clazz = Primitives.getAbbreviation(name)?.let {
            loadClass("[$it").componentType
        }

        if (clazz != null) return clazz
        var formatName = name.filterNot { it.isWhitespace() }
        if (formatName.endsWith("[]")) {
            val prefix = buildString {
                while (formatName.endsWith("[]")) {
                    formatName = formatName.dropLast(2)
                    append('[')
                }
            }

            formatName = prefix + (Primitives.getAbbreviation(formatName) ?: "L$formatName;")
        }

        return loadClass(formatName)
    }

    return runCatching {
        tryLoad(className)
    }.recoverCatching { ex ->
        if (ex !is ClassNotFoundException) throw ex

        val lastDot = className.lastIndexOf('.')
        if (lastDot == -1) throw ex

        val innerName = className.replaceRange(
            lastDot,
            lastDot + 1,
            "$"
        )

        tryLoad(innerName)
    }.map {
        it.reflect
    }.getOrNull()
}

inline fun <R> ClassLoader.reflect(className: String, block: Reflect<*>.() -> R): R? {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return reflect(className)?.run(block)
}

fun <T : Any> ClassLoader.typedReflect(className: String): Reflect<T>? {
    return reflect(className) as? Reflect<T>
}

inline fun <T : Any, R> ClassLoader.typedReflect(className: String, block: Reflect<T>.() -> R): R? {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return typedReflect<T>(className)?.run(block)
}

class Reflect<T : Any>(val type: Class<T>) {
    private val nestedClassCache = mutableMapOf<String, Class<*>?>()
    private val constructorCache = mutableMapOf<String, Constructor<*>?>()
    private val methodCache = mutableMapOf<String, Method?>()
    private val fieldCache = mutableMapOf<String, Field?>()

    val singleton by lazy {
        runCatching {
            type.getDeclaredField("INSTANCE")
        }.getOrNull()?.takeIf {
            Modifier.isStatic(it.modifiers)
        }?.get<T>(null)
    }

    fun nestedClass(name: String) = nestedClassCache.getOrPut(name) {
        firstRecursive {
            it.declaredClasses.firstOrNull { clazz ->
                clazz.name == "${type.name}$$name"
            }
        }
    }

    val nestedClasses
        get() = type.classes.toList()

    val declaredNestedClasses
        get() = type.declaredClasses.toList()

    fun constructor(vararg paramTypes: Class<*>) = constructorCache.getOrPut(getParametersString(*paramTypes)) {
        runCatching {
            type.getDeclaredConstructor(*paramTypes).setAccessible()
        }.getOrNull()
    } as? Constructor<T>

    val constructors
        get() = type.constructors.setAccessible()

    val declaredConstructors
        get() = type.declaredConstructors.map {
            (it as Constructor<T>).setAccessible()
        }

    fun method(name: String, vararg paramTypes: Class<*>) = methodCache.getOrPut(name + getParametersString(*paramTypes)) {
        firstRecursive {
            runCatching {
                it.getDeclaredMethod(name, *paramTypes).setAccessible()
            }.getOrNull()
        }
    }

    fun method(name: String) = methodCache.getOrPut("$name(*)") {
        method(name, *emptyArray<Class<*>>())?.let {
            return@getOrPut it
        }

        // 先按继承层级取最优先匹配的类
        // 再在该类的重载中取唯一的方法
        // 并忽略父类声明的同名方法
        methods(name)
            .groupBy { it.declaringClass }
            .entries
            .firstOrNull()
            ?.value
            ?.singleOrNull()
    }

    fun method(vararg paramTypes: Class<*>?) = methodCache.getOrPut(getParametersString(*paramTypes) + "(*)") {
        methods(*paramTypes).singleOrNull()
    }

    fun methods(name: String): List<Method> {
        val exists = mutableSetOf<String>()
        return findRecursive {
            it.declaredMethods.filter { method ->
                // 仅保留继承链中最先匹配到的类的方法（优先子类）
                // 忽略父类被覆盖的方法
                name.contentEquals(method.name) && exists.add(method.signature())
            }
        }.flatten().setAccessible()
    }

    fun methods(vararg paramTypes: Class<*>?): List<Method> {
        val exists = mutableSetOf<String>()
        return findRecursive {
            it.declaredMethods.filter { method ->
                // 仅保留继承链中最先匹配到的类的方法（优先子类）
                // 忽略父类被覆盖的方法
                paramTypes.contentEqualsWithoutNull(method.parameterTypes) && exists.add(method.signature())
            }
        }.flatten().setAccessible()
    }

    val methods
        get() = type.methods.setAccessible()

    val declaredMethods
        get() = type.declaredMethods.setAccessible()

    fun field(name: String) = fieldCache.getOrPut(name) {
        firstRecursive {
            runCatching {
                it.getDeclaredField(name).setAccessible()
            }.getOrNull()
        }
    }

    fun fields(type: Class<*>) = findRecursive {
        it.declaredFields.filter { field ->
            field.type == type
        }
    }.flatten().setAccessible()

    val fields
        get() = type.fields.setAccessible()

    val declaredFields
        get() = type.declaredFields.setAccessible()

    private fun getParametersString(vararg clazzes: Class<*>?): String {
        return "(${clazzes.joinToString(",") { it?.name ?: "null" }})"
    }

    private infix fun <T> Array<out T>.contentEqualsWithoutNull(other: Array<out T>): Boolean {
        if (size != other.size) return false
        return indices.all { i ->
            this[i] == null || this[i] == other[i]
        }
    }

    private inline fun <R> firstRecursive(func: (clazz: Class<*>) -> R?): R? {
        var superClass: Class<*> = type
        do {
            func(superClass)?.let { return it }
        } while ((superClass.getSuperclass()?.also { superClass = it }) != null)
        return null
    }

    private inline fun <R> findRecursive(func: (clazz: Class<*>) -> R?): List<R> {
        val result = mutableListOf<R>()
        var superClass: Class<*> = type
        do {
            func(superClass)?.let { result.add(it) }
        } while ((superClass.getSuperclass()?.also { superClass = it }) != null)
        return result
    }
}

fun Class<*>.descriptor(): String {
    return when {
        isPrimitive -> Primitives.getAbbreviation(name)!!
        isArray -> "[" + componentType!!.descriptor()
        else -> "L${name.replace('.', '/')};"
    }
}

fun Member.descriptor(): String {
    var parameterTypes: Array<Class<*>>
    var returnType: Class<*>
    when (this) {
        is Constructor<*> -> {
            parameterTypes = this.parameterTypes
            returnType = Void::class.javaPrimitiveType!!
        }
        is Method -> {
            parameterTypes = this.parameterTypes
            returnType = this.returnType
        }
        else -> {
            throw IllegalArgumentException("Unsupported member type: ${this::class.java.name}")
        }
    }
    return "(${parameterTypes.joinToString("") { it.descriptor() }})${returnType.descriptor()}"
}

fun Member.signature(): String {
    var name: String
    var parameterTypes: Array<Class<*>>
    when (this) {
        is Constructor<*> -> {
            name = "<init>"
            parameterTypes = this.parameterTypes
        }
        is Method -> {
            name = this.name
            parameterTypes = this.parameterTypes
        }
        else -> {
            throw IllegalArgumentException("Unsupported member type: ${this::class.java.name}")
        }
    }
    return "$name(${parameterTypes.joinToString(",") { it.name }})"
}

fun <T : Any> Class<T>.allocate(): T {
    if (!NativeBridge.isLoaded) {
        throw IllegalStateException("NativeBridge is not available!")
    }

    return NativeBridge.AllocObject<T>(this)
}

fun <T : Any> Class<T>.findInstances(): List<T> {
    if (!NativeBridge.isLoaded) {
        return emptyList()
    }

    return NativeBridge.VisitHeapObjects(this).toList()
}

fun Constructor<*>.deoptimize(): Boolean {
    return XposedKit.engine.deoptimize(this)
}

fun <T : Any> Constructor<*>.new(vararg args: Any?): T {
    return this.setAccessible().newInstance(*args) as T
}

fun <T : Any> Constructor<*>.call(obj: T, vararg args: Any?): T {
    setAccessible()
    XposedKit.engine.invokeSpecial(this, obj, *args)
    return obj
}

fun <T : Any> Constructor<*>.callOriginal(obj: Any?, vararg args: Any?): T {
    setAccessible()
    return XposedKit.engine.invokeOriginal(this, obj, *args) as T
}

fun Method.deoptimize(): Boolean {
    return XposedKit.engine.deoptimize(this)
}

fun <T> Method.call(obj: Any?, vararg args: Any?): T {
    return this.setAccessible().invoke(obj, *args) as T
}

fun <T> Method.callOriginal(obj: Any?, vararg args: Any?): T {
    setAccessible()
    return XposedKit.engine.invokeOriginal(this, obj, *args) as T
}

fun <T> Method.callSpecial(obj: Any, vararg args: Any?): T {
    setAccessible()
    return XposedKit.engine.invokeSpecial(this, obj, *args) as T
}

fun <T> Field.get(obj: Any?): T {
    return this.setAccessible()[obj] as T
}

fun Field.put(obj: Any?, value: Any?) {
    val isVolatile = Modifier.isVolatile(modifiers)
    val isImmutable = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
    // 从 Android 17 的第一个 Beta 开始 无法通过反射修改 static final 的字段
    // https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html
    if (
        !isImmutable ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
        (Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA && Build.VERSION.PREVIEW_SDK_INT < 1)
    ) {
        try {
            this.setAccessible()[obj] = value
            return
        } catch (t: Throwable) {
            // 跳过处理非 static final 字段的异常
            if (!isImmutable) throw t
        }
    }

    // 提前解析字段以便读取正确的偏移量
    get(obj)
    val base = declaringClass
    val offset = Field::class.java.reflect.field("offset")!!.get<Long>(this)
    when (type) {
        Boolean::class.javaPrimitiveType -> {
            value!! as Boolean
            if (!isVolatile) {
                theUnsafe.putBoolean(base, offset, value)
            } else {
//                theUnsafe.putBooleanVolatile(base, offset, value)
            }
        }
        Byte::class.javaPrimitiveType -> {
            value!! as Byte
            if (!isVolatile) {
                theUnsafe.putByte(base, offset, value)
            } else {
//                theUnsafe.putByteVolatile(base, offset, value)
            }
        }
        Char::class.javaPrimitiveType -> {
            value!! as Char
            if (!isVolatile) {
                theUnsafe.putChar(base, offset, value)
            } else {
//                theUnsafe.putCharVolatile(base, offset, value)
            }
        }
        Short::class.javaPrimitiveType -> {
            value!! as Short
            if (!isVolatile) {
                theUnsafe.putShort(base, offset, value)
            } else {
//                theUnsafe.putShortVolatile(base, offset, value)
            }
        }
        Int::class.javaPrimitiveType -> {
            value!! as Int
            if (!isVolatile) {
                theUnsafe.putInt(base, offset, value)
            } else {
                theUnsafe.putIntVolatile(base, offset, value)
            }
        }
        Long::class.javaPrimitiveType -> {
            value!! as Long
            if (!isVolatile) {
                theUnsafe.putLong(base, offset, value)
            } else {
                theUnsafe.putLongVolatile(base, offset, value)
            }
        }
        Double::class.javaPrimitiveType -> {
            value!! as Double
            if (!isVolatile) {
                theUnsafe.putDouble(base, offset, value)
            } else {
                theUnsafe.putLongVolatile(base, offset, value.toRawBits())
            }
        }
        Float::class.javaPrimitiveType -> {
            value!! as Float
            if (!isVolatile) {
                theUnsafe.putFloat(base, offset, value)
            } else {
                theUnsafe.putIntVolatile(base, offset, value.toRawBits())
            }
        }
        else -> {
            if (!isVolatile) {
                theUnsafe.putObject(base, offset, value)
            } else {
                theUnsafe.putObjectVolatile(base, offset, value)
            }
        }
    }
}

fun <T : AccessibleObject> T.setAccessible(): T {
    isAccessible = true
    return this
}

fun <T : AccessibleObject> Array<T>.setAccessible(): List<T> {
    return this.map { it.setAccessible() }
}

fun <T : AccessibleObject> Iterable<T>.setAccessible(): List<T> {
    return this.map { it.setAccessible() }
}