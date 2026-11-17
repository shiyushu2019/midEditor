package com.example.mideditor.command;

/** 全部命令 ID（菜单、工具栏、快捷键共用）。 */
public final class AppCommandIds {

    private AppCommandIds() {
    }

    public static final String FILE_NEW = "file.new";
    public static final String FILE_OPEN = "file.open";
    public static final String FILE_SAVE = "file.save";
    public static final String FILE_SAVE_AS = "file.saveAs";
    public static final String FILE_EXPORT = "file.export";
    public static final String FILE_CLOSE = "file.close";
    public static final String FILE_EXIT = "file.exit";

    public static final String EDIT_UNDO = "edit.undo";
    public static final String EDIT_REDO = "edit.redo";
    public static final String EDIT_CUT = "edit.cut";
    public static final String EDIT_COPY = "edit.copy";
    public static final String EDIT_PASTE = "edit.paste";
    public static final String EDIT_DELETE = "edit.delete";
    public static final String EDIT_SELECT_ALL = "edit.selectAll";
    public static final String EDIT_QUANTIZE = "edit.quantize";
    public static final String EDIT_VELOCITY_UP = "edit.velocityUp";
    public static final String EDIT_VELOCITY_DOWN = "edit.velocityDown";
    public static final String EDIT_SPLIT_AT_PLAYHEAD = "edit.splitAtPlayhead";

    public static final String NOTE_ADD = "note.add";
    public static final String NOTE_LOCK = "note.lock";
    public static final String NOTE_UNLOCK = "note.unlock";
    public static final String NOTE_DELETE = "note.delete";
    public static final String NOTE_SPLIT = "note.split";
    public static final String NOTE_MOVE_UP = "note.moveUp";
    public static final String NOTE_MOVE_DOWN = "note.moveDown";
    public static final String NOTE_MOVE_LEFT = "note.moveLeft";
    public static final String NOTE_MOVE_RIGHT = "note.moveRight";
    public static final String NOTE_LENGTHEN = "note.lengthen";
    public static final String NOTE_SHORTEN = "note.shorten";
    /** Ctrl+→：左端不动，右端往外延长一格（多条选中时按整体只动最外侧）。 */
    public static final String NOTE_EXTEND_RIGHT = "note.extendRight";
    /** Ctrl+←：右端不动，左端往外延长一格（多条选中时按整体只动最外侧）。 */
    public static final String NOTE_EXTEND_LEFT = "note.extendLeft";
    /** Shift+→：右端不动，左端向右收一格（缩短）。 */
    public static final String NOTE_TRIM_LEFT = "note.trimLeft";
    /** Shift+←：左端不动，右端向左收一格（缩短）。 */
    public static final String NOTE_TRIM_RIGHT = "note.trimRight";
    public static final String REST_INSERT = "rest.insert";
    public static final String REST_DELETE = "rest.delete";

    public static final String TRACK_ADD = "track.add";
    public static final String TRACK_REMOVE = "track.remove";
    public static final String TRACK_MOVE_UP = "track.moveUp";
    public static final String TRACK_MOVE_DOWN = "track.moveDown";
    public static final String TRACK_TOGGLE_MUTE = "track.toggleMute";
    public static final String TRACK_TOGGLE_SOLO = "track.toggleSolo";

    public static final String PLAY_PLAY_PAUSE = "play.playPause";
    public static final String PLAY_STOP = "play.stop";
    public static final String PLAY_REWIND = "play.rewind";
    public static final String PLAY_TOGGLE_LOOP = "play.toggleLoop";
    public static final String PLAY_LOOP_TO_SELECTION = "play.loopToSelection";
    public static final String PLAY_LOOP_CLEAR = "play.loopClear";
    public static final String PLAY_TOGGLE_METRONOME = "play.toggleMetronome";

    public static final String VIEW_TRACKS = "view.tracks";
    public static final String VIEW_INSTRUMENTS = "view.instruments";
    public static final String VIEW_MIXER = "view.mixer";
    public static final String VIEW_OUTPUT = "view.output";
    public static final String VIEW_DIAGNOSTICS = "view.diagnostics";
    public static final String VIEW_PROPERTIES = "view.properties";
    public static final String VIEW_GRID_SETTINGS = "view.gridSettings";
    public static final String VIEW_ZOOM_IN = "view.zoomIn";
    public static final String VIEW_ZOOM_OUT = "view.zoomOut";
    public static final String VIEW_ZOOM_RESET = "view.zoomReset";
    public static final String VIEW_TOGGLE_GRID = "view.toggleGrid";
    public static final String VIEW_FOLLOW_PLAYHEAD = "view.followPlayhead";
    public static final String VIEW_KEYBOARD_RULER = "view.keyboardRuler";
    public static final String VIEW_C_HIGHLIGHT = "view.cHighlight";
    public static final String VIEW_PITCH_ZOOM_IN = "view.pitchZoomIn";
    public static final String VIEW_PITCH_ZOOM_OUT = "view.pitchZoomOut";
    public static final String VIEW_PITCH_ZOOM_RESET = "view.pitchZoomReset";
    public static final String VIEW_PITCH_FIT = "view.pitchFit";
    public static final String VIEW_PITCH_SHOW_ALL = "view.pitchShowAll";

    public static final String WINDOW_CASCADE = "window.cascade";
    public static final String WINDOW_TILE = "window.tile";
    public static final String WINDOW_MINIMIZE_ALL = "window.minimizeAll";
    public static final String WINDOW_RESTORE_ALL = "window.restoreAll";
    public static final String WINDOW_CLOSE = "window.close";

    public static final String HELP_ABOUT = "help.about";
}
