# midEditor — 经典 MDI 多音轨 MIDI 编辑器

类 Word 2003 / 旧版 Visual Studio 的 **MDI 文档型桌面应用**：一个主框架窗口 + MDI 客户区 + 多个文档子窗口，
每个子窗口是一个多音轨 MIDI 工程，核心编辑对象是**平行线**（沿横轴延伸的普通条，非圆角音符块）。

## 运行

Maven 已安装到 `D:\Tools\apache-maven-3.10.1`，用户级 `Path` 已包含其 `bin`（新开的终端生效）。

```powershell
mvn clean package
java -jar target\mid-editor-1.0.0-SNAPSHOT.jar
```

若当前终端还是旧会话（PATH 未刷新），新开一个终端即可；仍不行时确认用户级环境变量已生效（必要时重启 VS Code）。

直接编译运行：

```powershell
mvn -q compile
java -cp target\classes com.example.mideditor.MidiEditorApp
```

要求 JDK 21（已在 JDK 21.0.11 上验证）。VS Code 的任务（maven: compile/test/package/clean）也已改为调用 `mvn`。

> 若用 `java -cp target\classes` 方式运行，请加 `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`（打包出的 jar 已在清单里带上该导出，无需额外参数）。没有该参数时播放仍可用，但调度会回退到 8ms 精度。

## 音色（真采样，非单正弦）

- 默认使用 Windows 自带 GM 音色库 `C:\Windows\System32\drivers\gm.dls`（Roland GS，235 个乐器，实测可加载）。
- 可放入 `soundfonts/*.sf2`（如 GeneralUser GS）覆盖默认；取第一个文件。
- 或用 `-Dmideditor.soundfont=<绝对路径>` 指定。
- 若都不可用，退回 JDK 内置默认 SoundBank，并在「诊断」面板如实显示后端。

## 交互

| 操作 | 方式 |
| --- | --- |
| 新建 / 打开 / 保存 / 另存为 | Ctrl+N / Ctrl+O / Ctrl+S / Ctrl+Shift+S（或 F12） |
| 拖拽打开 | 把 .mid / .midi 文件（可多个）直接拖进窗口任意位置 |
| 撤销 / 重做 | Ctrl+Z / Ctrl+Shift+Z 或 Ctrl+Y（**按文档隔离**） |
| 剪切 / 复制 / 粘贴 / 删除 / 全选 | Ctrl+X / Ctrl+C / Ctrl+V / Delete / Ctrl+A |
| 播放 / 暂停 | 空格（文本输入时不会误触发） |
| 停止 / 回到开头 | Ctrl+空格 / Ctrl+Home |
| 循环 / 节拍器 | Ctrl+L / Ctrl+M |
| 添加平行线 | 坐标空白处右键 → 添加平行线；或双击空白处 |
| 试听平行线 | 单击一条平行线（不拖动）即按其音轨音色发声 |
| 新建默认长度 | 右键空白 → 新平行线默认长度；或 视图 → 网格设置 → 新建平行线默认长度 |
| 添加/删除音轨 | 音轨菜单 / 工具栏 + / 子窗口底部「＋轨」「－轨」/ 左侧音轨面板 |
| 切换活动音轨 | 单击音轨头任意位置（双击名称可重命名）、左侧音轨列表或音轨菜单上/下移 |
| 键盘标尺（Y 轴） | 视图 → 键盘标尺（Ctrl+K），或 网格设置面板勾选；与音色无关，总是弹琴键盘 |
| 文档窗口最大化 | 新建/打开文档后子窗口默认在 MDI 客户区内最大化；「窗口 → 层叠/平铺」或「全部还原」取消最大化 |
| 覆盖保护 | 另存为 / 导出目标文件已存在时，会先弹出「确认覆盖」；选择“否”则取消保存 |
| 删除 / 拆分 / 移动 / 拉长缩短 / 力度 | 平行线上右键，或 Alt+↑↓←→ / Alt+Shift+←→ |
| 插入休止 / 删除休止 | 空白处右键（后续平行线整体右移 / 左移补齐） |
| 框选 | 在空白处左键拖出矩形 |
| 拖拽平行线 | 主体拖动=改时间+音高；右端拖动=改长度 |
| 水平缩放 | Alt+滚轮（以鼠标处为锚点）、Ctrl+= / Ctrl+- / Ctrl+0、传输条缩放滑块 |
| 左右滚动 | Shift+滚轮 （或底部水平滚动条） |
| 频谱上下滚动 | 在卷帘 / 键盘标尺上裸滚轮（步长随缩放自适应） |
| 音轨列表滚动 | 在左侧音轨信息区裸滚轮（仅当一屏放不下时） |
| 频率轴上下缩放 | Ctrl+滚轮（以鼠标处音高为锚点）；音轨头 ＋/－；Ctrl+Alt+= / Ctrl+Alt+- / Ctrl+Alt+0 |
| 快速看到全部音高 | 视图 → 音域 → 显示全部音域（128），或 Ctrl+Alt+0 后再继续缩小 |
| 让音域装下所有平行线 | 视图 → 音域 → 适配本音轨平行线（导入后自动生效） |

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
- **往返保真**：弯音（Pitch Bend）、RPN 粗/微调音、延音踏板等通道事件会原样保存并参与回放；每轨可见音域窗口也会写入（自定义 text meta），重新打开后音高与视图不漂移。

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 验证

```powershell
mvn test
```

32 项测试覆盖：tick/微秒换算、.mid 往返（含音域窗口与弯音/RPN 调音）、覆盖确认、撤销栈隔离、命令快捷键与启用态、
MDI 启动为空 / 多文档路由 / 编辑工作流（添加·拆分·移动·拉长·量化·力度·休止·剪贴板·撤销）、
播放引擎时钟前进与 seek、频率轴缩放（行高换算、滚轮滑动/缩放、音域适配）。

## 已知限制

- 音轨「独立垂直滚动」以音域窗口实现（▲▼ / Alt+滚轮），不额外占用滚动条。
- 频率轴精度：可见半音数 12–128 可调，128 时每行约 2px，适合总览；编辑时建议放大到 24–48 行。
- 循环区间默认取当前选区；无选区时取整个文档。
- 鼓组使用 MIDI 通道 10（Gervill 打击乐映射）。
- 缺少音色库时退回 JDK 默认 SoundBank（不伪造乐器音色，诊断面板会标注后端）。
