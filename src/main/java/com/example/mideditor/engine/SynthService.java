package com.example.mideditor.engine;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;

/**
 * 单一音频后端：Gervill 合成器 + 真实 GM 采样音色库（SoundFont / DLS）。
 * 音色库解析顺序：-Dmideditor.soundfont → ./soundfonts/*.sf2 → Windows gm.dls → JDK 默认。
 */
public final class SynthService implements AutoCloseable {

    private static final Path GM_DLS = Path.of("C:\\Windows\\System32\\drivers\\gm.dls");

    private Synthesizer synth;
    private Receiver receiver;
    private Method microsMethod;
    private String description = "未初始化";
    private boolean available;
    private final java.util.concurrent.atomic.AtomicLong noteEventsSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong noteOffsSent = new java.util.concurrent.atomic.AtomicLong();
    /** 正在发声的音符（channel<<8|pitch），All Notes Off 后清空。 */
    private final java.util.Set<Integer> activeNotes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long lastTimestamp = -1;

    public synchronized void open() {
        if (synth != null) {
            return;
        }
        try {
            synth = MidiSystem.getSynthesizer();
            synth.open();
            Soundbank bank = loadSoundbank();
            if (bank != null) {
                Instrument[] instruments = bank.getInstruments();
                boolean loaded = false;
                for (Instrument instrument : instruments) {
                    if (instrument != null) {
                        loaded = true;
                        break;
                    }
                }
                if (loaded) {
                    synth.loadAllInstruments(bank);
                }
            }
            receiver = synth.getReceiver();
            try {
                microsMethod = synth.getClass().getMethod("getMicrosecondPosition");
            } catch (NoSuchMethodException ignored) {
                microsMethod = null;
            }
            description = "Gervill · " + (bank == null ? "默认音色库" : bank.getName());
            available = true;
            resetChannels();
        } catch (Exception | LinkageError t) {
            available = false;
            description = "音频后端不可用：" + t.getMessage();
            synth = null;
            receiver = null;
        }
    }

    private Soundbank loadSoundbank() {
        List<Path> candidates = new ArrayList<>();
        String override = System.getProperty("mideditor.soundfont");
        if (override != null && !override.isBlank()) {
            candidates.add(Path.of(override));
        }
        Path soundfonts = Path.of("soundfonts");
        if (Files.isDirectory(soundfonts)) {
            try (var stream = Files.list(soundfonts)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".sf2") || n.endsWith(".dls");
                }).sorted().forEach(candidates::add);
            } catch (java.io.IOException ignored) {
                // 目录不可读则忽略
            }
        }
        candidates.add(GM_DLS);

        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                Soundbank bank = MidiSystem.getSoundbank(new File(candidate.toString()));
                if (bank != null) {
                    return bank;
                }
            } catch (java.io.IOException | javax.sound.midi.InvalidMidiDataException ignored) {
                // 尝试下一个候选
            }
        }
        try {
            return synth.getDefaultSoundbank();
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    private void resetChannels() {
        try {
            for (int ch = 0; ch < 16; ch++) {
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7, 100));
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 10, 64));
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0));
            }
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 通道重置失败不影响主流程
        }
    }

    /**
     * 重置单个通道的"会残留"状态：弯音回中、RPN 调音回默认、弯音范围 2 半音、
     * 延音/调制/表情归位。避免上一个文档的调音把后续文档整体移调。
     */
    public synchronized void resetChannel(int channel) {
        int ch = Math.max(0, Math.min(15, channel));
        try {
            send(new ShortMessage(ShortMessage.PITCH_BEND, ch, 0, 64));
            // RPN 0,0 = 弯音范围 = 2 半音
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 101, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 100, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 6, 2));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 38, 0));
            // RPN 0,1 = 微调 = 中心
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 101, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 100, 1));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 6, 64));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 38, 0));
            // RPN 0,2 = 粗调 = 中心
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 101, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 100, 2));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 6, 64));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 38, 0));
            // 退出 RPN
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 101, 127));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 100, 127));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 64, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 1, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 11, 127));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 120, 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0));
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 单通道重置失败不影响其它通道
        }
    }

    /** 播放开始前清空所有通道的残留调音/弯音/踏板状态。 */
    public synchronized void resetAllChannels() {
        for (int ch = 0; ch < 16; ch++) {
            resetChannel(ch);
        }
    }

    public synchronized void send(ShortMessage message) {
        send(message, -1);
    }

    /** 带绝对时间戳投递（微秒，合成器时钟）。 */
    public synchronized void send(ShortMessage message, long timestampMicros) {
        if (receiver == null) {
            return;
        }
        boolean noteOn = message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0;
        boolean noteOff = message.getCommand() == ShortMessage.NOTE_OFF
                || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0);
        if (noteOn) {
            noteEventsSent.incrementAndGet();
            activeNotes.add(noteKey(message.getChannel(), message.getData1()));
        } else if (noteOff) {
            noteOffsSent.incrementAndGet();
            activeNotes.remove(noteKey(message.getChannel(), message.getData1()));
        }
        lastTimestamp = timestampMicros;
        receiver.send(message, timestampMicros);
    }

    private static Integer noteKey(int channel, int pitch) {
        return (channel << 8) | (pitch & 0xFF);
    }

    public long noteEventsSent() {
        return noteEventsSent.get();
    }

    /** 已发出的 note-off 数量（用于验证停止/暂停时是否补发了消音事件）。 */
    public long noteOffsSent() {
        return noteOffsSent.get();
    }

    public long lastTimestamp() {
        return lastTimestamp;
    }

    /** 合成器音频时钟（微秒）；不可用时返回 -1。 */
    public long micros() {
        if (microsMethod == null || synth == null) {
            return -1;
        }
        try {
            Object value = microsMethod.invoke(synth);
            if (value instanceof Long l) {
                return l;
            }
            return -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 模块未导出时会抛 IllegalAccessException：永久降级，避免反复反射
            microsMethod = null;
            return -1;
        }
    }

    /** 音频时钟是否可用（用于诊断面板显示）。 */
    public boolean clockAvailable() {
        return micros() >= 0;
    }

    public void allNotesOff() {
        try {
            for (int ch = 0; ch < 16; ch++) {
                // 先松延音踏板：踏板踩下时 All Notes Off 会被“留住”变成余音
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 64, 0));
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0));
                // CC120 All Sound Off：直接掐断波的释放段，避免“余音绕梁”
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 120, 0));
            }
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 全音符关闭失败不影响主流程
        } finally {
            activeNotes.clear();
        }
    }

    /** 仍在发声（已发 note-on 且未收到 note-off）的音符数，主要用于测试与诊断。 */
    public int activeNoteCount() {
        return activeNotes.size();
    }

    /** 试听单个音符（单击平行线时使用）：立即发声，到时自动 note-off。 */
    public void audition(int channel, int program, int pitch, int velocity,
            int volume, int pan, long millis) {
        if (!available || receiver == null) {
            return;
        }
        int ch = Math.max(0, Math.min(15, channel));
        try {
            resetChannel(ch);
            send(new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, Math.max(0, Math.min(127, program)), 0));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7, Math.max(0, Math.min(127, volume))));
            send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 10, Math.max(0, Math.min(127, pan))));
            send(new ShortMessage(ShortMessage.NOTE_ON, ch,
                    Math.max(0, Math.min(127, pitch)), Math.max(1, Math.min(127, velocity))));
            javax.swing.Timer timer = new javax.swing.Timer((int) Math.max(60, millis), e -> {
                try {
                    send(new ShortMessage(ShortMessage.NOTE_OFF, ch, Math.max(0, Math.min(127, pitch)), 0));
                } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
                    // 自动 note-off 失败忽略
                }
            });
            timer.setRepeats(false);
            timer.start();
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 试听失败不影响编辑
        }
    }

    public boolean available() {
        return available;
    }

    public String description() {
        return description;
    }

    @Override
    public synchronized void close() {
        if (synth != null) {
            try {
                allNotesOff();
                synth.close();
            } finally {
                synth = null;
                receiver = null;
            }
        }
        available = false;
    }
}
