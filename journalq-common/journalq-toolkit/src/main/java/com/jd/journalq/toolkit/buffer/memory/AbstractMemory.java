package com.jd.journalq.toolkit.buffer.memory;

import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.os.Systems;
import sun.misc.Unsafe;

/**
 * memory. Represents memory that can be accessed directly via {@link Unsafe}
 */
public abstract class AbstractMemory<T extends MemoryAllocator> implements Memory<T> {
    protected static final Unsafe UNSAFE = Systems.UNSAFE;
    public static final long ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    protected static final String SIZE_ERROR = "size must be in [0," + SIZE_MAX + "]";

    protected T allocator;
    protected long address;
    protected long size;

    public AbstractMemory(T allocator) {
        Preconditions.checkNotNull(allocator, "allocator cannot be null");
        this.allocator = allocator;
    }

    @Override
    public T allocator() {
        return allocator;
    }

    @Override
    public long address() {
        return address;
    }

    @Override
    public long address(final long offset) {
        return address + offset;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public float getFloat(final long offset) {
        return Float.intBitsToFloat(getInt(offset));
    }

    @Override
    public double getDouble(final long offset) {
        return Double.longBitsToDouble(getLong(offset));
    }

    @Override
    public void putFloat(final long offset, final float f) {
        putInt(offset, Float.floatToRawIntBits(f));
    }

    @Override
    public void putDouble(final long offset, final double d) {
        putLong(offset, Double.doubleToRawLongBits(d));
    }

}