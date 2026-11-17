package com.example.mideditor.document;

/** 一次可撤销编辑。所有编辑作用于单个文档的模型快照。 */
public interface DocumentEdit {

    String label();

    void undo();

    void redo();
}
