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
- **关键：时值吸附网格，但起点绝不由「敲击间隔」推导**。v1.15/v1.16 曾经按两次按键的间隔生成休止符（`glueToPreviousInput` / `alignWithPreviousInput`），已被 v1.17 整体推翻：现在 `endInputNote` 只做两件事——起点取按下时的光标（光标本来就在上一条末尾，于是天然首尾相接；只有手动 A/D 挪开过才 `snapTickToAssistUnit`），时值 `snapDurationToAssistUnit` 吸附到整数格。**输入模式永远不会因为间隔而插休止符**。
- **小键盘数字输入（v1.22）**：`beginInputDegreeNote(degree)` 把光标切到「当前八度 C + C 大调音阶第 degree 级」（`DEGREE_SEMITONES = {0,2,4,5,7,9,11}`），然后直接复用 `beginInputNote(pitch, sourceKey)` —— 按下即开始计时、松手即 `endInputNote`，和空格完全同一条收尾路径。
  - **八度偏移 `inputOctaveJump`**：小键盘 `+` / `-`（`VK_ADD` / `VK_SUBTRACT`）±1，夹在 ±9；取音高时用的是「光标当前八度 + jump」。**每输入完一个音就归零**（在 `endInputNote` / `abortInputNote` 里清），不留给下一条。`degreePitch()` 是纯函数（static，包内可见），测试直接断言音高。
  - **按住大键盘 `+`/`=` 或 `-`/`_`（v1.22 补充，v1.25 改为一次性）**：`inputOctaveHold` 记 ±1（用完即清），它是<b>物理按键状态</b>，所以按住期间连敲多个数字都有效；取音高时参与 `shift = inputOctaveJump + inputOctaveHold * octaveScale`，`octaveScale` 由按键事件里是否带 Ctrl 决定（1 或 2）——所以 `Ctrl + = + 数字` = 高两个八度，`Ctrl + - + 数字` = 低两个八度。`InputModeDispatcher` 的修饰键放行条件也因此从“任何 Ctrl 都放行给命令系统”改成“Alt/Meta 一律放过，Ctrl 只放行小键盘 1~7 与 `VK_EQUALS`/`VK_MINUS`”（`Ctrl+Space` 停止等仍归命令系统）。
  - **Ctrl+U / Ctrl+L = 光标整八度**：`moveInputCursor(0, ±12)`，只挪光标（音高窗口跟着翻），不出音符、不动时间；只认「纯 Ctrl+U/L」（`SHIFT_DOWN_MASK` 不算），所以 `Ctrl+Shift+L`（循环域＝选区）仍归命令系统。注意 `Ctrl+L` 本身是「循环播放」的快捷键，输入模式下会先被 `InputModeDispatcher` 吃掉。
  - **刚输入的那条就是选中项**：`endInputNote` 末尾 `selection().setSingle(created)`——只换选中、不累加，也不影响 Esc 退出 / Esc 取消选中 / 删完选邻居这些既有规则（`DocumentSelection` 按 id 存，撤销后旧 id 解析为空自然失效）。
  - **空格与小键盘互不打断**：`inputNoteKey` 记下这条音是哪个键按下的，松手走 `endInputNoteFromKey(keyCode)`，键对不上就不收尾（两套输入并存时不会互相提前结束）。
  - 时钟可注入：`setNanoClockForTesting(LongSupplier)`（包内可见），测试可以精确控制“按了多久 / 隔了多久”。
  - 入口：工具栏第二个开关 + 编辑菜单勾选项。
- **光标可以翻动频谱**：`moveCursorTo` 不再把音高夹在可见音域内，而是 `scrollPitchWindowToCursor(track)` —— 光标越过窗口上/下边界时 `Track.shiftPitchRange()` 把窗口推过去（行数不变），再刷新键盘标尺与音轨头；切轨 / 重新进入输入模式时同样把新窗口翻到光标处。
- 入口：工具栏 `JToggleButton`（选中态跟随命令）+ 编辑菜单勾选项 + `Ctrl+I`。
- **撤销 / 重做要带上光标**：`endInputNote` 往 `inputSteps` 里压一条 `(track, noteId, beforeTick/Pitch, afterTick/Pitch)`；`MidiDocumentView.undo()` / `redo()`（命令层只调这两个）事后检查栈顶那条音符是否还在 —— 被撤销就退到 `before` 并压入 `undoneInputSteps`，被重做就退栈并跳到 `after`。别的编辑不会动光标。
- 回归测试 `InputModeTest`（29 项）+ `InputPreviewSoundTest`（1 项，假 Receiver 验证 NOTE_ON/NOTE_OFF 与 `activeNoteCount`）。
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
- **播放键**：`PLAY_PLAY_PAUSE` 主键 空格 → **F5**、`PLAY_STOP` Ctrl+空格 → **F4**（v1.20 改：原本是 Ctrl+F5，被用户报告为“按了却变成堆叠窗口”，旧键已彻底解绑）、`PLAY_REWIND` Ctrl+Home → **F6**；旧键用 `CommandRegistry.bindAlias` 保留为备用（`CommandRoutingTest` 里断言过空格仍然映射到播放，别名不破坏它）。
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

### v1.14 音轨信息面板缩到 1/3、钢琴标尺缩到 1/2

`Theme.HEADER_WIDTH` 244 → **81**（三分之一），`Theme.PITCH_STRIP_WIDTH` 46 → **23**（一半）。单纯改常量会把行头里的控件全裁掉，所以 `TrackHeaderRow` 一并重排：

- 原来 5 行固定宽控件（名字 120、音量/声像滑块各 150、乐器下拉、四个按钮 + 音域读数）在 81px 里放不下（FlowLayout 会折行、滑块被切），改成 8 行紧凑布局，控件宽度统一按 `inner = HEADER_WIDTH - 8` 算：
  - 第 1 行：`BorderLayout`——颜色块（12px）在 WEST，名称框在 CENTER（`inner-18` 宽）；
  - 第 2 行：乐器下拉占满一整行（文字会被截断，但 tooltip + 每帧刷新照旧）；
  - 第 3/4 行：音量、声像滑块各占一整行（去掉「音量 / 声像」文字标签，改 tooltip，省下来的横向空间给滑块）；
  - 第 5/6/7 行：`GridLayout(1,2)` 的 `M|S`、`▲|▼`（音域上下移）、`＋|－`（音域缩放）；
  - 第 8 行：音域读数（如 `45-74 · 30行`）。
- 泳道高度 240px 足够放这 8 行（约 150px），底部留白由 `Box.createVerticalGlue()` 吸收。
- 钢琴标尺：`C4` 这类标注右对齐位置从 `width-6` 改成 `width-2`，23px 下才不会被切；黑键/白键仍是整行铺满色块，缩窄不影响识别。
- 两个常量只被 `PianoRollView.relayout()` 用来算 `rowHeaderWidth = HEADER_WIDTH + PITCH_STRIP_WIDTH`，所以标尺、行头、画布的横向对齐是同一处算出来的，不会错位。

### v1.15 节奏辅助的连奏缝隙 + 底部改成 Word 式单行状态栏

**一、按住 3 秒、松开、再按住 3 秒，中间被插了休止符**

根因是两条各自独立取整：`endInputNote` 里上一条的**时长**走 `snapDurationToAssistUnit`（`round(hold / unit)`），下一条的**起点**走 `snapTickToAssistUnit(anchorTick + microsToTicks(敲击间隔))`（也是 round）。两次 round 的输入只差「松开到再按」的那点人工间隔，只要它把比值推过 .5，起点就比上一条的末尾多出一整格 → `reconcileRests` 忠实地把那格缝实体化成休止符。

修法（`MidiDocumentView.alignWithPreviousInput`，取代最初的 `glueToPreviousInput`）：记住**上一回松手的时刻**（`previousReleaseNanos`，在覆盖 `lastInputReleaseNanos` 之前抓一份）与上一条输入的 id（`lastInputNoteId`），落新音时在同一个编辑事务里做三件事：

1. **不重叠**：起点落进上一条里 → 上一条裁到这个起点（保节奏）；起点跟上一条完全相同（粗网格下两次敲击取整到同一 tick）→ 新音接在上一条末尾；
2. **连奏**：量化后的缝 ≤ 两格、且“上一回松手 → 这一回按下” **≤ 600ms** → 上一条拉长到新起点（只拉长前一条，不动新音的起点，敲击节奏不被改）；
   > ⚠ 这条判据的「缝 ≤ 两格」在 v1.16 被删掉了（细网格下它把容差压到 250ms），改成「间隔 ≤ max(600ms, 上一条音长的一半)」。
3. 停得够久（> 600ms 或缝 > 两格）就不动，休止符正常出现。

> 第一版判据是“静音 ≤ max(半格, 200ms)”，用户实测仍然会插休止符（250–300ms 的双击就超了），所以改成上面的“两格 + 600ms”；另外审计测试发现了第二个更隐蔽的 bug：**粗网格下两次敲击被取整到同一个 tick**，第二条音直接叠在第一条上（`音[0..480] 音[0..960]`），现在由第 1 条规则推到上一条末尾。

- `lastInputNoteId` 在手动 A/D 移光标、进出模式、开关节奏辅助、撤销/重做时清空（`noteById` 查不到也会自动跳过）；输入落点后还调一次 `reflowLocks()`，免得被拉长的上一条把它锁着的同伴丢在后面。
- 回归：`InputModeTest.backToBackPressesStayConnectedUnderAssist`（2.9s + 150ms + 2.9s → 相邻无休止）、`aRealPauseStillLeavesARest`（1s 停顿仍保留休止）、`doubleTapStaysConnectedAcrossTypicalSpeeds`（按住 300/900/3000ms × 间隔 0/60/150/250/400ms 共 15 组 → 必须首尾相连）、`assistNeverLeavesSliverRestsOrOverlaps`（**不变量审计**：1/4、1/8、1/16、1/8 三连音四档网格各敲一串长短不一的节奏 → 不许出现“小于一格”的休止符，也不许两条平行线重叠）。

**二、底部「诊断」栏改成 Word 式单行状态栏**

- `ToolPanels` 里 `DIAGNOSTICS` 默认 `visible=false`（仍在视图菜单里，需要时调出），底部那块高大的停靠区默认不再出现。
- `StatusBar` 重写成 `BorderLayout` 的两段：**WEST = 状态文字**（平时绿字「就绪」，`setMessage` 的临时提示占位并用 `Timer` 4 秒后自动回到就绪）、**CENTER = 右对齐的信息行**（文档 · 音轨 · tick/时间 · 缩放 · N 音轨 · M 平行线 · PPQ · 已保存/未保存 · 音频就绪/不可用）。高度 24 → **20px**，字号用 `SMALL_FONT`。
- `MainFrame.updateStatus` 改为调用新的 `statusBar.setInfo(...)`（原来是用 `setMessage` 塞一长串，会和临时提示互相覆盖）。
- 测试：`statusBar.stateText()` / `infoText()` 暴露给测试（新增用例 2 项），总计 **135 项**。

### v1.15 补：状态栏“看不见”其实是窗口比桌面还高

用户反馈「底部怎么看不见任何东西」。排查结论：状态栏画得好好的（离屏 `printAll` 能看到“就绪”），问题在**窗口尺寸**——`MainFrame` 里写死 `setSize(1440, 920)` 再 `setLocationRelativeTo(null)`，本机可用桌面只有 **1440×852**（1440×900 屏减掉任务栏），于是窗口纵向被居中到 `y = -10`，**底部 58px 落在可用区域之外**（压在任务栏后面）。以前底部有一块 ≥180px 的“诊断”停靠区顶着，所以没人发现；把诊断默认收起后，可见区域的下边缘正好是传输条，那一行 20px 状态栏就整条被挡掉了 —— 看起来像“底部什么都没有”。

修法（`MainFrame` 构造函数）：一律按 `GraphicsEnvironment.getMaximumWindowBounds()` 定尺寸与位置——
`width = min(1440, usable.width)`、`height = min(920, usable.height)`，左上角取 `usable.x/y` 居中（不小于 0），`setMinimumSize` 也夹到可用桌面之内。这样无论屏幕多大，状态栏都完整落在任务栏之上。
回归：`WindowFitsScreenTest`（断言 `usable.contains(frame.getBounds())`），测试总计 **136 项**。

**顺带：默认最大化。** 用户接着问「为什么不是最大化而是堆叠但布满屏幕」。原因是之前只用 `setSize` 把窗口撑到 1440×852（恰好等于可用桌面），但那只是“一个很大的普通窗口”：没有最大化状态、标题栏按钮还是“最大化”图标、别人一切窗口就现原形。现在 `MainFrame` 构造末尾就 `setExtendedState(getExtendedState() | MAXIMIZED_BOTH)`，`MidiEditorApp` 在 `setVisible(true)` 之后再确认一次（有些 WM 会在 show 时清掉 show 之前设的扩展状态）。实测：`getExtendedState()==6`（MAXIMIZED_BOTH），状态栏屏幕坐标 `y=832..852`，正好压到可用桌面底边，不会被任务栏挡。`WindowFitsScreenTest` 里额外断言了最大化状态。

### v1.16 连奏容差改为「跟着音长走」（第三次报同一个 bug）

用户第三次报「在同一个音高按两下，中间还是加很长的休止符」（第一次是 `按住3s → 松开 → 再按住3s`）。用探针把 60BPM 下 7 档网格 × 4 种按住时长 × 15 种间隔全部跑了一遍，才看清 v1.15 那条规则的实际边界：

> `legato = gap ≤ 2 × unit 且 silence ≤ 600ms` —— 两个条件取**与**，所以真实容差是 `min(600ms, 2×unit)`。

1. **细网格被两格卡死**：`2 × unit` 在 1/32 网格下只有 250ms、在 1/16 + 120BPM 下也只有 250ms。人手的「松开 → 再按」轻松就超了 → 每档网格都在 300–400ms 间隔处开始冒休止符。
2. **长音之后有死区**：连奏阈值 600ms，而 `PAUSE_REANCHOR_NANOS` 是 1.5s，中间 0.6–1.5s 这段停顿既不算连奏、又没触发重新锚定 → 按住 3 秒的音再按下去，中间被塞进一整条与停顿等长的休止符（探针表里 `1/16 hold=3000ms` 行：间隔 ≥ 700ms 就开始出现）。

修法（`MidiDocumentView.alignWithPreviousInput` / 新增 `legatoToleranceMicros`）：

- 容差改为 `max(600ms, 上一条音长 ÷ 2)`：长音之后按「音长的一半」放宽，短音仍用 600ms；
- **去掉「缝 ≤ 两格」这个条件**。它本来就是 `silence` 的近似（`gap = silence + 两次取整误差`，误差 ≤ 半格），保留它只会让细网格的容差莫名其妙地变小；
- 间隔超过容差才算真停顿，休止符照常由 `reconcileRests` 实体化；`> 1.5s` 仍然重新锚定到光标处。

探针复测（60BPM）：`hold=3000ms` 在 1/4、1/8、1/16 三档网格上、间隔 0–2000ms 全部 `--`（无休止符）；`hold=300ms` 仍然在 700ms 之后正常出现休止。

回归：`InputModeTest.longHeldNotesStayConnectedWhateverThePause`（3 秒音 × 1/4、1/16、1/32 × 间隔 0/200/400/600/800/1100/1400ms → 必须相邻且无休止）、`InputModeTest.fineGridDoesNotSplitQuickDoubleTapsWithRests`（1/32 网格按住 200ms、间隔 300/400/500/600ms → 必须相邻）、`aRealPauseStillLeavesARest` 改成「短促音（300ms）+ 1 秒停顿」（原来用 2.9 秒长音，把连奏规矩写反了）。测试总计 **140 项**。

> ⚠ **v1.16 这一版仍然是错的**（v1.17 已整体推翻）：它继续把“两次按键之间的间隔”拿去和休止符比较，只是把阈值调宽了一点。用户的原话是「我他妈没叫你做这个」——输入模式从一开始就**没有**“按间隔插休止符”这个需求。

### v1.17 输入模式不再碰休止符：新音直接接在上一条末尾

**用户的第三、四次反馈**：「同一个音高按两下，第二次不但前面冒出休止符，而且比实际按空格的时间长」。追问确认后定性：**输入模式从来不该按“两次按空格的间隔”生成休止符**——那是我自己加的规则（v1.15 的 `glueToPreviousInput` → v1.16 的 `alignWithPreviousInput`），需求里没有。

现在的模型（`MidiDocumentView.endInputNote`）：

1. **位置**：取按下那一刻的光标（`inputNoteStartTick`）。每输入完一条，光标就停在它的末尾（`moveCursorTo(created.endTick())`），所以下一次按下自然就落在上一条末尾 —— **连续输入天然首尾相接，间隔多长都不影响位置**。
2. **时值**：`heldNanosToTicks(按住时长)`，**不再做网格取整**。`snapDurationToAssistUnit` 已删除，因此不会出现“按 300ms 却落成一整格”的情况。
3. **节奏辅助**只做一件事：手动 A/D 挪过光标后，落音起点吸附到当前网格（`snapTickToAssistUnit`，网格关闭时退回一拍）。光标仍停在上一条末尾时**不做吸附**（否则那点 <=半格的取整误差又会变成缝）。
4. 想留空隙：用 A/D 把光标挪开再按（这是显式动作，`reconcileRests` 会把空隙实体化成休止符）。

删掉的东西：`alignWithPreviousInput`、`legatoToleranceMicros`、`LEGATO_SILENCE_MICROS`、`snapDurationToAssistUnit`、`assistAnchored/assistAnchorNanos/assistAnchorTick/lastInputReleaseNanos/PAUSE_REANCHOR_NANOS`。

保留的最小规则：光标被手动挪回上一条**里面**时，把上一条裁到新起点（防两条同音高重叠），仅此一条。

回归（`InputModeTest`，22 项）：`staccatoPressesAppendWithoutAnyRests`（每次 100ms、间隔 380ms 敲三下 → 0/100/200 三条、长度都是 100 tick、无休止）、`assistSnapsTheStartButKeepsTheRealHoldLength`（起点吸附 1/8 网格，时值严格 = 按住时长）、`assistNeverInsertsRestsOrOverlaps`（四档网格 × 8 组长短节奏 → 文档里**不许出现任何休止符**、不许重叠）、`doubleTapStaysConnectedAcrossTypicalSpeeds`（15 组）、`longHeldNotesStayConnectedWhateverThePause`、`fineGridDoesNotSplitQuickDoubleTapsWithRests`、`aLongPauseDoesNotPushTheNextNoteAway`。`aRealPauseStillLeavesARest` 已删除（那条行为本身就不该存在）。测试总计 **139 项**。

### v1.18 恢复“起点 + 时值”双重吸附（v1.17 删过头）

切掉间隔逻辑时，我把 `snapDurationToAssistUnit` 一起删了，于是连续输入时既没有起点吸附、也没有时值吸附——用户立刻发现「我的辅助（吸附）去哪了」。

现在两条规则并存：

| | 行为 |
| --- | --- |
| **吸附**（节奏辅助开） | 起点吸附到网格（`snapTickToAssistUnit`）；时值 = `max(1, round(按住时长 / 网格))` 个网格（`snapDurationToAssistUnit`，已恢复）。连续输入时上一条末尾本来就落在网格上，所以新音直接接上去也依然对齐 |
| **休止符** | 输入模式**永远不会**因为两次按键的间隔生成休止符；只有手动 A/D 把光标挪开再按，才会在中间留下（并被 `reconcileRests` 实体化成）休止符 |

幽灵条（`pendingInputTicks`）恢复显示吸附后的长度，所见即所得。

回归：`assistSnapsStartAndDurationToTheGrid`（起点与时值都是 1/8 的整数倍）、`staccatoPressesAppendWithoutAnyRests`（100ms 按住 → 一个八分音符，三条 0/240/480）、`assistNeverInsertsRestsOrOverlaps` 增加「起点/时值都必须是整数格」断言。测试总计 **139 项**。

### v1.19 粘贴落点 / 框选自动滚 / 删完接着选 / 输入模式按格删 / 回到开头跟着滚

五条交互修正，都是“用户手在哪儿，结果就在哪儿”：

1. **粘贴落点 = 上一次点击频谱的位置**（MidiDocumentView.setPasteAnchor，由 PianoRollCanvas.mousePressed 在左键按下时调用）。paste() 用锚点 tick（吸附网格）作为起点、用锚点音高减去剪贴板第一项的音高得到整体音高平移；没点过才退回播放头。
2. **框选自动滚动**（PianoRollCanvas.marqueeScroller）：框选时鼠标停在视口边缘 24px 内，就用 30ms 的 Swing Timer 持续滚动 24px/帧——因为鼠标贴边不动时 mouseDragged 不再触发，只用拖拽事件是滚不起来的。滚动后按“视口内坐标 + 新视图位置”重算画布坐标的框选矩形（utoScrollMarquee）。
3. **Delete 之后选中框落到邻居**（deleteSelection + 
eighbourAfterDeletion）：优先取被删区域之后最近的发声平行线，没有就取之前的最后一条；这样接着按方向键是从旁边继续，而不是清空后从文档开头重来。
4. **输入模式 Backspace / Delete 按格删**（MidiDocumentView.deleteInputStep + eraseRange）：以光标为界，Backspace 删左边一格并左移光标，Delete 删右边一格。整条命中就删掉，跨边界就裁剪，横跨两边就拆两段（头段保留原 id、尾段 copyWithNewId，锁定引用跟着重指）。
5. **回到开头时频谱也拉回开头**（PianoRollView.scrollToTimeStart）：
rewind()（F6 / ⏮ 按钮）与 stopTransport()（F4 / ⏹ 按钮，它本来就把播放头归零）都调用，横向位置归 0、纵向不动。

回归：InteractionFixesTest 6 项（粘贴落点与音高平移、删除后选中邻居、Delete/Backspace 按格删除与拆分、rewind 把视图拉回 x=0、框选贴左边自动向左滚）。测试总计 **145 项**。

> 测试坑：没有真实窗口时 getViewRect() 恒为 0；滚动类断言必须先手动给视口和内容设尺寸（viewport.setSize(1000,700) + canvas.setSize(6000,800)），doLayout()/validate() 在非 displayable 的组件上不生效。

### v1.20 WASD 移动自动跟随滚动 + 停止键改成 F4

1. **WASD 移动选中平行线时频谱要跟着滚**：`moveSelection` 原来只改 tick 不碰视图，所以往右移出屏幕后要自己拖横向滚动条。现在编辑提交后统一调 `scrollSelectionIntoView()`（取选中项里最早的 `startTick`，交给 `PianoRollView.scrollToTick` —— 它本来只在超出“安全区”时才动视图，所以贴边自动跟随、屏幕内不会乱跳）；休止符 ripple 分支同样调用。
2. **停止从 Ctrl+F5 改成 F4**：用户实测按 Ctrl+F5 后窗口变成层叠而不是停止（判断为快捷键冲突），所以 `PLAY_STOP` 主键改 `VK_F4`，并且**不再保留 Ctrl+F5 别名**（`ShortcutReworkTest` 新增断言：Ctrl+F5 在全局 InputMap 里必须没有绑定）。Ctrl+空格 仍作为停止的备用键。

回归：`ShortcutReworkTest.playbackKeysAreF5F4AndF6`（F5 / F4 / F6 + “Ctrl+F5 无绑定”）。测试总计 **145 项**。

### v1.21 Esc 取消所有选中 + 无选中时方向键先落第一条

- **Esc = 取消选择**：新命令 `EDIT_DESELECT`（`edit.deselect`，`VK_ESCAPE`，`CommandScope.SELECTION` + `hasSelection`），执行 `MidiDocumentView.clearSelection()`；编辑菜单里也加了「取消选择」。输入模式下 Esc 先被 `InputModeDispatcher` 吃掉（退出输入模式），所以两种语义不会打架；`TextFocusGuard` 保证光标在文本框里时 Esc 不误清选区。
  > ⚠ 只挂 `WHEN_IN_FOCUSED_WINDOW` 还不够：焦点在 MDI 子窗体（`JInternalFrame`）里时，Swing 解析到的是**子窗体自己的 JRootPane**，主窗口 root pane 上的键绑定不一定生效（用户实测按 Esc 没反应）。所以 Esc 同时由 `ArrowKeyDispatcher`（已注册的全局 `KeyEventDispatcher`）接管：不在输入模式且有选中就直接 `router.dispatch(EDIT_DESELECT)` 并 `consume()`，与焦点落在哪个控件无关。
- **无选中时方向键先选中第一条**：`stepSelection` 原来在没有锚点时会按播放头就近取（播放头在 0 且音在右边时按 ← 什么都不会发生）。现在没有选中（或选中的全是休止符 / 别的音轨）时，任何方向键都直接选中活动音轨里**起点最早的那条**，之后照旧在相邻对象之间走位。

回归：`InteractionFixesTest.escapeClearsTheWholeSelection`（Esc 真的绑在全局 InputMap 上 + 清空选区）、`arrowKeysPickTheFirstNoteWhenNothingIsSelected`（无选中时 →/↑ 都落到第一条，再按 → 走到第二条）。测试总计 **148 项**。

### v1.22 输入模式加小键盘数字（简谱）+ 小键盘 +/- 挪八度

第二套输入手法，和 `WASD + 空格` 并存：

1. **小键盘 1~7 = 简谱 do/re/mi/fa/sol/la/si**（`InputModeDispatcher` → `MidiDocumentView.beginInputDegreeNote`）。按下就把光标切到这个音高并**立即开始输入**（不用再按空格），松手时按按住时长落成平行线；音高 = 光标所在八度的 C 往上数 C 大调音阶（`DEGREE_SEMITONES`），再夹到 0..127。
2. **小键盘 + / - = 八度偏移 ±1**（`shiftInputOctaveJump`，夹在 ±9）。取音高用「当前八度 + jump」；**每输入完一个音 `inputOctaveJump` 归零**，不留给下一条，退出输入模式也归零。
3. **松手只认自己按下的那个键**：`inputNoteKey` 记录来源（空格 / 小键盘数字），走 `endInputNoteFromKey()`；空格按着时敲数字不会把空格那条提前收尾，反之亦然。
4. 其余行为（连奏接上一条末尾、节奏辅助吸附、幽灵条、撤销带光标）全部复用原有路径，没有第二份实现。

回归：`InputModeTest.numpadDigitsEnterTheDegreeInTheCursorOctave`（1~7 落在同八度的 do..si、首尾相连、按住时长有效）、`numpadPlusMinusShiftTheOctaveAndResetAfterEachNote`（+ 两次 → 高两个八度；输完 jump 归零，下一条不再上移；`-` 降回来）、`holdingMainKeyboardPlusMinusShiftsTheNumpadOctave`（按住 `=`/`-` 时数字 ±1 八度且按住期间一直有效、松开失效；再按 Ctrl 则 ±2 八度）、`numpadAndSpaceInputsChainTogether`（两套输入接着往下写）、`ctrlUMovesTheCursorUpAnOctaveAndCtrlLDown`（±12 半音、不动时间、裸 U 与 Ctrl+Shift+L 都放行）、`everyInputNoteBecomesTheSelection`（空格/小键盘输入都只选中新落的那条、退出输入模式不清选中）、`numpadIsNotConsumedWhenInputModeIsOff`。测试总计 **155 项**。

### v1.23 快捷键总表 + 帮助菜单入口

- **单一来源**：所有快捷键整理在仓库的 `docs/SHORTCUTS.md`（文件 / 编辑 / 选中走位 / 长度四键 / 播放 / 视图缩放 / 输入模式 / 鼠标 / 已知冲突）。改键先改代码，再同步这份表。
- **随程序打包**：`pom.xml` 的 `<build><resources>` 显式列出默认的 `src/main/resources`，并额外把 `docs` 下的 `SHORTCUTS.md` 复制到 class 路径根目录，所以 jar 里就有 `/SHORTCUTS.md`。
- **帮助 → 快捷键**（`HELP_SHORTCUTS` / `help.shortcuts`）：`ui/frame/ShortcutDoc.materialize()` 每次把打包进去的那份解到 `%TEMP%\midEditor\midEditor-快捷键.md`（覆盖写），然后 `Desktop.getDesktop().open(...)` 交给系统默认程序；不支持或打不开时弹框把路径告诉用户。之所以不读工作目录下的 `docs/SHORTCUTS.md`：程序可能被拷到任意位置、以任意 cwd 启动，只有 jar 里那份保证与运行版本一致。
- 回归：`ShortcutDocTest`（解得出且内容含 Ctrl+Z / F5 / F4 / 小键盘；帮助菜单里「快捷键」与「关于」都在）。测试总计 **157 项**。

### v1.24 BPM 改完按 F5 失效 / 那次速度不生效 + 输入模式入场居中 + 粘贴不改音高

用户报的三件事，后两件是需求变更：

1. **改完 BPM 按 F5 没反应，而且那次改的速度不生效** —— 两个原因叠在一起：
   - `JSpinner` 的编辑器只有在**回车或失去焦点**时才把文字提交到 model，而我们看的是 `bpmSpinner.getValue()`，所以「输入了 90 但没生效」。
   - 焦点停在 spinner 的内部 `JFormattedTextField` 里时，`TextFocusGuard.isTextEditing()` 为真，`PLAY_PLAY_PAUSE` 处理器直接 `return` —— 表现就是 F5 完全失效。
   - 修法：`TextFocusGuard` 增加“知道按了哪个键”的版本（`noteKeyboardEvent(KeyEvent)` + `shouldBlock(keyboardDriven, keyCode, focusOwner)`），**F1–F12 永远不算在打字**（功能键不可能插入字符），空格 / Delete 等仍然让位；`TransportBar.commitBpmEdit()`（`bpmSpinner.commitEdit()`，非法数字忽略）在**焦点离开**和 **`togglePlayPause()` 开头**各调一次，保证播放用的是刚输入的速度。
2. **进入输入模式时光标放在当前可见频谱中央**：`setInputMode(true)` 不再用播放头，而是取 `pianoRoll.visibleRect()` 的中点——横向 `tickAtX(visible.x + visible.width/2)` 再吸附网格，纵向取活动音轨泳道的中间行（音域窗口正好铺满泳道，所以这就是“看到的那一段的中间”）；视口还没布局（宽高为 0）时退回播放头 / 原音高。
3. **粘贴只跟随点击位置的时间，不再平移音高**：`paste()` 去掉 `pasteAnchorPitch - items.get(0).pitch()` 的整体移调，`setPasteAnchor(long tick)` 也不再带音高参数。

回归：`TextFocusGuardTest.functionKeysAreNeverTreatedAsTyping`（F5/F4/F6 不挡、空格与 Delete 仍挡）、`BpmTempoTest.tempoTypedButNotCommittedIsAppliedBeforePlayback`（输入 90 不回车直接播放 → `tempoMap.bpmAt(0) == 90`）、`InputModeTest.enteringInputModePutsTheCursorInTheMiddleOfTheVisibleSpectrum`（滚到 900 与 3000 后再进都落在新视口中点，纵向是泳道中间行）、`InteractionFixesTest.pasteLandsWhereYouLastClicked`（落点 960、音高仍是 60）。测试总计 **160 项**。

### v1.25 大键盘 +/− 的八度偏移改成“一次性”

- 用户要求：`大键盘 −` + `小键盘 7` 写出低音 7 之后，**下一个音自动回到原来的八度**，不需要再按 `+` 扳回来；小键盘 `+ / −` 那套（可累积、输完一个音才归零的 `inputOctaveJump`）保持原样。
- 实现：`inputOctaveHold` 从“物理按键状态”改成“**一次性令牌**”——`beginInputDegreeNote` 里算完 `shift` 就把它清掉；另加 `inputOctaveHoldKey` 记住是哪个键挂上的，用来**吃掉键盘自动重复**（按住不放产生的连续 KEY_PRESSED 不会把令牌重新挂上，只有真正松开再按才算）。`pressInputOctaveHold(direction, keyCode)` / `releaseInputOctaveHold(keyCode)` 取代原来的 `setInputOctaveHold(int)`。Ctrl 的双八度仍然按“按下的那一刻有没有 Ctrl”算（`octaveScale`）。
- 回归：`InputModeTest.holdingMainKeyboardPlusMinusShiftsTheNumpadOctave` 覆写成一次性语义（`=`+1 → 下一个数字回原八度；自动重复不重挂；松开再按才生效；Ctrl 双八度也只一次；小键盘 `+` 仍可累积到 +2 八度）。
- **第一次改完仍然复现（用户实测「低音 7 之后还是继续在低音区」）**：因为音高是拿**光标所在八度**当基准算的，而输入完光标就停在那个低音上 → 下一个数字又从低八度出发，令牌清没清都无所谓。真正的修法是把基准八度**独立出来**：新增 `inputDegreeOctave`，只在「进入输入模式（取可见中心）」和「手动改音高（W/S、Ctrl+U/L）」时更新；`beginInputDegreeNote` 用 `base + jump + temp` 算音高，然后 `inputDegreeOctave = base + jump`（小键盘 jump 照旧保留，`temp` 不写进基准）。A/D 只挪时间、不改音高，因此也不会把基准拖走。
- 回归补充：同一条测试里加了用户原始场景——`-` + 小键盘 `7` → 低八度 si（`base-1`），紧接着再敲 `7` → 回到 `base+11`（原八度的 si）。

### v1.26 入场接最后一条 / Ctrl+I 两态 / 调号属性 / 输入浮牌

1. **进入输入模式的光标位置**：`setInputMode(true)` 先找活动音轨上最后一个发声的平行线（`lastSoundingNote`），光标放到它的 `endTick`、音高取它；**一个音都没有时落在 tick 0 的 C 上**（`nearestC(可见窗口中间行)`，默认音域中央就是 C4），频谱也拉回开头。
2. **Ctrl+I 两态**：`EDIT_INPUT_MODE` 的处理器从三态循环改成「关 ⇄ 输入模式＋节奏辅助」，开的时候强制 `setInputBeatSnap(true)`。
3. **调号**：`MidiDocument.keyTonic()` / `setKeyTonic()`（主音音级 0..11，默认 C），UI 在**传输条 BPM 右边**的「调」下拉框（`TransportBar.keyBox` → `MidiDocumentView.setKeySignature()`，走 `pushEdit` 可撤销）；小键盘简谱（`degreePitch(degree, octave, tonic)`）与浮牌数字（`degreeLabel(tonic, pitch)`）都按它算。持久化：非 C 调时往指挥轨写一条 `mideditor:key=<tonic>` 的 marker text meta，读盘时 `readKeySignature()` 捡回来，`collectForeignMeta` 不再把它当外来 meta 存一遍。
4. **输入浮牌**：按住空格 / Q / 小键盘数字时，在**按下那一刻**的光标位置（`inputBadgeTick/Pitch`）旁边画一个 2a × a 的小矩形（a = 20px，中间一条竖线分成两个正方形）：左格 `scientificPitchName()`（MIDI 60 = C4，与左侧键盘标尺一致），右格 `degreeLabel()`。松手后 `inputBadgeHeld = false` 并记下 `hideAt = now + 1s`，由 60 Hz 的 `onUiTick` 到点清状态并重绘一次（牌子随之消失）；牌子靠近视口边缘时会夹回可见区域内。绘制在 `PianoRollPainter.paintInputBadge`，颜色 `Theme.INPUT_BADGE_*`。
   - 状态机可注入时钟（`setNanoClockForTesting`），测试直接断言「按住显示 / 0.9 秒还在 / 1.4 秒没了」。

回归：`InputModeTest.enteringInputModePutsTheCursorAfterTheLastNote`、`enteringInputModeWithNoNotesPutsTheCursorAtTheStart`（空文档 → tick 0 且落在 C、视图拉回开头）、`ctrlITogglesInputModeWithBeatSnapInTwoStates`、`keySignatureIsPerDocumentAndDrivesTheDegrees`、`pitchBadgeShowsWhileHoldingAndLingersOneSecond`、`pitchBadgeIsPainted`（离屏 paint 后图像里能找到 `INPUT_BADGE_BORDER` 像素）、`BpmTempoTest.keySignatureComboSetsTheKeyAndItSurvivesSaving`（下拉框 → 文档 → 存盘读回），以及按新语义改写的 `ShortcutReworkTest`（Ctrl+I 两态）与 `InteractionFixesTest` 两条按格删除（入场现在停在最后一个音末尾，测试改成显式把光标挪回 480）。测试总计 **166 项**。

### v1.28 小键盘 0 = 休止符 + 编辑菜单「一键移调」

1. **小键盘 0 输入休止符**：`InputModeDispatcher` 的 `VK_NUMPAD0` → `beginInputRestNote()`，走同一条 `beginInputNote(pitch, sourceKey, rest)` / `endInputNote` 路径，只是落地时用 `Note.rest(pitch, start, duration)`、不发声、不做“上一条裁剪”，浮牌右格写 **0**（简谱的 0 就是休止符）。按住多久就留多长的空；因为休止符在这个模型里就是空隙，夹在两个音之间会立刻被 `reconcileRests` 实体化成红线（时长以空隙为准），写在最后一个音后面时只是把光标往后推，等后面再接上音就成了休止符。
2. **编辑 → 一键移调…**（`EDIT_TRANSPOSE`，无快捷键）：对话框只有一个「半音数」spinner（-24…24）+ 实时预览「调号：C → D」；确定后 `MidiDocumentView.transposeAll(semitones)` —— 所有发声平行线的音高按 id 记下前后值，用 `SimpleEdit`（**绝对值**的 redo/undo）一次提交，所以撤销/重做不会叠加；调号按 `(old + shift) mod 12` 一起转（`MidiDocument.keyName()` 供下拉框与对话框共用）。休止符不平移（它们会随空隙重算），音高夹在 0..127。

回归：`InputModeTest.numpadZeroEntersARest`（1 → 0 → 5 得到「音·休止·音」且首尾相接、浮牌写 0）、`TransposeTest` 3 项（平移 + 调号 + 撤销/重做、越界夹取、0 半音不产生空编辑）。测试总计 **170 项**。

### v1.29 大小调 + 改完运输条控件把焦点还给频谱

1. **调性支持小调**：`MidiDocument.keyMinor()`（false 大调 / true 自然小调）+ `keyName(tonic, minor)`（"C"/"Cm"）。小键盘简谱按调式取音（`DEGREE_SEMITONES_MAJOR = {0,2,4,5,7,9,11}` / `DEGREE_SEMITONES_MINOR = {0,2,3,5,7,8,10}`），浮牌 `degreeLabel(tonic, minor, pitch)` 同理；传输条的调号下拉框变成 **24 项**（索引 = 主音×2 + 小调?1:0）；持久化标记变成 `mideditor:key=7m` 这种，读盘时按结尾的 `m` 认小调。
2. **移调能改大小调**：对话框多了「调式：保持 / 大调 / 小调」，`transposeAll(semitones, Boolean minor)`（null = 保持），预览显示 `C → D`、`C → Am`；撤销/重做同时恢复音高、主音与调式。
3. **改完运输条控件焦点自动回频谱**（用户报“改完调必须再点一下频谱才能继续输入”）：根因是 `JComboBox` 拿到焦点时 `TextFocusGuard.isTextEditing()` 为真，输入模式的键全被挡住。修法：调号 / 网格下拉框选完后 `host.focusSpectrum()`；BPM 框按**回车**提交后同样把焦点还回去（失焦提交那条路径不变）。`MidiDocumentView.focusSpectrum()` 就是 `pianoRoll.canvas().requestFocusInWindow()`。

回归：`InputModeTest.minorKeysUseTheNaturalMinorScale`（A 小调 3 = C，浮牌写 3）、`TransposeTest.transposingCanSwitchBetweenMajorAndMinor`（0 半音只改调式、撤销能退）、`BpmTempoTest.keySignatureComboSetsTheKeyAndItSurvivesSaving` 扩成 24 项下拉框 + G 大调/G 小调存盘读回。测试总计 **172 项**。

### v1.30 时间轴自动加长 + 缩放不再整套重排

1. **时间轴原来是有限的**：`PianoRollView.relayout` 只按「内容末尾 + 16 拍，最少 64 拍」算画布宽度，输入模式把光标一路往右推，过了那个宽度 `scrollToTick` 就滚不动了，光标直接跑出可见区域。修法：
   - `contentEndTick()` 把 `document.endTick()` / `inputCursorTick()` / `playheadTick()` 三者取最大再 +16 拍；
   - 新增 `ensureWidthFor(tick)`，在 `scrollToTick` 开头调用——不够就加宽到「tick 的 x + 一屏余量（至少 800px）」，所以光标 / 播放头 / 选区跟随都能源源不断地往右展开。
2. **缩放“卡卡的”**：原来每次 `timeline` 变化都走 `onTimelineChanged → relayout(false)`——重建行头、重设四块组件尺寸、`scroll.revalidate()` 全树重排；滚轮一格一次，肉眼就是一顿一顿。现在缩放只调 `applyTimeWidth()`（改画布 + 标尺宽度）再重画这两块；另外 Alt+滚轮不再调 `refreshApplicationState()`（整应用刷面板），只同步传输条；传输条缩放滑块拖拽中不刷命令态，松手才刷一次。

回归：`InputModeTest.movingTheCursorFarRightKeepsTheTimelineGrowing`（向右走 400 格后画布变宽、光标右侧仍有余量）；全量 173 项在无布局环境下全绿。

### v1.31 光标换到新音也亮浮牌

1. 浮牌（音名 + 简谱数字的 2:1 小矩形）原来只在「按住 空格 / Q / 小键盘数字」时出现，靠 `beginInputNote` 里的 `showInputBadge` + `endInputNote` 里的 `lingerInputBadge` 驱动；在 `moveCursorTo` 里换成静音键看音高时完全没有反馈。
   现在把浮牌当成通用的「音高反馈」：`moveCursorTo(tick, pitch)` 先比较新旧音高，`newPitch && inputMode && !inputNoteHeld` 就 `showInputBadge(pitch, tick)` + `lingerInputBadge()`（沿用同一个 `INPUT_BADGE_LINGER_NANOS = 1s`）。用 `inputNoteHeld` 挡住按住期间的重叠——不然 WASD 挪光标会把手上那条音的浮牌顶掉。
2. `setInputMode(true)` 末尾也补一次 `showInputBadge` + `lingerInputBadge`：进场就告诉用户光标停在哪个音上。
3. 顺带删掉 `degreeLabel(int, int)`（两参重载）：大小调重构后只留 `degreeLabel(tonic, minor, pitch)`，两参版本已成死代码。

回归：`InputModeTest.pitchBadgeShowsWhileHoldingAndLingersOneSecond`（按新入场行为改写时间点：进场即亮、1 秒后灭）、`InputModeTest.switchingToOneNoteLightsThePitchBadgeForASecond`（W → 亮且音高 +1、写 C#4；0.5 秒仍亮、1 秒后灭；D 键 D 音不亮牌；Ctrl+U → 亮、+13、写 C#5）；全量 **174 项**全绿。

### v1.32 添加和弦（音轨方法）

1. 命令 `TRACK_ADD_CHORD`（音轨菜单「添加和弦…」，左侧音轨面板右键同款；无快捷键）：**永远以当前音轨为旋律**，和弦音按音级写到它下面的新音轨（`"<旋律轨名> · 三音"` / `"· 五音"` …），旋律原轨一条都不动。作用域是 `CommandScope.TRACK`，启用条件只有 `hasTrack` —— 空音轨也不发虚（点了就是什么都不做，不产生空编辑）。
2. 对话框只有两项：类型（三和弦 `{2,4}` / 七和弦 `{2,4,6}` / 六和弦 `{2,4,5}` / 挂四 `{3,4}` / 五度 `{4}`）与位置（上方 / 下方）。
3. 音高算法 `chordInterval(pitch, steps, tonic, minor, below)`：把根音换算成「绝对音级」（`octave*7 + degree`），±steps 级再折回音高——调内旋律得到调内和弦（C 大调 C → E+G，A 小调 A → C+E）；根音不在音阶上时退回调式的固定音程（大调三音 +4、小调三音 +3、五音 +7、七音 +11/+10）。和弦音与原音 `startTick` / `durationTicks` / `velocity` 完全一致。
4. **只追加、不删不改**（v1.34 定稿）：每次调用都新建一组音轨插到旋律轨下面，绝不删除或改写已有音轨 —— 就跟「添加音轨」一样普通。所以（a）重复点会真的多出一组和弦轨；（b）对和弦轨再点一次也照样按算法叠出新的音轨；（c）`Track` 上不再有任何“这是和弦轨”的隐藏字段（`chordSourceId` 已删）。
5. 撤销：一条 `SimpleEdit`（建新轨 + 补音 / 删新轨 + 删音），只回收这一次加的东西。

回归：`AutoChordTest` 8 项（和弦轨落在旋律下面 + 大调叠音 + 起止力度、A 小调 A→C+E、半音 C#→F+G#、往下方叠、七和弦三条轨、重复执行再加一组且撤销只退这一组、和弦轨自己也能再加和弦、空音轨命令仍可用且右键菜单带「添加和弦」）。

### v1.33 音轨面板右键菜单 + 开播前收干净

1. 音轨面板右键：`MenuBarBuilder.addTrackItems()` 抽出来给顶部「音轨」菜单和 `trackPopup()` 共用（`item`/`checkItem` 改成收 `Container`，右键版用 `stripMnemonic()` 去掉「(A)」这类助记符括号）。
2. **右键即选中**：不再用 `setComponentPopupMenu`（L&F 抢先弹菜单，选中时机不可靠），改成自己处理 popup trigger —— 先 `list.setSelectedIndex` + `view.setActiveTrack(...)`，再 `popup.show(...)`；列表外的空白/按钮区也挂同一份菜单。
3. **开头那声短促怪音**：单击平行线 / 方向键走位 / 输入模式按住 Q 都会调 `SynthService.audition/previewOn`，那个音还挂着的时候按播放，`resetAllChannels()` 的 CC120 会把它硬切；更隐蔽的是 `pendingNoteOns` 里**已排进合成器队列、还没到点**的 note-on —— 它们没法撤回，一开播就会在开头冒出来。
   - `play()`：先 `panic()` 再 `pendingNoteOns.clear()` + `resetAllChannels()`（顺序很重要，先收干净再重置）；
   - `panic()`：对每条 pending note-on 在同一时间戳补 `NOTE_OFF` + `CC120`；
   - `seek()`：不管在播还是暂停都先 `panic()`（“回到开头”就是把排队的东西一并清掉）；
   - `nextIndex` / `cycleOffsetUs` 合成一个 `volatile record Cursor`，消除 EDT 重新锚定与调度线程共享可变 int 的竞态（半新半旧的 index 会让一串“后面的音”在开头被投出去）；
   - `togglePlayPause()` 再补 `endInputAudition()` + 按住中的输入音 `abortInputNote()`。
4. 回归：`PlaybackStartTest`（假 Receiver 记录消息：先 audition 72，再 play 一个从 60 开始的文档 → 必须先看到 NOTE_OFF 72、且 NOTE_ON 60 在它之后，72/60 各只响一次）。

### v1.35 试听与浮牌补齐（点边缘 / 方向键 / WASD）

1. **点右边缘也试听**：`PianoRollCanvas.mouseReleased` 原来只认 `mode == MOVE`，按在右端 6px 内是 `RESIZE` 模式 → 只选中不出声。现在 `(mode == MOVE || mode == RESIZE) && !dragged` 都试听——拖了才是改长度。
2. **浮牌不再只在输入模式画**：`PianoRollPainter.paintInputBadge` 去掉 `host.inputMode()` 这个门槛，泳道跟 `host.inputBadgeTrack()`（新字段，为空才退回活动音轨）；`onUiTick` 里“到点清掉重绘”也提到输入模式判断之外。新增 `flashInputBadge(track, pitch, tick, rest)`：只亮 1 秒、不进入“按住”状态。
3. **←/→ 换选中对象时亮牌**：`stepSelection` 试听后 `flashInputBadge(...)`，所以换一个音就能看到「C#4 | 3」。
4. **WASD 移动也试听 + 亮牌**：`moveSelection` 提交后取 `firstSoundingSelection()`，`auditionNote` + `flashInputBadge`；休止符 ripple 分支不发声。
5. **↑/↓ 退出选中**：`ArrowKeyDispatcher.commandIdFor` 对 `VK_UP`/`VK_DOWN` 返回 null，`PianoRollCanvas.installArrowKeys` 不再绑这两键，`NOTE_SELECT_UP/DOWN` 命令去掉加速键与菜单项（能力 `stepSelection(0, ±1)` 保留）。

回归：`SelectionStepTest.upAndDownKeysNoLongerWalkByPitch`（↑/↓ 不动选中，`stepSelection(0,1)` 仍能走）、`SelectionStepTest.steppingShowsThePitchBadgeForASecond`、`InteractionFixesTest.clickingTheRightEdgeStillAuditionsTheNote`、`InteractionFixesTest.wasdMoveAuditionsTheNoteAndFlashesTheBadge`、`LoopAndArrowKeyTest` 改断言“↑/↓ 无绑定”。

### v1.36 拖动只亮牌不发声、停止不动视野、播放头跟滚

1. **鼠标拖动平行线**：`applySelectionMove` 末尾用 `firstMovingNote()` 取锚点，`flashInputBadge(...)` 亮 1 秒浮牌，但**不调用 `auditionNote`**（拖动中连续发声太吵）；键盘 WASD 仍是“试听 + 浮牌”。单击（含点右边缘不拖）照旧试听。
2. **停止不跳视野**：`stopTransport()` 删掉 `pianoRoll.scrollToTimeStart()`——F4 只把播放头收回 0；只有 F6「回到开头」才把频谱拉回开头。
3. **播放头拖出视野会跟滚**：新增 `PianoRollView.ensureTickVisible(tick)`（`ensureWidthFor` + 只在目标跑出可见范围时才 `setViewPosition` 居中，视野内不乱动），`MidiDocumentView.seekTick` 里调用它——所以标尺拖动、频谱里的红线拖动、程序化 seek 都会自动跟随。

回归：`InteractionFixesTest.mouseDragFlashesTheBadgeWithoutAuditioning`（拖动后浮牌在、`lastAuditionInfo()` 引用不变）、`seekingOutsideTheViewportScrollsToFollow`（视野内不滚、拖到 ppq*40 处滚过去且播放头落在可见范围）、`stopKeepsTheViewportWhereItIs`（`stopTransport` 后 `visibleRect().x` 不变、播放头回 0）。

### v1.37 批量移动：同音高才给反馈

1. 判据从「只选一条」改成 **`selectionSharesOnePitch()`**（选中的发声平行线音高全都一样，单选当然算）：这种情况下 `moveSelection` 照旧 `auditionNote` + `flashInputBadge`，`applySelectionMove` 照旧只亮牌；**音高参差的批量移动才完全静音、不出牌**——同一个音高一个反馈就代表全部，参差的批量则没有任何等价物。
2. 回归：`InteractionFixesTest.batchMovesStaySilentWithoutAnyBadge`（60+64 一起挪 → 静音）、`batchOfOnePitchStillGetsFeedback`（两条都是 60，一起 +3 → 试听 63 且亮牌）；全量 **191 项**。

### v1.39 单击也给浮牌

1. 之前“单击 = 只有声音”（`mouseReleased` 里只调 `auditionNote`）、“拖动 = 只有牌子”（`applySelectionMove` 只 `flashInputBadge`），两边都只给一半反馈。现在新增 `MidiDocumentView.auditionNoteWithBadge(track, note)`，`PianoRollCanvas` 的单击（含点右边缘不拖）走它：**试听 + 亮 1 秒浮牌**；拖动仍旧只亮牌不发声。
2. 回归：`InteractionFixesTest.clickingTheRightEdgeStillAuditionsTheNote` 补断言（单击后 `inputBadgeVisible()` 为真、牌子写 C4）；全量 **191 项**。