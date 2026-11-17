package com.example.mideditor.domain;

/** 拍号（起始 tick + 分子/分母）。 */
public record TimeSignature(long startTick, int numerator, int denominator) {

    public static TimeSignature defaultSignature() {
        return new TimeSignature(0, 4, 4);
    }
}
