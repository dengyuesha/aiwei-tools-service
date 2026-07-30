package com.aiwei.tools.catalog;

/**
 * 工具在新服务中的迁移状态。
 */
public enum ToolStatus {
    /** 已有真实执行器，可以调用。 */
    AVAILABLE,
    /** 已列入迁移范围，但执行器尚未完成。 */
    PLANNED,
    /** 已有代码但仍属于实验能力，默认不对生产开放。 */
    EXPERIMENTAL
}

