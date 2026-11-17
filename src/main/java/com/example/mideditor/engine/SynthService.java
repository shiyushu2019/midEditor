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
        } catch (Throwable t) {
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
            } catch (Exception ignored) {
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
            } catch (Exception ignored) {
                // 尝试下一个候选
            }
        }
        try {
            return synth.getDefaultSoundbank();
        } catch (Throwable t) {
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
        } catch (Exception ignored) {
            // 忽略
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
        if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
            noteEventsSent.incrementAndGet();
        }
        lastTimestamp = timestampMicros;
        receiver.send(message, timestampMicros);
    }

    public long noteEventsSent() {
        return noteEventsSent.get();
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
            return value instanceof Long l ? l : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public void allNotesOff() {
        try {
            for (int ch = 0; ch < 16; ch++) {
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0));
                send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 120, 0));
            }
        } catch (Exception ignored) {
            // 忽略
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
            } catch (Exception ignored) {
                // 忽略
            }
            synth = null;
            receiver = null;
        }
        available = false;
    }
}
