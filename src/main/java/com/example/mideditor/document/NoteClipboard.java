package com.example.mideditor.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.mideditor.domain.Note;

/** 应用级剪贴板：保存「平行线」相对时间偏移。 */
public final class NoteClipboard {

    public record Item(long startOffset, int pitch, long duration, int velocity, boolean rest) {
    }

    private List<Item> items = List.of();

    public void copy(List<Note> notes) {
        if (notes == null || notes.isEmpty()) {
            items = List.of();
            return;
        }
        long min = Long.MAX_VALUE;
        for (Note note : notes) {
            min = Math.min(min, note.startTick());
        }
        List<Item> copy = new ArrayList<>();
        for (Note note : notes) {
            copy.add(new Item(note.startTick() - min, note.pitch(), note.durationTicks(), note.velocity(),
                    note.isRest()));
        }
        copy.sort(Comparator.comparingLong(Item::startOffset).thenComparingInt(Item::pitch));
        items = List.copyOf(copy);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public List<Item> items() {
        return items;
    }

    public void clear() {
        items = List.of();
    }
}
