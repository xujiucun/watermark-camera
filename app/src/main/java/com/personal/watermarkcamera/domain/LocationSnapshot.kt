package com.personal.watermarkcamera.domain

/**
 * 位置水印的数据快照。
 * 逆地理成功时带 [district] / [detail]；失败但拿到坐标时只带 [coordinates]；
 * 完全拿不到位置时三个字段都为 null，由界面降级成「未获取位置」。
 * [weatherText] 仅在拿到天气时非空（如「晴 16°C」），拿不到就整段不显示。
 */
data class LocationSnapshot(
    val district: String? = null,
    val detail: String? = null,
    val coordinates: String? = null,
    val weatherText: String? = null
)
