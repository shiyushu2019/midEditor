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
    /** 没有音频时钟时不能"超前发送"，否则一窗内的音符会一起响，只能贴近到期时间发送。 */
    private static final int FALLBACK_LOOKAHEAD_US = 8_000;
    private static final long FALLBACK_SLEEP_MS = 3;
    /** note-off 略提前，保证同 tick 的下一条 note-on 不被误杀。 */
    private static final long NOTE_OFF_NUDGE_US = 400;
    /** 等待调度线程退出的上限（避免停止时与正在投递的事件互相踩）。 */
    private static final long SCHEDULER_JOIN_MS = 100;

    /** 已交给合成器但还没到时间的 note-on，停止时按同一时间戳补 note-off，避免“余音绕梁”。 */
    private record PendingNoteOn(int channel, int pitch, long timestamp) {
    }

    /**
     * 投递进度（不可变）：EDT 重新锚定时整个替换，
     * 调度线程读到的永远是“某个完整瞬间”的 index + 循环偏移，不会拿到半新半旧的组合。
     */
    private record Cursor(int index, long cycleOffsetUs) {
    }

    private final SynthService synth;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingNoteOn> pendingNoteOns = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private volatile State state = State.STOPPED;
    private volatile Object owner;
    private volatile PlaybackPlan plan;
    private volatile boolean loopEnabled;
    private volatile LoopRange loopRange = new LoopRange(0, 0);

    private volatile long anchorMicros;
    private volatile long anchorNanos;
    private volatile long frozenTick;
    /** 计划代号：每次换计划 / 重新锚定就 +1，调度线程一旦发现代号变了就立即停手，不再发旧事件。 */
    private volatile long planEpoch;

    private volatile Cursor cursor = new Cursor(0, 0);
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
            // 等旧调度线程真正退出，否则它手上的旧批次会在新计划生效后继续发出去
            joinScheduler(stopSchedulerThread());
            planEpoch++;
            // 先把这条引擎留下的声音全收干净——包括已经排进合成器队列、还没到点的 note-on。
            // 不然上一次播放/定位残留的那些排队事件会在新一次播放的最开头“冒出其他音”。
            panic();
            pendingNoteOns.clear();
            synth.resetAllChannels();
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
            joinScheduler(stopSchedulerThread());
            panic();
        }
        fireStateChanged();
    }

    public void stop() {
        synchronized (lock) {
            state = State.STOPPED;
            joinScheduler(stopSchedulerThread());
            panic();
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
            // 不管在播还是暂停，定位前都先收干净（含排队中未响的 note-on），
            // 否则“回到开头”后那几个排队事件会晚一步冒出来
            panic();
            planEpoch++;
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
            planEpoch++;
            plan = newPlan;
            if (state == State.PLAYING) {
                panic();
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
                planEpoch++;
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
        // 主时钟必须用 nanoTime：Gervill 的 getMicrosecondPosition 是"已渲染帧数"，
        // 空闲/恢复时会一次性追帧（例如 16384 帧 ≈ 371ms），直接拿它算 elapsed 会跳变。
        return (System.nanoTime() - anchorNanos) / 1000.0;
    }

    private void anchor(long tick) {
        PlaybackPlan p = plan;
        if (p == null) {
            return;
        }
        long target = Math.max(0, tick);
        anchorMicros = p.tempo().microsAtTick(target);
        anchorNanos = System.nanoTime();
        cursor = new Cursor(p.indexAtTick(target), 0);
        frozenTick = target;
        sendStateUpTo(p, target);
    }

    /** 重放目标 tick 之前的非音符状态事件（program / CC / 弯音 / RPN），保证 seek 后调音一致。 */
    private void sendStateUpTo(PlaybackPlan p, long targetTick) {
        for (PlaybackPlan.Event event : p.events()) {
            if (event.tick() > targetTick) {
                break;
            }
            if (event.command() == ShortMessage.NOTE_ON || event.command() == ShortMessage.NOTE_OFF) {
                continue;
            }
            sendImmediate(event);
        }
    }

    private void startSchedulerThread() {
        Thread thread = new Thread(this::runScheduler, "midEditor-scheduler");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 2);
        scheduler = thread;
        thread.start();
    }

    private Thread stopSchedulerThread() {
        Thread thread = scheduler;
        scheduler = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            return thread;
        }
        return null;
    }

    /** 等调度线程真正退出：保证接下来 panic 时不会又有事件插进来。 */
    private void joinScheduler(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(SCHEDULER_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 立即消音：
     * <ol>
     * <li>对已交给合成器、但时间戳还在未来的 note-on，按同一时间点补发 note-off；</li>
     * <li>向所有通道发延音踏板松开、All Notes Off 与 All Sound Off（后者会直接掐断释放段）。</li>
     * </ol>
     * 两件事都做，才能避免“停止/暂停后那个音还在响”。
     */
    private void panic() {
        long now = synth.micros();
        PendingNoteOn pending;
        while ((pending = pendingNoteOns.pollFirst()) != null) {
            try {
                // 已经排进合成器队列的 note-on 没法真正撤回，只能在同一时间戳上紧跟一条
                // note-off + All Sound Off：按到达顺序处理，那个音连一个采样块都活不下来。
                long timestamp = now >= 0 ? Math.max(now, pending.timestamp()) : -1;
                synth.send(new ShortMessage(ShortMessage.NOTE_OFF, pending.channel(), pending.pitch(), 0),
                        timestamp);
                synth.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, pending.channel(), 120, 0),
                        timestamp);
            } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
                // 取消失败也无所谓，随后的 allNotesOff 会兵底
            }
        }
        synth.allNotesOff();
    }

    private void runScheduler() {
        try {
            while (state == State.PLAYING) {
                boolean clock = synth.micros() >= 0;
                dispatchDue(elapsedMicros() + (clock ? LOOKAHEAD_US : FALLBACK_LOOKAHEAD_US));
                // 用 parkNanos 代替 Thread.sleep：同样可被 interrupt 提前唤醒，且不占用时间片
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS
                        .toNanos(clock ? SCHEDULER_SLEEP_MS : FALLBACK_SLEEP_MS));
            }
        } catch (RuntimeException t) {
            // 调度异常不应导致 UI 崩溃
        }
    }

    private void dispatchDue(double horizonUs) {
        long epoch = planEpoch;
        PlaybackPlan p = plan;
        if (p == null) {
            return;
        }
        long synthNow = synth.micros();
        if (synthNow >= 0) {
            // 清掉已经到期的记录，队列只保留“还在未来”的 note-on
            pendingNoteOns.removeIf(pending -> pending.timestamp() <= synthNow);
        }
        List<PlaybackPlan.Event> events = p.events();
        Cursor c = cursor;
        while (state == State.PLAYING) {
            // 计划已被换掉（静音、独奏、编辑、seek）：这批旧事件立刻作废，
            // 否则旧计划里的音符会在新计划生效后又“漏”出去一小段
            if (epoch != planEpoch || plan != p) {
                return;
            }
            if (c.index() >= events.size()) {
                if (loopEnabled && loopRange.valid()) {
                    c = new Cursor(p.indexAtTick(loopRange.startTick()), c.cycleOffsetUs() + loopLengthUs(p));
                    cursor = c;
                    continue;
                }
                long endUs = p.tempo().microsAtTick(p.endTick()) - anchorMicros;
                if (elapsedMicros() > endUs + 200_000) {
                    SwingUtilities.invokeLater(this::pause);
                }
                break;
            }
            PlaybackPlan.Event event = events.get(c.index());
            if (loopEnabled && loopRange.valid() && event.tick() >= loopRange.endTick()) {
                c = new Cursor(p.indexAtTick(loopRange.startTick()), c.cycleOffsetUs() + loopLengthUs(p));
                cursor = c;
                continue;
            }
            double dueUs = p.tempo().microsAtTick(event.tick()) + c.cycleOffsetUs() - anchorMicros;
            if (dueUs > horizonUs) {
                break;
            }
            if (epoch != planEpoch || plan != p) {
                return;
            }
            sendScheduled(event, dueUs);
            c = new Cursor(c.index() + 1, c.cycleOffsetUs());
            cursor = c;
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
            // 每次事件单独取当前合成器时钟，按"剩余延迟"换算时间戳，
            // 这样即使 Gervill 刚追帧，也不会把事件排到错误的绝对时间。
            double remainingUs = dueUs - elapsedMicros();
            long synthNow = synth.micros();
            if (synthNow >= 0 && remainingUs > 2_000) {
                long timestamp = synthNow + Math.round(remainingUs);
                if (event.command() == ShortMessage.NOTE_OFF) {
                    timestamp -= NOTE_OFF_NUDGE_US;
                }
                synth.send(message, timestamp);
                if (event.command() == ShortMessage.NOTE_ON && event.data2() > 0) {
                    pendingNoteOns.addLast(new PendingNoteOn(event.channel(), event.data1(), timestamp));
                }
            } else {
                synth.send(message);
            }
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 单条事件失败不影响整体播放
        }
    }

    private void sendImmediate(PlaybackPlan.Event event) {
        try {
            synth.send(new ShortMessage(event.command(), event.channel(), event.data1(), event.data2()));
        } catch (javax.sound.midi.InvalidMidiDataException | RuntimeException ignored) {
            // 单条事件失败不影响整体播放
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
