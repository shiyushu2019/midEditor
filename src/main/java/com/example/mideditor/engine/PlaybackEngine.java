package com.example.mideditor.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sound.midi.ShortMessage;
import javax.swing.SwingUtilities;

/**
 * lookahead 调度器：独立线程每 20ms 唤醒、向前看约 260ms 投递事件，
 * 时间基准取合成器音频时钟（不可用时退回 System.nanoTime），避免累积漂移。
 */
public final class PlaybackEngine {

    public enum State {
        STOPPED, PLAYING, PAUSED
    }

    public interface Listener {
        void playbackStateChanged(PlaybackEngine engine);
    }

    public record LoopRange(long startTick, long endTick) {
        public boolean valid() {
            return endTick > startTick;
        }
    }

    private static final int LOOKAHEAD_US = 260_000;
    private static final long SCHEDULER_SLEEP_MS = 20;

    private final SynthService synth;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile State state = State.STOPPED;
    private volatile Object owner;
    private volatile PlaybackPlan plan;
    private volatile boolean loopEnabled;
    private volatile LoopRange loopRange = new LoopRange(0, 0);

    private volatile long anchorTick;
    private volatile long anchorMicros;
    private volatile long anchorNanos;
    private volatile long anchorSynthMicros = -1;
    private volatile long frozenTick;

    private int nextIndex;
    private long cycleOffsetUs;
    private Thread scheduler;

    public PlaybackEngine(SynthService synth) {
        this.synth = synth;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public State state() {
        return state;
    }

    public boolean isPlaying() {
        return state == State.PLAYING;
    }

    public Object owner() {
        return owner;
    }

    public boolean loopEnabled() {
        return loopEnabled;
    }

    public LoopRange loopRange() {
        return loopRange;
    }

    public PlaybackPlan plan() {
        return plan;
    }

    public void play(Object owner, PlaybackPlan newPlan, long fromTick, boolean loop, LoopRange range) {
        if (!synth.available()) {
            return;
        }
        synchronized (lock) {
            stopSchedulerThread();
            synth.allNotesOff();
            this.owner = owner;
            this.plan = newPlan;
            this.loopEnabled = loop && range != null && range.valid() && range.startTick() >= 0;
            this.loopRange = range == null ? new LoopRange(0, 0) : range;
            anchor(fromTick);
            state = State.PLAYING;
            startSchedulerThread();
        }
        fireStateChanged();
    }

    public void pause() {
        synchronized (lock) {
            if (state != State.PLAYING) {
                return;
            }
            frozenTick = currentTick();
            state = State.PAUSED;
            stopSchedulerThread();
            synth.allNotesOff();
        }
        fireStateChanged();
    }

    public void stop() {
        synchronized (lock) {
            state = State.STOPPED;
            stopSchedulerThread();
            synth.allNotesOff();
            if (plan != null) {
                anchor(0);
            }
            frozenTick = 0;
        }
        fireStateChanged();
    }

    /** 播放中定位：静音当前发声并重新锚定，实现无缝续播。 */
    public void seek(long tick) {
        synchronized (lock) {
            if (plan == null) {
                return;
            }
            long target = Math.max(0, tick);
            if (state == State.PLAYING) {
                synth.allNotesOff();
            }
            anchor(target);
            frozenTick = target;
        }
        fireStateChanged();
    }

    /** BPM / 静音 / 独奏等变化后在当前播放位置重建计划。 */
    public void updatePlan(PlaybackPlan newPlan) {
        synchronized (lock) {
            if (plan == null) {
                return;
            }
            long tick = currentTick();
            plan = newPlan;
            if (state == State.PLAYING) {
                synth.allNotesOff();
                anchor(tick);
            } else {
                frozenTick = tick;
            }
        }
    }

    public void setLoopEnabled(boolean enabled, LoopRange range) {
        synchronized (lock) {
            this.loopRange = range == null ? new LoopRange(0, 0) : range;
            this.loopEnabled = enabled && this.loopRange.valid();
            if (state == State.PLAYING) {
                // 保证循环边界变化后仍然连续
                anchor(currentTick());
            }
        }
        fireStateChanged();
    }

    public long currentTick() {
        PlaybackPlan p = plan;
        if (p == null) {
            return 0;
        }
        if (state != State.PLAYING) {
            return frozenTick;
        }
        double elapsedUs = elapsedMicros();
        double targetAbsUs = anchorMicros + elapsedUs;
        if (loopEnabled) {
            LoopRange range = loopRange;
            double loopStartUs = p.tempo().microsAtTick(range.startTick());
            double loopEndUs = p.tempo().microsAtTick(range.endTick());
            double loopLenUs = loopEndUs - loopStartUs;
            if (loopLenUs > 1) {
                int guard = 0;
                while (targetAbsUs >= loopEndUs && guard++ < 100_000) {
                    targetAbsUs -= loopLenUs;
                }
                if (targetAbsUs < loopStartUs) {
                    targetAbsUs = loopStartUs;
                }
            }
        }
        return p.tempo().tickAtMicros(Math.round(targetAbsUs));
    }

    private double elapsedMicros() {
        long synthNow = synth.micros();
        if (synthNow >= 0 && anchorSynthMicros >= 0) {
            return synthNow - anchorSynthMicros;
        }
        return (System.nanoTime() - anchorNanos) / 1000.0;
    }

    private void anchor(long tick) {
        PlaybackPlan p = plan;
        if (p == null) {
            return;
        }
        long target = Math.max(0, tick);
        anchorTick = target;
        anchorMicros = p.tempo().microsAtTick(target);
        anchorNanos = System.nanoTime();
        anchorSynthMicros = synth.micros();
        cycleOffsetUs = 0;
        nextIndex = p.indexAtTick(target);
        frozenTick = target;
        sendInitialSetup(p);
    }

    /** 定位后需要重新下发 tick 0 的 program/CC 事件（不能重发音符）。 */
    private void sendInitialSetup(PlaybackPlan p) {
        for (PlaybackPlan.Event event : p.events()) {
            if (event.tick() > 0) {
                break;
            }
            if (event.command() == ShortMessage.PROGRAM_CHANGE
                    || event.command() == ShortMessage.CONTROL_CHANGE) {
                sendImmediate(event);
            }
        }
    }

    private void startSchedulerThread() {
        Thread thread = new Thread(this::runScheduler, "midEditor-scheduler");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 2);
        scheduler = thread;
        thread.start();
    }

    private void stopSchedulerThread() {
        Thread thread = scheduler;
        scheduler = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runScheduler() {
        try {
            while (state == State.PLAYING) {
                dispatchDue(elapsedMicros() + LOOKAHEAD_US);
                Thread.sleep(SCHEDULER_SLEEP_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 调度异常不应导致 UI 崩溃
        }
    }

    private void dispatchDue(double horizonUs) {
        PlaybackPlan p = plan;
        if (p == null) {
            return;
        }
        List<PlaybackPlan.Event> events = p.events();
        while (state == State.PLAYING) {
            if (nextIndex >= events.size()) {
                if (loopEnabled && loopRange.valid()) {
                    nextIndex = p.indexAtTick(loopRange.startTick());
                    cycleOffsetUs += loopLengthUs(p);
                    continue;
                }
                long endUs = p.tempo().microsAtTick(p.endTick()) - anchorMicros;
                if (elapsedMicros() > endUs + 200_000) {
                    SwingUtilities.invokeLater(this::pause);
                }
                break;
            }
            PlaybackPlan.Event event = events.get(nextIndex);
            if (loopEnabled && loopRange.valid() && event.tick() >= loopRange.endTick()) {
                nextIndex = p.indexAtTick(loopRange.startTick());
                cycleOffsetUs += loopLengthUs(p);
                continue;
            }
            double dueUs = p.tempo().microsAtTick(event.tick()) + cycleOffsetUs - anchorMicros;
            if (dueUs > horizonUs) {
                break;
            }
            sendScheduled(event, dueUs);
            nextIndex++;
        }
    }

    private long loopLengthUs(PlaybackPlan p) {
        LoopRange range = loopRange;
        long lengthUs = p.tempo().microsAtTick(range.endTick()) - p.tempo().microsAtTick(range.startTick());
        return Math.max(1, lengthUs);
    }

    private void sendScheduled(PlaybackPlan.Event event, double dueUs) {
        try {
            ShortMessage message = new ShortMessage(event.command(), event.channel(), event.data1(), event.data2());
            long synthNow = synth.micros();
            if (synthNow >= 0 && anchorSynthMicros >= 0) {
                synth.send(message, anchorSynthMicros + Math.round(dueUs));
            } else {
                synth.send(message);
            }
        } catch (Exception ignored) {
            // 单条事件失败不影响整体播放
        }
    }

    private void sendImmediate(PlaybackPlan.Event event) {
        try {
            synth.send(new ShortMessage(event.command(), event.channel(), event.data1(), event.data2()));
        } catch (Exception ignored) {
            // 忽略
        }
    }

    private void fireStateChanged() {
        Runnable notify = () -> {
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.playbackStateChanged(this);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            notify.run();
        } else {
            SwingUtilities.invokeLater(notify);
        }
    }
}
