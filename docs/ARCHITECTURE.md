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
- 播放头动画只 `repaint` 播放头新旧位置的窄条（约 8 px 宽），不触发全量重绘；泳道内容变化用脏矩形合并。
- 双缓冲由 Swing 默认提供；动画由独立 `javax.swing.Timer`（60 Hz）+ EDT 合并重绘。

**命中与交互**：`HitTester` 统一提供 `hitAt(x,y)`（命中平行线主体 / 右端手柄 6 px / 空白）；`DragSession` 三种模式：MOVE（pitch+time）、RESIZE_END（duration）、MARQUEE。

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
| 保存/重开后音高偏移 | 根因：①重新打开时每轨音域窗口被 `autoRangeFromNotes` 重定位；②弯音/RPN 调音等通道事件保存时被丢弃；③合成器通道残留上一个文档的调音状态。修复：写入 `mideditor:yRange=` 标记并恢复；`RawChannelEvent` 保留弯音/RPN/踏板等并参与回放；每次播放/试听前 `resetAllChannels()`（弯音回中、RPN 归零），seek 时重放目标位置前的状态事件 |
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

### v1.3 滚轮语义统一 + Windows 下「按过 Alt 后滚轮失效」修复

滚动/缩放规则收敛成一张表（画布、键盘标尺、音轨头共用 `PianoRollView.wheel*` 方法）：

| 位置 | 裸滚轮 | Shift+滚轮 | Alt+滚轮 | Ctrl+滚轮 |
| --- | --- | --- | --- | --- |
| 卷帘 / 键盘标尺 | 上下滚动频谱 | 左右滚动 | 左右缩放 | 上下缩放 |
| 左侧音轨信息区 | 滚动一屏放不下的音轨 | 左右滚动 | 左右缩放 | 上下缩放 |

缩放锚点：`wheelZoomTime` 以鼠标处 tick 为锚点重设 `pxPerQuarter` 并回推视口位置（`viewX' = viewX + newX - mouseX`）；`wheelZoomPitch` 由鼠标 y 反算连续音高，用 `PitchRange.aligned(target, anchorPitch + offset×target/laneHeight)` 重建窗口，缩放前后鼠标下的 tick / 音高停在同一像素。

**「按过 Alt 之后滚轮完全没反应」根因**：单独按一下（或按住又松开）Alt 时，Windows 会让窗口进入系统「菜单模式」（`WM_SYSKEYUP`），此期间 OS 直接**丢弃滚轮消息**（键盘与点击仍正常），必须再按一个键或点击一次才会恢复 —— 这就是“滚轮时灵时不灵”的真因。修复：`app/AltReleaseWheelFix` 监听裸 Alt 抬起，用缓存的 `Robot` 补发一个 ESC（本应用不占用 ESC）让窗口立即退出菜单模式；仅在主窗口为活动窗口、且无弹出菜单选择时补发。实测：Alt 抬起后裸滚轮 45→44（修复前为无事件）。

新增 `PitchAxisTest` 用例（统一修饰键语义、Ctrl 缩放锚点音高不变、裸滚轮滑动），测试总计 34 项。
