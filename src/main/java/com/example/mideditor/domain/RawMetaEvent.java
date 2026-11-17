package com.example.mideditor.domain;

/**
 * 我们不建模、但必须原样保留的 meta 事件（版权、调号、标记、乐器名、SMPTE 偏移……）。
 *
 * <p>
 * 读写 .mid 时按原 tick / 原 type / 原字节原样带回去，避免「打开再保存」就丢掉别的软件写入的信息。
 * </p>
 */
public final class RawMetaEvent {

    private final long tick;
    private final int type;
    private final byte[] data;

    public RawMetaEvent(long tick, int type, byte[] data) {
        this.tick = Math.max(0, tick);
        this.type = type;
        this.data = data == null ? new byte[0] : data.clone();
    }

    public long tick() {
        return tick;
    }

    public int type() {
        return type;
    }

    public byte[] data() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }
}
