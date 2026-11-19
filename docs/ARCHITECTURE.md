# midEditor 架构设计（经典 MDI 多音轨 MIDI 编辑器）

> 状态：设计待确认（v0.1）
> 技术栈：Java 21 + Swing/Java2D + javax.sound.midi (Gervill) + Maven 3.10.1 + JUnit 5

## 0. 技术栈决策

| 需求 | 选型 | 理由 |
| --- | --- | --- |
| MDI 窗口架构 | Swing `JDesktopPane` + `JInternalFrame` | Java 生态中唯一与 MFC/Word 2003 语义一致的 MDI 实现；不是 Tab，不是多顶层窗口 |
| 菜单/工具栏/状态栏 | `JMenuBar`+`JToolBar`+自定义 `StatusBar` | 原生助记符（Alt 激活）、`AbstractAction` 统一启用态 |
| 钢琴卷帘渲染 | Java2D 自绘（`paintComponent`）+ 离屏缓存 + 视口虚拟化 | 需要毫秒级重绘与精确控制「平行线」外观，控件级方案不可控 |
| MIDI 解析/生成 | `javax.sound.midi`（JDK 成熟库，非手写） | 零依赖，`MidiSystem.getSequence` / `MidiSystem.write` |
| 发声（真音色） | Gervill `SoftSynthesizer` + SoundBank | **已验证**：加载 `C:\Windows\System32\drivers\gm.dls`（Roland GS，235 个乐器采样）成功 |
| 可停靠工具窗口 | 自研轻量 DockManager | 避免引入重量级三方 docking 框架，行为可控且可测 |
| 测试 | JUnit 5（已有） | 领域/编解码/撤销隔离可用纯 JUnit 覆盖 |

已验证的环境事实：
- JDK 21.0.11 内含 `com.sun.media.sound.{SoftSynthesizer, SF2Soundbank, DLSSoundbank}`。
- `gm.dls`（3.4 MB）加载成功：`bank=GS sound set (16 bit)`，`instruments=235`，`Piano 1 / Nylon-str.Gt / Acoustic Bs. / Violin / Trumpet / Flute / Fantasia / Synth Drum` 全部可枚举，`synth.open()` 成功（存在可用音频设备）。
- 需要 `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`（同时写进 compiler、surefire、launch.json；运行时若缺该参数则走反射降级路径）。

## 1. 架构设计

### 1.1 对象职责

```
MainFrame (JFrame)                      应用外壳：标题栏 / 菜单栏 / 工具栏 / 状态栏 / 工作区
 ├─ AppContext                          组合根：注册表、服务定位（无全局 static 状态）
 ├─ MenuBarBuilder / ToolBarBuilder     Action 装配（与快捷键共用同一 Action 实例）
 ├─ StatusBar                           文档 / 音轨 / tick / 播放位置 / 缩放 / 脏状态 / 诊断
 ├─ DockManager                         LEFT|RIGHT|BOTTOM 停靠区 + 浮动工具窗口
 └─ MdiWorkspace (JDesktopPane)         MDI Client Area：子窗口层叠/平铺/最小化 + Window 菜单绑定
     └─ MdiChildFrame (JInternalFrame)  一个文档一个子窗口（可最大化/最小化/关闭）
         └─ DocumentView                视图基类：撤销栈 + 选区 + 脏状态 + 播放传输状态
             └─ MidiDocumentView        多轨编辑器：音轨头 + 标尺 + 卷帘堆叠 + 播放头 + 传输条
                 └─ MidiDocument        文档模型（被视图持有，1:1）
```

### 1.2 核心组件契约

**MainFrame（主框架窗口）**
- 拥有唯一 `JMenuBar`（文件/编辑/视图/窗口/帮助），全部菜单项为 `JMenuItem(Action)`，助记符与加速键由 Action 提供。
- 标题栏：`midEditor — <活动文档名>`；无文档时 `midEditor`。
- 持有 `MdiWorkspace`、`DockManager`、`StatusBar`，通过 `AppContext` 取得服务。

**MdiWorkspace（MDI Client Area）**
- `extends JDesktopPane`；`DesktopManager = DefaultDesktopManager`。
- API：`cascade()`, `tileHorizontally()`, `tileVertically()`, `minimizeAll()`, `restoreAll()`, `nextDocument()`, `activeDocument()`。
- 监听 `InternalFrameListener` + `PropertyChangeListener(VISIBLE/ICONIFIED/SELECTED)` 维护活动文档；子窗口关闭一律经 `DocumentManager.requestClose(doc)`（不直接 `dispose`）。
- 空工作区绘制提示文案（"文件 → 新建 / 打开 以创建文档"），不创建任何文档对象。

**MdiChildFrame（MDI 子窗口）**
- `JInternalFrame`，`resizable/closable/maximizable/iconifiable = true`，标题 = `文档名[*]`。
- 持有其 `DocumentView`；生命周期与 `MidiDocument` 绑定；销毁时注销监听、释放播放资源。
- 不持有任何跨文档共享状态。

**DocumentManager（文档管理）**
- 持有 `LinkedHashMap<UUID, DocumentSession>`；`DocumentSession = {MidiDocument, DocumentView, MdiChildFrame}`。
- API：`newDocument()`, `openDocument(Path)`, `saveDocument()`, `saveDocumentAs(Path)`, `exportMidi(Path)`, `requestClose(session)`, `requestExit()`, `setActive(session)`, `activeSession()`, `addListener(DocumentManagerListener)`。
- 未保存提示统一走 `UnsavedChangesDialog`（保存/不保存/取消）；`requestExit` 遍历全部会话，任一取消则中止退出。
- 活动文档变化 → 触发 `CommandStateService.refreshAll()` + 主框架标题更新 + Window 菜单选中项更新。

**DocumentView（视图基类，每文档独立）**
- 独立 `EditStack`（撤销/重做，禁止跨文档共享）、`DocumentSelection`、`dirty`、`PlaybackEngine` 绑定、`GridSettings`、`ZoomModel`。
- 声明 `createEditContext()` 提供 `CommandContext` 所需的 `activeDocument / activeTrack / selection`。
- `dirty` 由 `EditStack` 变更事件驱动；保存成功清零；标题星号随动。

**Command / CommandRouter（命令系统）**
```java
public enum CommandScope { SELECTION, TRACK, DOCUMENT, APPLICATION }

public interface Command {
    String id();
    CommandScope scope();
    Action action();                       // 菜单/工具栏/快捷键共用
    boolean isEnabled(CommandContext ctx); // 同一实例控制启用态
    boolean isChecked(CommandContext ctx); // 视图菜单勾选项
}
```
- `CommandRouter.dispatch(id)`：构建 `CommandContext{activeSession, activeTrack, selection, focusOwner}`，按 `SELECTION → TRACK → DOCUMENT → APPLICATION` 顺序解析；无活动文档时仅 APPLICATION 域可执行。
- `CommandContext` 为不可变快照；选区变化、音轨切换、文档激活、播放状态变化、撤销栈变化 → 统一刷新 Action 启用态（菜单项不可能是摆设）。
- 同一 `Action` 实例同时挂到 `JMenu`、`JToolBar`、`InputMap/ActionMap`，因此三者行为必然一致。

**撤销隔离**
- `EditStack` 属于 `DocumentView`；命令从 `CommandContext.activeSession.undoStack()` 取栈，禁止从全局获取。
- 编辑以 `TrackNotesSnapshotEdit`（受影响音轨的音符前后快照）或复合 `CompositeEdit` 表示；拖拽过程用 `Transaction` 聚合为单条撤销记录。
- 单元测试：两文档交错编辑 → 分别 undo，互不影响。

### 1.3 事件流（示例：右键添加平行线）

```
PianoRollContextMenu (点中空白) → CommandRouter.dispatch("note.add")
 → CommandContext(activeDoc=D, track=T, tick=草稿tick, pitch=草稿pitch)
 → AddParallelLineCommand → DocumentView.transaction(Track T)
     → track.notes.add(new Note(...))         // 领域模型，tick 单位
     → EditStack.push(snapshotEdit)           // 文档私有栈
     → fireNotesChanged(T, affectedTickRange) // 只重绘脏区
 → CommandStateService.refreshAll() → 菜单/工具栏/状态栏同步
```

## 2. 模块划分与目录结构

```
src/main/java/com/example/mideditor/
  MidiEditorApp.java              入口：LAF、异常处理、装配 MainFrame
  app/      AppContext.java MainFrame.java AppCommandIds.java
  command/  Command.java CommandRegistry.java CommandRouter.java CommandContext.java
            CommandStateService.java （各域命令在 edit/ view/ file/ playback/ 子包）
  mdi/      MdiWorkspace.java MdiChildFrame.java WindowMenuBinder.java
  document/ MidiDocument.java DocumentManager.java DocumentSession.java
            DocumentView.java MidiDocumentView.java DocumentSelection.java
            EditStack.java DocumentEdit.java TrackNotesSnapshotEdit.java Transaction.java
  domain/   Track.java Note.java TempoMap.java TimeSignature.java GridSettings.java
            TimeBase.java GmProgram.java PitchRange.java TrackColor.java
  io/       MidiFileCodec.java DocumentFileService.java
  engine/   PlaybackEngine.java SchedulerLoop.java AudioClock.java PlaybackPlan.java
            SoundBankProvider.java SynthesizerBackend.java GervillBackend.java
            ProceduralBackend.java Metronome.java PlaybackState.java
  ui/frame/ MenuBarBuilder.java ToolBarBuilder.java StatusBar.java
  ui/dock/  DockManager.java DockPanel.java DockRegion.java FloatingDockWindow.java
  ui/pianoroll/ PianoRollStack.java TrackLaneView.java TimeRuler.java PianoKeysStrip.java
                PlayheadOverlay.java MarqueeOverlay.java GridPainter.java
                ParallelLinePainter.java HitTester.java DragSession.java
                PianoRollContextMenu.java ViewportModel.java
  ui/tools/ TrackListPanel.java InstrumentPanel.java MixerPanel.java OutputPanel.java
            DiagnosticsPanel.java PropertiesPanel.java GridSettingsPanel.java
  ui/theme/ Theme.java Icons.java
  util/     Log.java Listener.java Ranges.java Fmt.java
src/test/java/com/example/mideditor/
  domain/TimeBaseTest.java io/MidiFileCodecTest.java
  document/EditStackIsolationTest.java command/CommandRouterTest.java
  ui/pianoroll/HitTesterTest.java engine/TempoSchedulingTest.java
```

约定：单文件 ≤ 400 行；`domain` 不依赖 Swing；`engine` 只依赖 `domain` + `io`；视图不直接改模型，一律经命令/事务。

## 3. 领域模型

```java
public final class MidiDocument {
    private final UUID id;
    private Path filePath;                 // null = 未保存过
    private boolean dirty;
    private final int ppq;                 // 默认 480
    private final TempoMap tempoMap;       // tick -> us/qn 分段
    private final List<TimeSignature> timeSignatures;
    private final List<Track> tracks;      // 共享同一时间轴
    // undo/redo 不在模型内：由 DocumentView 的 EditStack 持有（视图即文档会话）
}

public final class Track {
    private final UUID id;
    private String name;
    private int channel;                   // 0-15；鼓组固定 9
    private int program;                   // 0-127 GM
    private Color color;
    private boolean mute, solo;
    private int volume = 100;              // 0-127 -> CC7
    private int pan = 64;                  // 0-127 -> CC10
    private final List<Note> notes;        // 按 startTick 排序维护
    private PitchRange yRange;             // 钢琴卷帘可见音域（频率轴：可缩放 12-128 行 / 可滑动）
    private boolean drumKit;
}

public final class Note {
    private final UUID id;
    private int pitch;                     // 0-127
    private long startTick;
    private long durationTicks;            // > 0
    private int velocity;                  // 1-127（决定平行线粗细）
    private boolean rest;                  // true = 休止符（红色线段，不发声，其余待遇与平行线一致）
    private UUID lockLeaderId;             // 收尾锁定：指向被锁定的前一条的 id
}

public record PitchRange(int low, int high) {}
public interface DocumentEdit { String label(); void undo(); void redo(); boolean canMergeWith(DocumentEdit next); }
```

硬规则：
- 所有模型时间单位 = tick（`long`）。毫秒/秒仅出现在 `TimeBase`/`AudioClock`/播放调度与状态栏显示中。
- 数据模型类名 `Note`；所有 UI 文案、右键菜单、提示、文档统一称「平行线」。

## 4. 钢琴卷帘渲染方案

**布局几何**
- `pxPerTick = (pxPerQuarter * zoom) / ppq`，`pxPerQuarter` 基准 96；水平缩放 0.1×–16×。
- 每轨泳道高 = `128 * rowH`（`rowH` 默认 10 px，缩放 6–20 px）；每轨独立垂直滚动，共享水平滚动与缩放。
- 泳道堆叠顺序 = `tracks` 顺序；标尺与所有泳道共享 X 变换。

**分层绘制（自底向上）**
1. `GridPainter`：小节线/拍线/网格线（吸附网格）三层，颜色分层；黑键/白键行底色条带。
2. `ParallelLinePainter`（核心）：每条平行线 = 水平填充矩形，**方角、无边框、无圆角**；
   - 高度 = velocity 映射到 `[3, rowH-1]` px，垂直居中于音高行；
   - 颜色 = 音轨色 × 选中/发声状态叠加；
   - 选中：2 px 强调色描边 + 提亮；播放中发声：附加高亮光晕色；
   - 网格/小节线永远绘制在平行线之下，视觉上不混淆。
3. `MarqueeOverlay`：框选半透明矩形。
4. `PlayheadOverlay`：**单一覆盖层，横跨整个泳道堆叠区**（不是每轨一条），1–2 px 竖线 + 顶部三角标记。

**性能**
- 视口虚拟化：按 tick 区间二分查找可见平行线，只绘制可见部分 + 1 屏预取。
- 背景（网格 + 键盘条带）缓存为 `BufferedImage`，键 = (trackId, zoom, scrollX, scrollY, size, theme)，滚动/缩放变化才失效。
- 播放头动画只 `repaint` 播放头新旧位置的窄条（约 8 px 宽，见 `PianoRollView.repaintPlayhead`），不触发全量重绘；泳道内容变化用脏矩形合并。
- **局部重绘的硬约束**：`paint()` 里一切「位置固定」的装饰（活动音轨左侧高亮条、锁定圆点、循环区）都必须按 **tick / 视口边界**（`view.visibleRect().x`）定位，**绝不能**用 `clip.x` —— 播放头每帧只刷一条窄带，用 `clip.x` 就会在窄带起点一路留下竖线（曾经踩过：活动音轨高亮条变成"播放头竖线状尾迹"）。回归测试：`ActiveLanePaintTest`。
- **垂直方向禁止固定贴边装饰**：Swing 垂直滚动是「位块拷贝 + 只补新露出的一条带」，任何贴着视口上下边界画的装饰（播放头顶部三角曾经如此）会被连同内容一起搬走，屏上留下一串旧箭头（音轨多时向上翻尤其明显）。因此**画布里的播放头只有竖线**，顶端三角由 `TimeRuler`（列头，不参与垂直滚动、且按 tick 绝对定位）绘制。回归测试：`PlayheadArrowPaintTest`。
- **播放头动画只局部重绘 → 状态翻转的装饰必须整条重刷**：发声高亮是按 `isNoteSounding(note)` 整条换色的；窄带重绘只盖 ±8px，所以播放头跨过某条线的头 / 尾时只有那一小段换了颜色，剩下的仍是旧色（现象：“每条线末尾总有固定一小段不亮” / “走过去了还亮着”）。`PianoRollView.repaintHighlightChangedNotes(from,to)` 把 `startTick` 或 `endTick` 落在 `(from,to]` 里的平行线整条 `repaint`。回归测试：`PlayheadHighlightTest`。
- **时间网格必须分层**：小节（最亮、2px）> 拍（中等）> 细分（极淡），下层不重复画在上层位置；**细分线间距 < 8px 时整层不画**（`MIN_SUBGRID_PIXELS`）——否则缩小后整片糊成一块，根本分不出小节和拍（用户反馈原话：网格杂乱、没法辅助找节奏）。
- **网格分辨率必须对应真实时值**：`GridSettings.Resolution.ticksPerGrid` = 1/4→ppq、1/8→ppq/2、1/16→ppq/4、1/32→ppq/8、**1/8 三连音→ppq/3**、**1/16 三连音→ppq/6**（三连音旧实现写成 ppq*2/3 与 ppq/3，整整差一倍，下拉框选了也对不上画面）；`OFF` 时细分层完全不画（旧实现会回退成 1/16 网格继续画）。回归测试：`GridResolutionTest`。
- 双缓冲由 Swing 默认提供；动画由独立 `javax.swing.Timer`（60 Hz）+ EDT 合并重绘。

**长文件的复杂度红线（编辑路径必须线性）**
- `Track.reconcileRests()` 用「休止符 / 空隙双指针合并」O(rests+gaps)；旧实现是每条休止符扫全部空隙（平方级，2 万音符单次 200 ms+），而每次编辑/拖拽都会调它。
- 收尾锁定的闭包（`beginSelectionEdit` / `movingIds`）先用 `LockIndex`（id→音符、leader→跟随者）建索引再工作队列 BFS；旧实现是「每条音符扫全表」= O(n²)，长文件下一按下鼠标就卡。
- `Track.addNote` 顺序追加走 O(1) 快路径（导入长文件曾达 5 万音符 1.9 秒）；`reconcileRests` 补缺口时先追加再统一排序，不用线性插入。
- 回归测试 `LongFileEditPerformanceTest`（2 万音符，时间上限只用来兜住算法退化，不追求精确数值）。

**命中与交互**：`HitTester` 统一提供 `hitAt(x,y)`（命中平行线主体 / 右端手柄 6 px / 空白）；`DragSession` 三种模式：MOVE（pitch+time）、RESIZE_END（duration）、MARQUEE。

**输入模式（v1.1）**
- 状态挂在 `MidiDocumentView`：`inputMode` + 光标 `(inputCursorTick, inputCursorPitch)` + 空格按住的起始纳秒/**按下那一刻的位置**/正在响的试听音；无独立组件，只是画布上的一个装饰 + 一套全局按键路由。
- 按键走 `app/InputModeDispatcher`（`KeyEventDispatcher`）而不是画布键绑定：焦点可能在工具栏/状态栏；且**空格在输入模式下必须优先于「播放/暂停」**，消费掉事件就不会落到命令系统（`Ctrl+Space` 等带修饰键的仍放行）。
- **按住就要听得见**：`SynthService.previewOn/previewOff` 成对发 NOTE_ON / NOTE_OFF，音符长度与听到的长度天然一致；窗口失焦（Alt+Tab、弹窗）时由 `AppContext` 里的 `focusedWindow` 监听补一次 `endInputNote()`，否则松手事件永远收不到 → 卡音 + 幽灵条一直长。退出输入模式走 `abortInputNote()`（作废音符但一定收声）。
- 时长换算：松手时 `heldNanos → micros → tick`，用 `TempoMap.microsAtTick` / `tickAtMicros` 在光标 tick 处求逆（跨变速按段累加），**不吸附网格**——这就是「按住多久就是多久」。
- 绘制：`PianoRollPainter.paintInputCursor`（红色圆点 + 极淡的 tick 竖线）；按住空格时再画一条半透明「幽灵条」，长度取 `pendingInputTicks()`，由 60 Hz 的 `uiTimer` 在按住期间逐帧刷新。圆点与竖线都按 tick 绝对定位，滚动时随内容平移，不会被位块拷贝成残影。
- **节奏辅助（可选开关）**：`inputBeatSnap` 打开后，起点和时值都吸附到当前网格单位（`assistUnitTicks()` = `grid.step(ppq)`，网格关闭时退回一拍）。
- **关键：按“敲击间隔”落点，而不是每条音各自取整**。只把每条音的起点四舍五入到最近网格线，会让“起点=上一次起点+上一次吸附后的长度”，只要敲的间隔和按住时长不一致，后面每个音就会系统性地偏移。现在的做法：第一次按下时记锚点 `(assistAnchorNanos, assistAnchorTick)`，之后每次按下用 `microsToTicks(距锚点的真实时间)` 算出应该落在哪个 tick，再贴网格——所以敲四分就落四分、敲八分就落八分，不会被上一条音的取整误差带偏。
  - 锚点失效条件：手动 A/D 移光标、切换轨道、进出输入模式、开关辅助、撤销/重做、以及**停顿超过 1.5s**（`PAUSE_REANCHOR_NANOS`）——重新锚定到光标处，避免“走开一会儿回来，音被摆到三秒之后”。
  - 时钟可注入：`setNanoClockForTesting(LongSupplier)`（包内可见），测试可以精确控制“按了多久 / 隔了多久”。
  - 入口：工具栏第二个开关 + 编辑菜单勾选项；`InputModeTest` 里用假时钟验证“断奏每 480ms 敲一下 → 落点 0/480/960”。
- **光标可以翻动频谱**：`moveCursorTo` 不再把音高夹在可见音域内，而是 `scrollPitchWindowToCursor(track)` —— 光标越过窗口上/下边界时 `Track.shiftPitchRange()` 把窗口推过去（行数不变），再刷新键盘标尺与音轨头；切轨 / 重新进入输入模式时同样把新窗口翻到光标处。
- 入口：工具栏 `JToggleButton`（选中态跟随命令）+ 编辑菜单勾选项 + `Ctrl+I`。
- **撤销 / 重做要带上光标**：`endInputNote` 往 `inputSteps` 里压一条 `(track, noteId, beforeTick/Pitch, afterTick/Pitch)`；`MidiDocumentView.undo()` / `redo()`（命令层只调这两个）事后检查栈顶那条音符是否还在 —— 被撤销就退到 `before` 并压入 `undoneInputSteps`，被重做就退栈并跳到 `after`。别的编辑不会动光标。
- 回归测试 `InputModeTest`（17 项）+ `InputPreviewSoundTest`（1 项，假 Receiver 验证 NOTE_ON/NOTE_OFF 与 `activeNoteCount`）。
- **Q 是“按住一个音到底”**：`beginInputAudition()/endInputAudition()` 成对发 note-on/off，自动重复的 KEY_PRESSED 被 `inputAuditionHeld` 拦住；早期用 `audition(…, 220ms)` + 自动重复，听感是“一个音被反复敲”。

## 5. 播放调度方案

- **后端**：Gervill `SoftSynthesizer`（音频时钟权威来源），`Receiver` 支持绝对时间戳调度：`receiver.send(msg, timestampMicros)`，因此**不需要**逐音符 `Timer`。
- **调度循环**（独立线程，非 EDT）：每 25 ms 唤醒一次，向前看 250 ms；把该窗口内的 tick 事件（note-on/off、CC）换算成绝对微秒时间戳投递；窗口边界按 `PlaybackPlan`（已按 tick 排序、已过滤 mute/solo、每轨已分配 channel/program）推进。
- **时间换算**：`TempoMap` 预计算分段前缀和，`tick→micros`、`micros→tick` 均为 O(log n)。
- **播放头**：60 Hz 定时器读取音频时钟 `audioClock.micros()` → `tick`，只重绘播放头。`AudioClock` 优先取合成器时间基准，并与 `System.nanoTime()` 做漂移校正（低通平滑），保证长时间播放不漂移。
- **控制**：
  - 播放 / 暂停 / 停止（停止回到起点）/ 从头播放 / 循环（loopStart、loopEnd tick）。
  - **播放中定位**：`seek(tick)` 先对所有已发声音符发送 note-off，再发 `CC 120 / CC 123` 静音，清空未投递窗口，从新 tick 重新填充 —— 无缝续播。
  - BPM 修改：实时重建 `TempoMap` 前缀和，重新调度当前窗口。
  - 节拍器：独立通道 10 的点击音，强拍重音、可开关。
  - 循环：调度跨过 `loopEnd` 时回绕到 `loopStart` 继续填充。
- **并发**：模型在 EDT 上改，调度线程只读不可变快照 `PlaybackPlan`；编辑发生时递增 `planVersion`，调度线程懒加载新快照（无锁、无竞态）。

## 6. 音色方案

**主方案：SoundBank 采样（真音色，已验证）**
- `SoundBankProvider` 解析顺序：
  1. `./soundfonts/*.sf2`（用户自带，如 GeneralUser GS.sf2，取第一个）；
  2. `C:\Windows\System32\drivers\gm.dls`（Roland GS 235 乐器，离线可用）；
  3. JDK 默认 SoundBank（最后兜底）。
- 每音轨 → MIDI channel + `ProgramChange(program)`；`volume→CC7`、`pan→CC10`；鼓组轨固定 channel 10；mute/solo 在 `PlaybackPlan` 层过滤（solo 存在时仅 solo 轨发声）。
- 加载路径：`SoftSynthesizer.open()` + `loadAllInstruments(bank)`；通过 `--add-exports` 直接引用，缺失时反射调用（保证不加参数也能跑）。

**降级方案（无可用 SoundBank / 无音频设备时）**
- 回退到 JDK 内置默认 SoundBank，并在「诊断」「状态栏」中如实标注后端名称。
- 不实现伪音色（不用单一正弦冒充乐器）；无设备时播放命令不产生声音，但界面、编辑、保存不受影响。

## 7. 验收清单

| # | 验收项 | 判定方式 |
| --- | --- | --- |
| 1 | 启动后主界面为空，无任何文档窗口 | 目视 + `DocumentManager.sessionCount()==0` |
| 2 | 文件→新建/打开 后出现 `JInternalFrame` 子窗口 | 目视 + `desktop.getAllFrames().length` |
| 3 | 可同时打开多文档并切换活动文档 | 窗口菜单、Ctrl+Tab 式切换、标题栏跟随 |
| 4 | 菜单/工具栏/快捷键路由到活动文档 | 两文档交错操作，仅活动文档变化 |
| 5 | 子窗口内多音轨卷帘共享时间轴 | 标尺/水平滚动/缩放联动，播放头贯穿全部泳道 |
| 6 | 编辑对象是「平行线」，平行横轴，粗细反映 velocity | 目视；`ParallelLinePainter` 无圆角无边框 |
| 7 | 右键可添加/删除/拆分/移动/拉长/缩短，可插删休止 | 逐项操作验证 |
| 8 | 拖拽可移动/拉长缩短/框选批量编辑 | 逐项操作验证 |
| 9 | 播放头可拖动定位、可随时播放/暂停、循环 | 播放中 seek 不爆音不卡顿 |
| 10 | 多轨按各自 GM 音色同时发声 | 听感：钢琴/贝斯/弦乐/鼓组可辨；非单一正弦 |
| 11 | 撤销/重做按文档隔离 | `EditStackIsolationTest` + 手工验证 |
| 12 | 保存/导出 .mid 后重新打开可还原 | `MidiFileCodecTest` 往返一致（音符/音高/时值/力度/乐器/音量/声像） |
| 13 | 视图菜单可控制工具窗口与子窗口排列 | 7 个工具窗口显隐；层叠/平铺/最小化/最大化/关闭 |
| 14 | 关闭脏文档与退出时提示保存 | 三分支（保存/不保存/取消）逐项验证 |
| 15 | 无文档时菜单项正确禁用 | 目视所有文档级菜单灰显 |

## 8. 分步实施计划

| 步骤 | 内容 | 完成标志 |
| --- | --- | --- |
| S0 | Maven `--add-exports` 配置、LAF、MainFrame 外壳（菜单/工具栏/状态栏/空桌面）、Command 骨架 | 启动为空界面，菜单可 Alt 激活且全部灰显/可用正确 |
| S1 | domain + io + DocumentManager + MDI 子窗口 + 新建/打开/保存/另存/关闭提示 + 窗口菜单 + 每文档 EditStack | .mid 往返成功，撤销隔离测试通过 |
| S2 | 钢琴卷帘堆叠、键盘条带、标尺、网格、平行线绘制、共享水平滚动/缩放、独立垂直滚动 | 多轨可视，平行线渲染符合规范 |
| S3 | 右键菜单全项 + 拖拽 move/resize/框选 + 剪贴板 + 量化/力度 + 插删休止 + 快捷键 | 全部编辑项可撤销 |
| S4 | PlaybackEngine + Gervill + gm.dls + 播放头覆盖层 + 传输条 + 循环 + 节拍器 + 播放中 seek | 多轨真音色齐奏，播放头无漂移 |
| S5 | DockManager 七个工具窗口 + 混音器/乐器/属性/网格设置 + 状态栏细节 + 诊断/输出 | 视图菜单控制全部面板 |
| S6 | 性能优化、单元测试补全、完整验收走查 | `mvn test` 全绿 + 验收清单 15 项通过 |

现有 `com.example.App` / `AppTest` 将在 S0 替换为新的入口与测试结构。

## 9. 实现状态（v1.0，已交付）

已按 S0→S6 全部实现并通过 `mvn test`（32 项）：

| 模块 | 实际实现 |
| --- | --- |
| 主框架 | `app/MainFrame`：菜单栏（Alt 激活 + 助记符）、工具栏、状态栏、停靠区 + `MdiWorkspace` |
| MDI | `JDesktopPane` + `JInternalFrame`；层叠/平铺/最小化/还原；「窗口」菜单动态文档列表；新建/打开文档默认在客户区内最大化 |
| 文档管理 | 启动为空；新建/打开/保存/另存/导出/关闭提示/退出检查；多文档活动切换 |
| 命令 | `AppCommand` + `CommandRegistry` + `CommandRouter`（选中→音轨→文档→应用），Action 复用 |
| 撤销 | 每文档 `EditStack`；跨轨批量用 `CompositeEdit`；拖拽聚合为单条记录 |
| 卷帘渲染 | Java2D 自绘「平行线」：方角无边框，高度=velocity；共享时间轴，逐轨音域窗口 |
| 编辑交互 | 右键全项、拖拽移动/拉伸、框选、剪贴板、量化、力度、插删休止、快捷键 |
| 播放 | `PlaybackEngine` lookahead（20ms/260ms）+ 合成器时钟；播放头贯穿全轨；seek/循环/节拍器/BPM |
| 音色 | Gervill + `gm.dls`（或 `soundfonts/*.sf2`）；每轨 program/CC7/CC10；鼓组 ch10 |
| 文件 | `javax.sound.midi` 读写，往返保留音符/力度/乐器/音量/声像/速度/拍号 |
| 工具窗口 | 7 个 DockPanel，支持显示/隐藏与浮动 |

与早期设计的差异（均为实现期决策）：

1. **音色降级**：未引入程序化合成后端；无音色库时退回 JDK 默认 SoundBank 并在诊断中标注。
2. **逐轨垂直滚动**：以可缩放的「音域窗口」实现（音轨头 ▲▼＋－、Alt+滚轮滑动、Shift+滚轮/键盘标尺滚轮缩放，行数 12–128），未绘制独立滚动条。
3. **未使用 `--add-exports`**：音色库经公共 API `MidiSystem.getSoundbank(File)` 加载，运行无需任何 JVM 参数。
4. **播放调度时钟**：优先使用合成器微秒时钟（反射 `getMicrosecondPosition`），不可用时退回 `System.nanoTime()`。
5. **主框架标题**：`midEditor — <文档名><*>`；窗口菜单选中项与活动文档同步。
## 10. v1.1 交互修订

| 问题 | 实现 |
| --- | --- |
| 音轨添加/切换不易发现 | 新增顶层「音轨(T)」菜单；子窗口传输条新增「＋轨 / －轨」；音轨头**任意位置单击**即切换（双击名称重命名）；添加/切换后自动滚动到对应泳道 |
| 单击平行线试听 | `SynthService.audition(...)`：program/CC7/CC10 + note-on，定时 note-off；`PianoRollCanvas` 在"按下未拖动"释放时触发；任何文档播放期间不叠加试听 |
| 新建平行线默认时长 | `GridSettings.NoteLength`（跟随网格 / 1/4 / 1/8 / 1/16 / 1/32 / 1 小节 / 自定义 tick）；右键空白菜单与「网格设置」面板均可修改，1 小节按文档拍号计算 |
| Y 轴钢琴键盘标尺 | `PianoKeysColumn` 位于行头最左，逐泳道按各自音域绘制白/黑键与 C 音标注；与 GM 音色无关；视图→键盘标尺（Ctrl+K）或网格设置面板可显隐 |
| 保存/重开后音高偏移 | 根因：①重新打开时每轨音域窗口被 `autoRangeFromNotes` 重定位；②弯音/RPN 调音等通道事件保存时被丢弃；③合成器通道残留上一个文档的调音状态。修复：`RawChannelEvent` 保留弯音/RPN/踏板等并参与回放；每次播放/试听前 `resetAllChannels()`（弯音回中、RPN 归零），seek 时重放目标位置前的状态事件。~~写入 `mideditor:yRange=` 标记并恢复~~（视图状态现已不再入文件，见 v1.13） |
| 平行线长度≠实际时长 / 相邻线一起响 | 根因：`SoftSynthesizer#getMicrosecondPosition` 属未导出包，反射被拒 → `micros()` 返回 -1，引擎退化为把 260ms lookahead 内的事件**立即发送**。修复：jar 清单加 `Add-Exports: java.desktop/com.sun.media.sound`（surefire/launch.json/app:run 同步加 `--add-exports`）恢复采样级时间戳调度；无时钟时降为 8ms 前瞻 + 3ms 轮询；note-off 额外提前 400µs，保证同 tick 的下一条 note-on 不被误杀 |
| 保存到已存在文件无提示 | `DocumentFileService.chooseSave` 增加覆盖判定：目标存在时弹出「确认覆盖」（是/否），选择“否”则取消；目标为目录时给出提示。`ensureOverwriteAllowed(Path, Supplier<Boolean>)` 抽成可测试函数 |
| 停止后重新播放，播放头瞬移数小节 | 根因：Gervill 的 `getMicrosecondPosition()` 是「已渲染帧数」，空闲后会一次性追帧（实测 16384 帧 ≈ 371ms），播放头以它为锚点就会瞬间多出数百 ms。修复：播放头/自动暂停等一律用单调的 `System.nanoTime()` 计算 elapsed；事件时间戳改为每条发送时「当前合成器时钟 + 剩余延迟」，不再使用固定合成器锚点 |
| Ctrl+S 无反应 | 根因：`chooseSave` 里 `path` 被重新赋值后又进 lambda（非 effectively final），VS Code 的 ECJ 把带错误的类写进 `target/classes`，Maven 增量编译又跳过了它 → 运行时抛 `Error: Unresolved compilation problems`，而 `CommandRouter` 只 `catch (Exception)` 所以静默。修复：拆出 `ensureMidiExtension` 使 lambda 捕获只读变量；`CommandRouter` 改 `catch (Throwable)` 并记录日志；补 `Ctrl+Y` 重做、`F12` 另存为别名与对应测试。验证请用 `mvn clean test/package`，避免旧/ECJ 类残留 |

另：Maven 安装在 `D:\Tools\apache-maven-3.10.1`，已写入用户级 `Path` / `MAVEN_HOME`；仓库内的 Maven Wrapper（`mvnw*`、`.mvn/`）与临时构建脚本已删除。
测试增至 20 项（含默认时长、键盘标尺、试听事件投递、音域/弯音保真、同 tick 的 note-off 顺序与音频时钟可用性）。

## 11. v1.2 频率轴（音域）可缩放

用户反馈「频率谱不够用」：原先每轨固定 30 行（45–74 左右），跨度大的曲子会有平行线被挡在可见音域之外，且只能用 ▲▼ 一格格挪。
本次把 Y 轴（音高 = 频率）做成可缩放、可滑动的轴：

| 维度 | 实现 |
| --- | --- |
| 数据模型 | `PitchRange` 新增 `MIN_ROWS=12` / `MAX_ROWS=128` / `withRows()` / `fitting()`；`Track.pitchRows()` / `setPitchRows()` / `shiftPitchRange()`。行数（可见半音数）就是缩放级别，泳道像素高度固定（`Theme.LANE_HEIGHT`），所以缩放只改行高，不影响多轨排列 |
| 几何换算 | `PianoRollView.rowTop/rowBottom` 改为 `round(行序号 × 泳道高 ÷ 行数)`，保证 12–128 行都能**整数像素铺满泳道、无缝隙无重叠**；`pitchAt()` 用整数反算，命中测试与拖拽改音高在任意缩放下都准 |
| 鼠标滚轮（v1.3 统一） | **裸滚轮**：在卷帘 / 键盘标尺上 = 上下滚动频谱；在左侧音轨信息区 = 滚动一屏放不下的音轨。`Shift+滚轮` = 左右滚动；`Alt+滚轮` = 左右缩放（锚点 = 鼠标处 tick）；`Ctrl+滚轮` = 上下缩放（锚点 = 鼠标处音高）。缩放锚点由 `PianoRollView.wheelZoomTime/wheelZoomPitch` 保证：缩放前后鼠标下的 tick / 音高停在同一像素（`PitchRange.aligned(rows, desiredHigh)`） |
| 菜单 / 快捷键 | 视图 → 音域（频率轴）：放大 / 缩小 / 重置为 30 个半音 / 适配本音轨平行线 / 显示全部音域（128）；`Ctrl+Alt+=` `Ctrl+Alt+-` `Ctrl+Alt+0`；右键菜单与「网格设置」面板同步提供行数微调框（可应用到全部音轨） |
| 渲染自适应 | 平行线粗细按行高换算（始终 ≥1px，力度映射保持不变）；键盘标尺在行高 <7px 时省略八度文字，避免 128 行时文字糊成一片 |
| 导入自适应 | `Track.autoRangeFromNotes()` 改用 `PitchRange.fitting()`：音域跨度超过 30 个半音时自动放大窗口，导入的 .mid 不再有「看不见的音」 |

测试增至 32 项（新增 `PitchAxisTest` 6 项：行数双向钳位与中心保持、跨度自适应、12/30/60/128 行下的行几何与 `pitchAt` 反解、滚轮滑动/缩放、键盘标尺滚轮、音域适配与重置）。

### v1.4 停止/暂停后“余音绕梁”

根因有两层：
1. **调度线程与停止竞争**：scheduler 不受 `lock` 保护，停止瞬间可能刚好又投递出一条 note-on，而 `state != PLAYING` 后它的 note-off 永远不会再发 → 卡音。
2. **lookahead 已排队的事件**：合成器队列里最多还有 260ms 的未来 note-on，`CC123/CC120` 只能作用于已开始的音，对这些“还没响”的无能为力；而且延音踏板（CC64）踩下时 All Notes Off 会被留住变成余音。

修复：
- `PlaybackEngine.stop()/pause()` 先 `state = 非播放` ↓ interrupt 调度线程 ↓ **join(100ms)**，确保不再有新事件插进来。
- `PlaybackEngine` 用 `pendingNoteOns` 记录“已交给合成器但时间戳还在未来”的 note-on；`panic()` 先对这些事件按同一时间点补发 note-off（微秒级 +1 避免顺序问题），再调 `allNotesOff()`。
- `SynthService.allNotesOff()` 改为每通道依次发 **CC64=0（松踏板）→ CC123（All Notes Off）→ CC120（All Sound Off）**，并清空活动音符集合；新增 `activeNoteCount()` / `noteOffsSent()` 便于测试与诊断。
- `seek()` / `updatePlan()` 的静音也统一走 `panic()`。

新增 `PlaybackEngineTest.stopSilencesSoundingNotes`（长音 + 已排队未响的音，停止后 `activeNoteCount()==0` 且确实补发了 note-off；暂停同样验证），测试总计 41 项。

### v1.5 音域完整性与 Do（C）高亮
- **音域是完整的**：`Note.pitch` 夹紧在 `0..127`，`PitchRange` 上限 128 行，即 MIDI 全部 128 个半音（C-1 到 G9）都能建模、编辑、播放与显示；钢琴 A0(21)–C8(108)、鼓组 35–81 都包含在内。之前“看不到”只是默认音域窗口只有 30 个半音，拉满 128 行（Ctrl+Alt+0 后再缩，或「显示全部音域」）即可全见。新增 `PitchAxisTest.pitchRangeCoversEveryMidiPitch` 固定这个约束。
- **Do（C）高亮**：不再用色块/描边，而是在 `pitch % 12 == 0` 的行**内部叠加一层半透明黄色**（`Theme.C_TINT`，alpha 0x3A），键盘标尺对应键叠 `C_TINT_KEY`（alpha 0x5C）+ 深琥珀色八度标注；既不抢音轨平行线的颜色，也不在频谱里多出一堆框线。可用 视图 → Do（C）高亮 / Ctrl+Shift+K 或网格设置面板开关（默认开）。

### v1.6 拖拽吸附：网格 + 贴相邻平行线

- `MidiDocumentView.applySelectionMove` 先按“第一个被拖动的平行线”为锚点计算一次吸附量，再整体平移（多选时相对间距不变）。
- 吸附优先级：**同泳道其它平行线的两端**（自己的起点贴到上一条的终点 = 紧接上一个；自己的终点贴到下一条的起点 = 正好让出下一个）> 网格吸附（`GridSettings.snap`）。
- 吸附半径按像素换算成 tick（`EDGE_SNAP_PIXELS = 8`，随缩放换算），手感与缩放无关；`吸附到网格` 关掉时不做任何吸附；纯上下拖动（`deltaTicks == 0`）不改横坐标。
- 新增 `SnapToNeighbourTest` 4 项（贴上一条终点、贴下一条起点、多选保持相对间距、关闭吸附时保持原值），测试总计 47 项。

### v1.7 休止符实体化 + 收尾锁定

**休止符不再靠"没有平行线"表示**，而是正式对象：

- `Note` 增加 `boolean rest` 与工厂方法 `Note.rest(pitch, start, dur)`；`copy()` 保留该标记，`notesEqual` 一并比较，所以撤销/重做、剪贴板、拆分都认得它。
- 渲染：`PianoRollPainter.paintRest` 画一条**红色线段**（`Theme.REST_COLOR`，两端有小竖线，选中时用 `REST_SELECTED` 描边），位置、长度、命中测试与平行线完全一致 —— 可框选、拖动、拉长、量化、改力度、拆分、锁定、删除。
- 语义：`PlaybackPlan` 跳过 `note.isRest()`；`auditionNote` 也直接返回，所以它不发声、不占用合成器通道。
- 持久化：`MidiFileCodec` 不为休止符写音符事件（否则别的 DAW 会当成真音），而是写一条自定义 text meta `mideditor:rest=<pitch>,<start>,<duration>`；读文件时 `findStoredRests` 解析回来并挂到该轨第一个 `ChannelData`（轨道为空时新建一个），因此保存 → 重新打开不丢。

**收尾锁定**给两条平行线的接缝上锁，形成"这一句"的整体：

- `Note.lockLeaderId`（UUID）指向被锁定的前一条，`locked()` 即非空；锁定处画**空心圆**（`Theme.LOCK_RING`，先用行底色挖空再描边，跨音高时补一条半透明竖线）。
- 锁定入口 `MidiDocumentView.lockSelection()`：选中 ≥ 2 条按时间顺序逐对 `link()`（前一条的 `endTick` 作为后一条的 `startTick`）；只选 1 条时用 `nextAfter()` 找同轨后面最近的一条来连。
- 移动入口统一走 `movingIds(track)`：从选中集合出发，沿 `lockLeaderId` 双向闭包，把整条链一起纳入；`moveSelection`（方向键）与 `beginSelectionEdit` / `applySelectionMove`（鼠标拖拽）都用它，所以拖任意一段都是整组平移、接缝不会散，吸附也只按整组算一次。
- `unlockSelection()` 双向清理（自己锁别人的、别人锁自己的都解开）；删除平行线时顺手清掉指向已删对象的悬空锁。
- 新增 `RestAndLockTest` 4 项（休止符不发声但可插入/选中、锁定后首尾对齐并整组移动、解除后独立移动、休止符 .mid 往返），测试总计 51 项。

**锁定与「长度」的关系：只移动，不缩放**（v1.7 修订）

- 拖动右端 / `Shift+←→` 只改**用户真正选中的那条**的长度：拖拽事务里 `beginSelectionEdit` 额外记 `primarySelection`，`applySelectionResize` / `resizeSelection` 只按它改 `duration`，因锁定被纳入的同伴不参与缩放。
- 长度变化后由 `reflowLocks(tracks)` 重新贴齐：后一条的 `startTick = 前一条.endTick`（**只移位置**），沿链传递，`guard` 限 512 次防成环。于是「拖 B 的右端」锁点不动、A 不变长；「拖 A 的右端」锁点本身就是 A 的尾巴，B 只整体后移而长度不变。
- 同一思想贯穿其余编辑：`quantizeSelection`（锁点优先于网格）、`insertRest` / `deleteRest`（整段位移后重新贴齐、被删对象留下的悬空锁一并清掉）、`splitSelectedAt`（拆分只改长度：左段保留被锁身份、右段不继承锁，指向被拆对象的引用改指到**尾段**）。`link()` 增加成环保护（A 锁 B 时拒绝 B 再锁 A）。
- 测试：`RestAndLockTest` 扩到 7 项（新增拖后一条、拖前一条、Shift+→ 调长度三种情形的长度与锁点断言），总计 54 项。

### v1.8 播放中换计划不再“漏音”（静音偶发失效）

现象：两条音轨、其中一条静音，从头播放，偶尔会有一瞬间听到静音轨的声音。

根因（调度线程与 EDT 的竞态）：`dispatchDue` 一次性把 lookahead 窗口（~260ms）内的事件发给合成器，循环里**不复检计划身份**。当 EDT 在 `updatePlan()` 里换上新计划（静音 / 独奏 / 编辑 / seek 都会触发）并重新锚定时，调度线程手里还是**旧计划的 events 列表**，但 `anchorMicros` / `nextIndex` 已被改成新计划的基准，于是旧事件算出的 `dueUs` 变成负数 → 一批本该被静音掉的 note-on 被立刻发出去，听起来就是“静音失效了一瞬间”。

修复：
- `planEpoch`（volatile）：`play` / `updatePlan` / `seek` / `setLoopEnabled` 每次换计划或重锚定都 +1。
- `dispatchDue` 开头记住 `epoch` 与 `p`，**每发一条前复检** `epoch != planEpoch || plan != p` → 立刻 `return`，旧批次作废；新批次由下一轮 20ms 唤醒用新计划继续。
- `play()` 改为 `joinScheduler(stopSchedulerThread())`（原来只 interrupt 不等），并 `pendingNoteOns.clear()`，避免上一轮的残留批次在新一轮开始后继续投递。
- `panic()` 里“取消已排队 note-on”改成 same-timestamp（原来是 `+1µs`，会跨一个采样块，反而能听见一下）。

验证：`MuteLeakTest` 2 项 —— 用注入的 `SynthService.forTesting(Receiver)`（无音频设备、走 nanoTime 回退分支）把“批次投递中”这个瞬间用小门闩固定住，在批次中途 `updatePlan()` 静音，断言此后伴奏轨 note-on 数为 0（修前实测漏出 42 个），并断言未静音轨照常发声；另一项验证“静音后从头播放”全程无该轨音符。总计 56 项。

### v1.9 空隙自动休止符（无声即休止，实体化）

原本休止符只能是用户手工插入的对象；但「两条平行线之间没声音」本身就是休止符 —— 手工去补一段红线段既琐碎又容易漏，而且只标红不实体化等于没给休止符「同等地位」（选不中、锁不住）。现在改成**自动实体化**：

- `domain/GapRests.of(Track)`：把音轨里**发声区间求并集**（休止符不发声，天然被并集跳过），并集内部的空洞就是空隙；只取「第一个音开始之后、最后一个音结束之前」（首尾不加），返回 `Span(pitch, startTick, endTick)`；落在哪一行取**结束于该空隙起点的那一条**的音高。
- `Track.reconcileRests()`：把空隙变成真正的 `Note.rest` 对象。**已存在且与某段空隙重叠的休止符就地改写**（保留 id，所以锁定关系不会断），落在空隙之外的删掉，缺的补上。调用点：`MidiDocumentView.afterEdit()`（每次编辑 / 撤销 / 重做后）与 `commitMultiEdit()`（拍撤销快照之前，保证快照前后自洽）、以及拖拽过程中的 `applySelectionMove` / `applySelectionResize`。
- 结果：休止符与平行线**完全同地位** —— 框选、单击选中、`Shift+←→` 调长度、力度、拆分、收尾锁定、右键菜单、撤销/重做、`.mid` 存盘（`mideditor:rest=`）全都适用；`PlaybackPlan` 仍然跳过它不发声。
- 编辑语义（因为几何由空隙决定，操作必须落到「音」上）：
  - **拖动 / 拉长休止符** = 把这段静音拉长或缩短：它后面的音整体平移（`restRippleStarts` 记录最初的起点，绝对位移，多次拖拽不累积误差），休止符自身几何交给 `reconcileRests` 重算。
  - **删除休止符** = 把后面的音整体前移补齐，空隙消失，休止符自然也没了。
  - **把后面那条音拖到贴上** → 空隙消失 → 休止符自动删除。
  - 只有「选中的全是休止符」时走 ripple；混选（平行线 + 休止符）仍按普通平移处理，避免误伤。
- 移除了手工「插入休止符」命令与「空隙休止符」显示开关：休止符不再需要手工插入，也不再有“只标红不可编辑”的第二套视觉。
- 测试：`GapRestsTest` 7 项（空隙识别：普通空隙、首尾不越界、重叠与和弦、声部交错、手绘休止符仍属空隙、紧邻无缝、空音轨）+ `AutoRestTest` 6 项（打开文件即刻实体化、自动实体化、拖动推音、贴合即消失、删除补齐、撤销回滚）；`RestAndLockTest` 改为验证「空隙生成的休止符可选中、可锁定、不发声」。总计 69 项。

### v1.3 滚轮语义统一 + Windows 下「按过 Alt 后滚轮失效」修复

滚动/缩放规则收敛成一张表（画布、键盘标尺、音轨头共用 `PianoRollView.wheel*` 方法）：

| 位置 | 裸滚轮 | Shift+滚轮 | Alt+滚轮 | Ctrl+滚轮 |
| --- | --- | --- | --- | --- |
| 卷帘 / 键盘标尺 | 上下滚动频谱 | 左右滚动 | 左右缩放 | 上下缩放 |
| 左侧音轨信息区 | 滚动一屏放不下的音轨 | 左右滚动 | 左右缩放 | 上下缩放 |

缩放锚点：`wheelZoomTime` 以鼠标处 tick 为锚点重设 `pxPerQuarter` 并回推视口位置（`viewX' = viewX + newX - mouseX`）；`wheelZoomPitch` 由鼠标 y 反算连续音高，用 `PitchRange.aligned(target, anchorPitch + offset×target/laneHeight)` 重建窗口，缩放前后鼠标下的 tick / 音高停在同一像素。

**「按过 Alt 之后滚轮完全没反应」根因**：单独按一下（或按住又松开）Alt 时，Windows 会让窗口进入系统「菜单模式」（`WM_SYSKEYUP`），此期间 OS 直接**丢弃滚轮消息**（键盘与点击仍正常），必须再按一个键或点击一次才会恢复 —— 这就是“滚轮时灵时不灵”的真因。修复：`app/AltReleaseWheelFix` 监听裸 Alt 抬起，用缓存的 `Robot` 补发一个 ESC（本应用不占用 ESC）让窗口立即退出菜单模式；仅在主窗口为活动窗口、且无弹出菜单选择时补发。实测：Alt 抬起后裸滚轮 45→44（修复前为无事件）。

新增 `PitchAxisTest` 用例（统一修饰键语义、Ctrl 缩放锚点音高不变、裸滚轮滑动），测试总计 34 项。

### v1.10 缩放也要吸附，且连续的一组缩放后不能拉出缝

两个并列的手感缺陷，根因都在 `MidiDocumentView` 的两条缩放入口上（`applySelectionResize` 鼠标拖右端 / `resizeSelection` 长度四键）：

1. **缩放完全不吸附**。`PianoRollCanvas.mouseDragged` 的 `RESIZE` 分支直接把像素差换算成 `rawTick` 交给 `applySelectionResize`，后者 `setDurationTicks(origin[2] + deltaTicks)` 原样落值；移动（`applySelectionMove`）有 `snapToNeighbour` + 网格吸附，缩放却没有，于是右端可以停在任意 tick 上。
   修法：`resizedDuration(note, 之前区间, delta)` 把**被拉的那条边**（`之前末尾 + delta`）过一遍 `snapResizeEdge`（= `grid.snap(...)`，吸附开关或网格分辨率关掉时原样返回），长度按 `吸附后末尾 - 当前起点` 反算；起点不动。休止符那条 ripple 分支同理：以被拖休止符的末尾为锚点把位移量吸成整格（`snapRippleDelta`）。
2. **多选一起缩放会凭空长出休止符**。每条各加同一个 `delta`，缩短时每条的右侧都多出 `delta` 的空隙，`reconcileRests` 忠实地把这些碎缝实体化成红色休止符。锁定时看起来正常，是因为 `reflowLocks` 会把锁住的同伴「移回来」。
   修法：缩放前先拍一份 `{起, 止}`（`timeSnapshot` / `primaryTimesBefore`），缩放后调 `glueSelectionChain(tracks, before)`：把选中项按**缩放前**的顺序排好，若相邻两条原本 `前.止 == 后.起`，就把后一条的 `startTick` 拉回 `前一条.endTick`（**只移位置、不改长度**，与 `reflowLocks` 同一套思想），然后逐条把末尾再吸一次网格（起点跟着接缝走了，末尾必须重吸，否则接缝对齐后末尾又会落回网格外）。判据用「缩放**之前**是否相接」，所以本来分开的音符不会被硬吸到一起。

验证：`ResizeSnapTest` 4 项（单条/多条拖右端后末尾都在网格线上、键盘改长度同样吸附、关掉吸附才保留原始位移）＋ `ContiguousResizeTest` 4 项（三条一起缩短/加长后仍首尾相接且不冒休止符、键盘缩短同样成立、不相接的两条不被吸到一起），测试总计 120 项。

### v1.11 迷你左栏、F5 播放键、Ctrl+I 三态循环

- **左侧工具窗口缩成迷你**：`DockManager` 不再写死 250，改在 `doLayout()` 里 `applyMiniLeftWidth()`——宽度取窗口的 1/4，再夹到 `[MINI_LEFT_MIN=120, MINI_LEFT_MAX=160]`（纯 1/4 在 1200px 窗口上是 300、在 400px 上是 100，两头都不好用）。配套把 `DockPanel` 的「浮/×」按钮 margin 归零（22px 才装得下单个汉字），`TracksPanel` 的四个按钮由「添加 删除 上移 下移」改成 `＋ － ▲ ▼` + tooltip，否则 160px 一行放不下会折行。
- **播放键**：`PLAY_PLAY_PAUSE` 主键 空格 → **F5**、`PLAY_STOP` Ctrl+空格 → **Ctrl+F5**、`PLAY_REWIND` Ctrl+Home → **F6**；旧键用 `CommandRegistry.bindAlias` 保留为备用（`CommandRoutingTest` 里断言过空格仍然映射到播放，别名不破坏它）。
- **Ctrl+I 三态循环**（`EditCommands` 的 `EDIT_INPUT_MODE` 处理器）：`关 → 输入模式＋节奏辅助 → 输入模式（无辅助） → 关`。第一下就落到「带辅助」那档，第二下只关辅助（光标还在），第三下退出。`setInputMode` 本身不动 `inputBeatSnap`，所以「先开模式再开辅助」不会互相覆盖。
- 新增 `DockMiniWidthTest` 3 项（1200→160、400→120、1600→160）与 `ShortcutReworkTest` 2 项（F5/Ctrl+F5/F6 绑定与空格别名、三次按键的状态序列），测试总计 125 项。

### v1.12 WASD 移动、方向键换选中框

原来的裸方向键是「移动选中的平行线」，和输入模式的 WASD 各管一摊；用户要把两者按「模式」和「功能」拆开：

- **W/A/S/D**：非输入模式 = 移动选中的平行线（W/S ±1 半音、A/D ±1 网格），输入模式 = 移动红色光标。实现放在 `ArrowKeyDispatcher.wasdCommandIdFor()`：先看 `view.inputMode()`，是的话直接返回 null（交给 `InputModeDispatcher`，它在 KEY_PRESSED 阶段就 consume 掉了）；否则映射到 `NOTE_MOVE_*` 命令。**不能**把 W/A/S/D 直接写进全局 InputMap——`WHEN_IN_FOCUSED_WINDOW` 会在文本控件的 `typed` 之前吃掉字母键，BPM 框、音轨名就再也打不进 w/a/s/d 了。
- **←/→/↑/↓**：任何时候都只移动**选中框**（新增 `NOTE_SELECT_LEFT/RIGHT/UP/DOWN` → `MidiDocumentView.stepSelection(timeStep, pitchStep)`）。规则：活动音轨内、只看发声的平行线（休止符走过去没声音反而奇怪）；←/→ 按起点前后取相邻那条，↑/↓ 取音高差最小、同差再比时间距离的那条；没有选中时按播放头就近取一条；到头就停住不越界。**不改任何 tick**，只 `selection().setSingle(target)` + `scrollToTick` + 试听。
- **试听要“像它自己”**：`auditionNote` 的时长从「压到 120–1500ms」改成 `tempoMap` 算出的**真实时值**（最短 60ms，不再封顶 1500ms），`lastAudition={音高, 毫秒}` 先记下再尝试发声（没音频设备也能验证走位）。`SynthService.audition` 变成 `synchronized` 并在每次开新声前 `cancelAudition()`（收掉上一声的 NOTE_OFF、停掉旧定时器），否则连着按方向键会把几个音叠在一起；定时器到点走 `finishAudition()`，若中途已经换过对象就什么也不做。`allNotesOff()` 也顺带 `cancelAudition()`。
- 兼容：`Alt+↑↓←→` 仍是 `NOTE_MOVE_*` 的别名（`bindAlias`），`Ctrl/Shift+←→` 的伸缩语义完全没动；画布 `installArrowKeys()` 的本地绑定同步改成 `NOTE_SELECT_*`，否则滚动面板的同键绑定会抢走方向键。
- 测试：`SelectionStepTest` 5 项（WASD 移音符 / 输入模式 WASD 归光标 / ←→ 走位且不动音符 / ↑↓ 按音高走位 / 每步按自己的时值试听）＋ `AuditionSoundTest` 2 项（NOTE_ON 到点收 NOTE_OFF、再次试听先掐掉上一声），测试总计 132 项。

### v1.13 视图状态不再写进 .mid（“软件不该记忆缩放”）

现象：用户关掉再开、重新打开自己的工程，发现频谱缩放程度与 BPM 还是上次的样子，觉得软件“不纯净”。

事实核对：`MidiEditorApp` 启动时**不新建文档、不打开文件、不读写任何配置**，程序里也没有 `java.util.prefs` / properties / 任何属性文件；`DocumentManager.newDocument()` 每次都是全新的 `MidiDocument(480)` + 一个默认 Track。所以「记忆」只可能来自 **.mid 文件本身**：

1. **BPM / 拍号**是标准 MIDI 的 tempo(0x51) / 拍号(0x58) meta —— 属于乐曲数据，本来就该跟着文件走（也是别的 DAW 打开时的行为）。
2. **频谱缩放 / 上下位置**是 v1.1 为修「重开音高偏移」特意写进去的自定义标记 `mideditor:yRange=low,high` —— 这是**视图状态**，确实不该进文件。

改法（`MidiFileCodec`）：

- 写盘不再输出 `mideditor:yRange=`；
- 读盘删掉 `findStoredRange()`，一律 `track.autoRangeFromNotes()`（按内容适配音域）；
- 常量改名 `LEGACY_RANGE_MARKER`，只用于 `collectForeignMeta()` 把旧文件里的这条标记**丢掉**，否则它会被当“外来 meta”永远搬运下去。

回归：`FreshInstanceStateTest`（实例 A 改 150 BPM / 60 行 → 关掉 → 实例 B 新建文档必须是 60 BPM + 默认行数）、`MidiFileCodecTest.roundTripKeepsMusicButNotTheViewRange`（乐曲事件全保真、音域按内容重算、文件里不许再有该标记），测试总计 133 项。
