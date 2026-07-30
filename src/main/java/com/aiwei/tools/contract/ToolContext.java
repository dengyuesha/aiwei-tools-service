package com.aiwei.tools.contract;

/**
 * 调用方显式传入的设备和语言上下文。
 *
 * @param city 城市
 * @param district 区县
 * @param latitude 纬度
 * @param longitude 经度
 * @param coordinateSystem 坐标系，例如 gcj02 或 wgs84
 * @param locale 语言区域
 * @param timezone 时区
 */
public record ToolContext(
        String city,
        String district,
        Double latitude,
        Double longitude,
        String coordinateSystem,
        String locale,
        String timezone) {

    /**
     * 返回空上下文，避免执行器处理空对象。
     *
     * @return 空上下文
     */
    public static ToolContext empty() {
        return new ToolContext(null, null, null, null, null, null, null);
    }
}

