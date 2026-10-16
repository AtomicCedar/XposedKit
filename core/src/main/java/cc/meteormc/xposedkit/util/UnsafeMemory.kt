package cc.meteormc.xposedkit.util

import sun.misc.Unsafe

internal object UnsafeMemory {
    fun putInt8Volatile(unsafe: Unsafe, base: Any?, offset: Long, value: UInt) {
        val alignedOffset = offset and -4L
        var oldValue: Int
        var newValue: Int
        do {
            oldValue = unsafe.getIntVolatile(base, alignedOffset)
            val bits = (offset - alignedOffset).toInt() * 8
            newValue = (oldValue and (0xFF shl bits).inv()) or (value shl bits).toInt()
        } while (!unsafe.compareAndSwapInt(base, alignedOffset, oldValue, newValue))
    }

    fun putInt16Volatile(unsafe: Unsafe, base: Any?, offset: Long, value: UInt) {
        val alignedOffset = offset and -4L
        var oldValue: Int
        var newValue: Int
        do {
            oldValue = unsafe.getIntVolatile(base, alignedOffset)
            val bits = (offset - alignedOffset).toInt() * 8
            newValue = (oldValue and (0xFFFF shl bits).inv()) or (value shl bits).toInt()
        } while (!unsafe.compareAndSwapInt(base, alignedOffset, oldValue, newValue))
    }
}