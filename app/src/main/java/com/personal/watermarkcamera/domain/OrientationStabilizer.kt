package com.personal.watermarkcamera.domain

/** 拍摄方向：竖屏 / 横屏（左、右侧朝上），与 Surface.ROTATION_* 一一对应。 */
enum class CaptureDirection(val isLandscape: Boolean) {
    PORTRAIT(false),

    /** 左侧朝上（传感器角度 ~90°），对应 Surface.ROTATION_270。 */
    LANDSCAPE_LEFT(true),

    /** 右侧朝上（传感器角度 ~270°），对应 Surface.ROTATION_90。 */
    LANDSCAPE_RIGHT(true)
}

/**
 * 把 OrientationEventListener 的角度序列稳定成一次方向决策，避免在边界附近抖动时反复切换。
 *
 * - 滞回：竖屏 → 横屏要越过 [landscapeEnterAngle]，横屏 → 竖屏要回到 [portraitEnterAngle] 以内，
 *   其余角度（含 180° 倒置）都维持现状；
 * - 驻留：新方向必须连续保持 [dwellMillis] 才提交，中途回摆即取消；
 * - 平放：角度不可判定（传 null，传感器在手机接近平放时给出的就是 ORIENTATION_UNKNOWN），
 *   此时维持现状并取消待提交的方向。
 *
 * [update] 返回「本次新提交的方向」，null 表示维持不变。
 */
class OrientationStabilizer(
    private val portraitEnterAngle: Float = 30f,
    private val landscapeEnterAngle: Float = 60f,
    private val dwellMillis: Long = 400L
) {
    private var committed: CaptureDirection = CaptureDirection.PORTRAIT
    private var pending: CaptureDirection? = null
    private var pendingSinceMillis: Long = 0L
    private var lastAngle: Float? = null

    val current: CaptureDirection get() = committed

    /** 待提交方向的预计提交时间；手机静止后不再有传感器回调，界面可据此延时调用 [tick]。 */
    val pendingCommitAtMillis: Long? get() = pending?.let { pendingSinceMillis + dwellMillis }

    fun update(angleDegrees: Float?, nowMillis: Long): CaptureDirection? {
        if (angleDegrees == null) {
            pending = null
            return null
        }
        val angle = normalize(angleDegrees)
        lastAngle = angle
        val candidate = candidateFor(angle)
        if (candidate == null || candidate == committed) {
            pending = null
            return null
        }
        if (pending != candidate) {
            pending = candidate
            pendingSinceMillis = nowMillis
            return null
        }
        if (nowMillis - pendingSinceMillis < dwellMillis) return null
        committed = candidate
        pending = null
        return committed
    }

    /** 驻留到期时补一次判定：手机静止、传感器不再回调时也能完成切换。 */
    fun tick(nowMillis: Long): CaptureDirection? = update(lastAngle, nowMillis)

    private fun candidateFor(angle: Float): CaptureDirection? = when (committed) {
        CaptureDirection.PORTRAIT -> when (angle) {
            in landscapeEnterAngle..(180f - landscapeEnterAngle) ->
                CaptureDirection.LANDSCAPE_LEFT
            in (180f + landscapeEnterAngle)..(360f - landscapeEnterAngle) ->
                CaptureDirection.LANDSCAPE_RIGHT
            else -> null
        }
        else -> when (angle) {
            in 0f..portraitEnterAngle, in (360f - portraitEnterAngle)..360f ->
                CaptureDirection.PORTRAIT
            else -> null
        }
    }

    private fun normalize(degrees: Float): Float {
        var angle = degrees % 360f
        if (angle < 0f) angle += 360f
        return angle
    }
}
