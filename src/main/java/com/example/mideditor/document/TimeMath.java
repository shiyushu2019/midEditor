package com.example.mideditor.document;

import com.example.mideditor.domain.TimeSignature;

/** 小节 / 拍 的时间换算。 */
public final class TimeMath {

    private TimeMath() {
    }

    public static TimeSignature signatureAt(MidiDocument document, long tick) {
        TimeSignature result = TimeSignature.defaultSignature();
        for (TimeSignature signature : document.timeSignatures()) {
            if (signature.startTick() <= tick) {
                result = signature;
            } else {
                break;
            }
        }
        return result;
    }

    public static long beatTicks(MidiDocument document, long tick) {
        TimeSignature signature = signatureAt(document, tick);
        return Math.max(1, document.ppq() * 4L / Math.max(1, signature.denominator()));
    }

    public static long barTicks(MidiDocument document, long tick) {
        TimeSignature signature = signatureAt(document, tick);
        return Math.max(1, beatTicks(document, tick) * Math.max(1, signature.numerator()));
    }

    public static long barStart(MidiDocument document, long tick) {
        long bar = barTicks(document, tick);
        return (tick / bar) * bar;
    }

    public static long barCount(MidiDocument document, long tick) {
        long bar = barTicks(document, 0);
        return tick / bar + 1;
    }
}
