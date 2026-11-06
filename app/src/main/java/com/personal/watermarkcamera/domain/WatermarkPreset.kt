package com.personal.watermarkcamera.domain

/**
 * 用户自定义的水印模板：一份有名字的完整设置快照。
 * [settings] 原样包含位置（xFraction / yFraction），应用模板即可还原水印落点。
 */
data class WatermarkPreset(
    val id: String,
    val name: String,
    val createdAt: Long,
    val settings: WatermarkSettings
)
