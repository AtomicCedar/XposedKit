@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalContracts::class)

package cc.meteormc.xposedkit

import android.os.Build
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.util.Primitives
import cc.meteormc.xposedkit.util.UnsafeMemory
import org.lsposed.hiddenapibypass.HiddenApiBypass
import sun.misc.Unsafe
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
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
    private val nestedClassCache = mutableMapOf<Any, Class<*>?>()
    private val constructorCache = mutableMapOf<Any, Constructor<*>?>()
    private val methodCache = mutableMapOf<Any, Method?>()
    private val fieldCache = mutableMapOf<Any, Field?>()

    val singleton by lazy {
        runCatching {
            type.getDeclaredField("INSTANCE")
        }.getOrNull()?.takeIf {
            Modifier.isStatic(it.modifiers)
        }?.get<T>(null)
    }

    fun nestedClass(name: String) = nestedClassCache.getOrPut(name) {
        findRecursive {
            it.declaredClasses.firstOrNull { clazz ->
                clazz.name == "${type.name}$$name"
            }
        }
    }

    inline val nestedClasses
        get() = type.classes.toList()

    inline val declaredNestedClasses
        get() = type.declaredClasses.toList()

    fun constructor(vararg paramTypes: Class<*>) = constructorCache.getOrPut(getParametersString(*paramTypes)) {
        runCatching {
            type.getDeclaredConstructor(*paramTypes)
        }.getOrElse {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.getDeclaredConstructor(type, *paramTypes) }.getOrNull()
            } else {
                null
            }
        }?.setAccessible()
    } as? Constructor<T>

    fun constructor(matcher: ConstructorMatcher) = constructorCache.getOrPut(matcher) {
        if (!matcher.isHidden || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            declaredConstructors
        } else {
            HiddenApiBypass.getDeclaredMethods(type).filterIsInstance<Constructor<*>>()
        }.firstOrNull {
            matcher.matches(it)
        }?.setAccessible()
    } as? Constructor<T>

    inline fun constructor(matcher: ConstructorMatcher.() -> Unit): Constructor<T>? {
        contract {
            callsInPlace(matcher, InvocationKind.EXACTLY_ONCE)
        }
        return constructor(ConstructorMatcher().apply(matcher))
    }

    inline val constructors
        get() = type.constructors.setAccessible()

    inline val declaredConstructors
        get() = type.declaredConstructors.map {
            (it as Constructor<T>).setAccessible()
        }

    fun method(name: String) = methodCache.getOrPut("$name[*]") {
        method(name, *emptyArray<Class<*>>())?.let {
            return@getOrPut it
        }

        method {
            this.name = name
        }
    }

    fun method(vararg paramTypes: Class<*>?) = methodCache.getOrPut(getParametersString(*paramTypes) + "[*]") {
        method {
            this.paramTypes = paramTypes
        }
    }

    fun method(name: String, vararg paramTypes: Class<*>) = methodCache.getOrPut(name + getParametersString(*paramTypes)) {
        findRecursive {
            runCatching {
                it.getDeclaredMethod(name, *paramTypes)
            }.getOrElse { _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { HiddenApiBypass.getDeclaredMethod(it, name, *paramTypes) }.getOrNull()
                } else {
                    null
                }
            }
        }?.setAccessible()
    }

    fun method(matcher: MethodMatcher) = methodCache.getOrPut(matcher) {
        findRecursive {
            if (!matcher.isHidden || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                declaredMethods
            } else {
                HiddenApiBypass.getDeclaredMethods(type).filterIsInstance<Method>()
            }.firstOrNull {
                matcher.matches(it)
            }
        }?.setAccessible()
    }

    inline fun method(matcher: MethodMatcher.() -> Unit): Method? {
        contract {
            callsInPlace(matcher, InvocationKind.EXACTLY_ONCE)
        }
        return method(MethodMatcher().apply(matcher))
    }

    inline val methods
        get() = type.methods.setAccessible()

    inline val declaredMethods
        get() = type.declaredMethods.setAccessible()

    fun field(name: String) = fieldCache.getOrPut(name) {
        findRecursive {
            runCatching {
                it.getDeclaredField(name)
            }.getOrElse { _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val fields = HiddenApiBypass.getStaticFields(it) + HiddenApiBypass.getInstanceFields(it)
                    fields.firstOrNull { field -> field.name == name }
                } else {
                    null
                }
            }
        }?.setAccessible()
    }

    fun field(type: Class<*>) = fieldCache.getOrPut(type) {
        findRecursive {
            it.declaredFields.singleOrNull { field ->
                field.type == type
            }
        }?.setAccessible()
    }

    fun field(matcher: FieldMatcher) = fieldCache.getOrPut(matcher) {
        findRecursive {
            if (!matcher.isHidden || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                declaredFields
            } else {
                HiddenApiBypass.getStaticFields(type) + HiddenApiBypass.getInstanceFields(type)
            }.firstOrNull {
                matcher.matches(it)
            }
        }?.setAccessible()
    }

    inline fun field(matcher: FieldMatcher.() -> Unit): Field? {
        contract {
            callsInPlace(matcher, InvocationKind.EXACTLY_ONCE)
        }
        return field(FieldMatcher().apply(matcher))
    }

    inline val fields
        get() = type.fields.setAccessible()

    inline val declaredFields
        get() = type.declaredFields.setAccessible()

    private fun getParametersString(vararg clazzes: Class<*>?): String {
        return "(${clazzes.joinToString(",") { it?.name ?: "null" }})"
    }

    private inline fun <R> findRecursive(func: (clazz: Class<*>) -> R?): R? {
        var superClass: Class<*> = type
        do {
            func(superClass)?.let { return it }
        } while ((superClass.getSuperclass()?.also { superClass = it }) != null)
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reflect<*>) return false
        if (type != other.type) return false
        return true
    }

    override fun hashCode(): Int {
        return type.hashCode()
    }

    override fun toString(): String {
        return "Reflect{${type.name}}"
    }
}

sealed class MemberMatcher {
    var name: String? = null

    var modifiers: Int? = null

    var annotations: Array<out Annotation>? = null

    var isSynthetic: Boolean? = null

    var isHidden: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemberMatcher) return false

        if (name != other.name) return false
        if (modifiers != other.modifiers) return false
        if (!annotations.contentEquals(other.annotations)) return false
        if (isSynthetic != other.isSynthetic) return false
        if (isHidden != other.isHidden) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (modifiers ?: 0)
        result = 31 * result + (annotations?.contentHashCode() ?: 0)
        result = 31 * result + (isSynthetic?.hashCode() ?: 0)
        return result
    }
}

sealed class ExecutableMatcher : MemberMatcher() {
    var paramTypes: Array<out Class<*>?>? = null

    var paramCount: Int? = null

    var exceptionTypes: Array<out Class<*>>? = null

    var isVarArgs: Boolean? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExecutableMatcher) return false
        if (!super.equals(other)) return false

        if (!paramTypes.contentEquals(other.paramTypes)) return false
        if (paramCount != other.paramCount) return false
        if (!exceptionTypes.contentEquals(other.exceptionTypes)) return false
        if (isVarArgs != other.isVarArgs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (paramTypes?.contentHashCode() ?: 0)
        result = 31 * result + (paramCount ?: 0)
        result = 31 * result + (exceptionTypes?.contentHashCode() ?: 0)
        result = 31 * result + (isVarArgs?.hashCode() ?: 0)
        return result
    }
}

class ConstructorMatcher : ExecutableMatcher() {
    fun matches(constructor: Constructor<*>): Boolean {
        if (name != null && name != constructor.name) return false
        if (modifiers != null && modifiers != constructor.modifiers) return false
        if (annotations != null && !annotations!!.any { it in constructor.annotations }) return false
        if (paramTypes != null && !paramTypes.contentEqualsWithoutNull(constructor.parameterTypes)) return false
        if (paramCount != null && paramCount != constructor.parameterTypes.size) return false
        if (exceptionTypes != null && !exceptionTypes!!.any { it in constructor.exceptionTypes }) return false
        if (isSynthetic != null && isSynthetic != constructor.isSynthetic) return false
        if (isVarArgs != null && isVarArgs != constructor.isVarArgs) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConstructorMatcher) return false
        if (!super.equals(other)) return false
        return true
    }

    override fun hashCode(): Int {
        return super.hashCode() * 31
    }
}

class MethodMatcher : ExecutableMatcher() {
    var returnType: Class<*>? = null

    var isBridge: Boolean? = null

    var isDefault: Boolean? = null

    fun matches(method: Method): Boolean {
        if (name != null && name != method.name) return false
        if (modifiers != null && modifiers != method.modifiers) return false
        if (annotations != null && !annotations!!.any { it in method.annotations }) return false
        if (paramTypes != null && !paramTypes.contentEqualsWithoutNull(method.parameterTypes)) return false
        if (paramCount != null && paramCount != method.parameterTypes.size) return false
        if (exceptionTypes != null && !exceptionTypes!!.any { it in method.exceptionTypes }) return false
        if (returnType != null && returnType != method.returnType) return false
        if (isSynthetic != null && isSynthetic != method.isSynthetic) return false
        if (isVarArgs != null && isVarArgs != method.isVarArgs) return false
        if (isBridge != null && isBridge != method.isBridge) return false
        if (isDefault != null && isDefault != method.isDefault) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodMatcher) return false
        if (!super.equals(other)) return false

        if (returnType != other.returnType) return false
        if (isBridge != other.isBridge) return false
        if (isDefault != other.isDefault) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (returnType?.hashCode() ?: 0)
        result = 31 * result + (isBridge?.hashCode() ?: 0)
        result = 31 * result + (isDefault?.hashCode() ?: 0)
        return result
    }
}

class FieldMatcher : MemberMatcher() {
    var type: Class<*>? = null

    var isEnumConstant: Boolean? = null

    fun matches(field: Field): Boolean {
        if (name != null && name != field.name) return false
        if (modifiers != null && modifiers != field.modifiers) return false
        if (annotations != null && !annotations!!.any { it in field.annotations }) return false
        if (type != null && type != field.type) return false
        if (isSynthetic != null && isSynthetic != field.isSynthetic) return false
        if (isEnumConstant != null && isEnumConstant != field.isEnumConstant) return false
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FieldMatcher) return false
        if (!super.equals(other)) return false

        if (type != other.type) return false
        if (isEnumConstant != other.isEnumConstant) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + (isEnumConstant?.hashCode() ?: 0)
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

fun <T : Any> Class<T>.findInstances(assignable: Boolean = true): List<T> {
    if (!NativeBridge.isLoaded) {
        return emptyList()
    }

    return NativeBridge.VisitHeapObjects(this, assignable)?.toList().orEmpty()
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
    this.setAccessible()
    val isStatic = Modifier.isStatic(modifiers)
    val isVolatile = Modifier.isVolatile(modifiers)
    // 从 Android 17 的第一个 Beta 开始 无法通过反射修改 static final 的字段
    // https://android-developers.googleblog.com/2026/02/the-first-beta-of-android-17.html
    if (
        !(isStatic && Modifier.isFinal(modifiers)) ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA ||
        (Build.VERSION.SDK_INT == Build.VERSION_CODES.BAKLAVA && Build.VERSION.PREVIEW_SDK_INT < 1)
    ) {
        try {
            this[obj] = value
            return
        } catch (_: IllegalAccessException) {

        }
    }

    // 提前解析字段以便读取正确的偏移量
    runCatching { get(obj) }
    val base = if (isStatic) declaringClass else obj
    val offset = Field::class.java.reflect.field("offset")!!.get<Long>(this)
    when (type) {
        Boolean::class.javaPrimitiveType -> {
            value!! as Boolean
            if (!isVolatile) {
                theUnsafe.putBoolean(base, offset, value)
            } else {
                UnsafeMemory.putInt8Volatile(theUnsafe, base, offset, if (value) 1u else 0u)
            }
        }
        Byte::class.javaPrimitiveType -> {
            value!! as Byte
            if (!isVolatile) {
                theUnsafe.putByte(base, offset, value)
            } else {
                UnsafeMemory.putInt8Volatile(theUnsafe, base, offset, value.toUInt())
            }
        }
        Char::class.javaPrimitiveType -> {
            value!! as Char
            if (!isVolatile) {
                theUnsafe.putChar(base, offset, value)
            } else {
                UnsafeMemory.putInt16Volatile(theUnsafe, base, offset, value.code.toUInt())
            }
        }
        Short::class.javaPrimitiveType -> {
            value!! as Short
            if (!isVolatile) {
                theUnsafe.putShort(base, offset, value)
            } else {
                UnsafeMemory.putInt16Volatile(theUnsafe, base, offset, value.toUInt())
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

@PublishedApi
internal fun <T : AccessibleObject> T.setAccessible(): T {
    isAccessible = true
    return this
}

@PublishedApi
internal fun <T : AccessibleObject> Array<T>.setAccessible(): List<T> {
    return this.map { it.setAccessible() }
}

private infix fun <T> Array<out T>?.contentEqualsWithoutNull(other: Array<out T>?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    if (size != other.size) return false
    return indices.all { i ->
        this[i] == null || this[i] == other[i]
    }
}