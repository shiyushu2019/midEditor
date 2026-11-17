# midEditor — 经典 MDI 多音轨 MIDI 编辑器

类 Word 2003 / 旧版 Visual Studio 的 **MDI 文档型桌面应用**：一个主框架窗口 + MDI 客户区 + 多个文档子窗口，
每个子窗口是一个多音轨 MIDI 工程，核心编辑对象是**平行线**（沿横轴延伸的普通条，非圆角音符块）。

## 运行

```powershell
.\mvnw.cmd package
java -jar target\mid-editor-1.0.0-SNAPSHOT.jar
```

或直接：

```powershell
.\mvnw.cmd -q compile
java -cp target\classes com.example.mideditor.MidiEditorApp
```

要求 JDK 21（已在 JDK 21.0.11 上验证）。

## 音色（真采样，非单正弦）

- 默认使用 Windows 自带 GM 音色库 `C:\Windows\System32\drivers\gm.dls`（Roland GS，235 个乐器，实测可加载）。
- 可放入 `soundfonts/*.sf2`（如 GeneralUser GS）覆盖默认；取第一个文件。
- 或用 `-Dmideditor.soundfont=<绝对路径>` 指定。
- 若都不可用，退回 JDK 内置默认 SoundBank，并在「诊断」面板如实显示后端。

## 交互

| 操作 | 方式 |
| --- | --- |
| 新建 / 打开 / 保存 / 另存为 | Ctrl+N / Ctrl+O / Ctrl+S / Ctrl+Shift+S |
| 撤销 / 重做 | Ctrl+Z / Ctrl+Shift+Z（**按文档隔离**） |
| 剪切 / 复制 / 粘贴 / 删除 / 全选 | Ctrl+X / Ctrl+C / Ctrl+V / Delete / Ctrl+A |
| 播放 / 暂停 | 空格（文本输入时不会误触发） |
| 停止 / 回到开头 | Ctrl+空格 / Ctrl+Home |
| 循环 / 节拍器 | Ctrl+L / Ctrl+M |
| 添加平行线 | 坐标空白处右键 → 添加平行线；或双击空白处 |
| 删除 / 拆分 / 移动 / 拉长缩短 / 力度 | 平行线上右键，或 Alt+↑↓←→ / Alt+Shift+←→ |
| 插入休止 / 删除休止 | 空白处右键（后续平行线整体右移 / 左移补齐） |
| 框选 | 在空白处左键拖出矩形 |
| 拖拽平行线 | 主体拖动=改时间+音高；右端拖动=改长度 |
| 水平缩放 | Ctrl+滚轮、Ctrl+= / Ctrl+- / Ctrl+0 |
| 音轨纵向音域滚动 | 音轨头 ▲▼，或在该音轨上 Alt+滚轮 |

「视图」菜单可显示/隐藏 7 个工具窗口（音轨、乐器、混音器、输出、诊断、属性、网格设置），
并可层叠/平铺/最小化/还原 MDI 子窗口；「窗口」菜单列出所有文档用于切换活动文档。

## 架构

```
MainFrame                    标题栏 / 菜单栏 / 工具栏 / 状态栏 / 停靠区
└─ MdiWorkspace (JDesktopPane)          MDI Client Area
    └─ MdiChildFrame (JInternalFrame)    一个文档 = 一个子窗口
        └─ MidiDocumentView              独立撤销栈 / 选区 / 脏状态 / 播放状态
            └─ MidiDocument              领域模型（tick 时间轴）
```

- **命令系统**：菜单、工具栏、快捷键共享同一 `Action`；`CommandRouter` 按「选中 → 音轨 → 文档 → 应用」路由，
  分发后统一刷新启用态，菜单不可能是摆设。
- **撤销隔离**：`EditStack` 属于 `DocumentView`，跨轨操作使用 `CompositeEdit` 聚合成一条记录。
- **播放**：Gervill + lookahead 调度线程（20ms 唤醒、260ms 前瞻），事件带合成器时钟绝对时间戳；
  播放头由音频时钟换算 tick，播放中 seek 会静音后重新锚定，无缝续播。
- **MIDI 文件**：`javax.sound.midi` 解析/生成（不手写解析器），保留音轨名、乐器、音量、声像、速度、拍号。

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 验证

```powershell
.\mvnw.cmd test
```

15 项测试覆盖：tick/微秒换算、.mid 往返、撤销栈隔离、命令快捷键与启用态、
MDI 启动为空 / 多文档路由 / 编辑工作流（添加·拆分·移动·拉长·量化·力度·休止·剪贴板·撤销）、
播放引擎时钟前进与 seek。

## 已知限制

- 音轨「独立垂直滚动」以音域窗口实现（▲▼ / Alt+滚轮），不额外占用滚动条。
- 循环区间默认取当前选区；无选区时取整个文档。
- 鼓组使用 MIDI 通道 10（Gervill 打击乐映射）。
- 缺少音色库时退回 JDK 默认 SoundBank（不伪造乐器音色，诊断面板会标注后端）。
