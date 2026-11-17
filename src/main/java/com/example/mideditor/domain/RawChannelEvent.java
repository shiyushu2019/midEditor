package com.example.mideditor.domain;

/**
 * 不由模型直接编辑、但必须原样保存/回放的通道事件
 * （弯音、通道/复音触后、以及音量和声像之外的 CC，例如 RPN 调音、延音踏板）。
 */
public record RawChannelEvent(long tick, int command, int data1, int data2) {
}
