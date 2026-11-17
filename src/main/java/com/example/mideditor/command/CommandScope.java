package com.example.mideditor.command;

/** 命令作用域，决定路由优先级。 */
public enum CommandScope {
    SELECTION,
    TRACK,
    DOCUMENT,
    APPLICATION
}
