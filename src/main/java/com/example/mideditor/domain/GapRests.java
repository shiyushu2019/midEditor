package com.example.mideditor.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动休止符（空隙）：一个音轨里「第一个音符开始之后、最后一个音符结束之前」，
 * 没有任何音符在发声的时间段，事实上就是休止符，不必再手工画一条红线段。
 *
 * <p>
 * 判定用「发声并集」：只要那段时间里有任何一个音高在响，就不算空隙。
 * 休止符本身不发声，所以它所在的位置天然属于空隙。
 * </p>
 */
public final class GapRests {

    /** 一段空隙：起始 tick（上一条的尾巴）、结束 tick（下一条的头）、以及画在哪一行（上一条的音高）。 */
    public record Span(int pitch, long startTick, long endTick) {

        public long lengthTicks() {
            return endTick - startTick;
        }
    }

    private GapRests() {
    }

    /** 算出音轨里所有自动休止符（按时间升序）。 */
    public static List<Span> of(Track track) {
        if (track == null || track.notes().isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        long cursor = Long.MIN_VALUE;
        int cursorPitch = -1;
        for (Note note : track.notes()) {
            if (note.isRest()) {
                continue; // 休止符不发声，不参与“有没有声音”的判定
            }
            long start = note.startTick();
            long end = note.endTick();
            if (cursor == Long.MIN_VALUE) {
                cursor = end;
                cursorPitch = note.pitch();
                continue;
            }
            if (start > cursor) {
                spans.add(new Span(cursorPitch, cursor, start));
            }
            if (end > cursor) {
                cursor = end;
                cursorPitch = note.pitch();
            }
        }
        // 契约是「按时间升序」：正常情况（notes 已排序）本来就成立，只在检测到乱序时才付排序成本，
        // 让调用方可以放心地按双指针线性合并（Track.reconcileRests）。
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i).startTick() < spans.get(i - 1).startTick()) {
                spans.sort(java.util.Comparator.comparingLong(Span::startTick));
                break;
            }
        }
        return spans;
    }
}
