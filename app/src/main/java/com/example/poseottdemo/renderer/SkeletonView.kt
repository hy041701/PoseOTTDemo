package com.example.poseottdemo.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.poseottdemo.model.PoseFrameData
import com.example.poseottdemo.model.PosePoint
import com.example.poseottdemo.scoring.CorrectionBodyPart
import com.example.poseottdemo.scoring.CorrectionLevel
import com.example.poseottdemo.scoring.JointCorrection
import kotlin.math.hypot

class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val TAG = "SkeletonView"
        private const val MIN_VISIBILITY = 0.50f
        private const val MIN_PRESENCE = 0.50f
        private const val CALIBRATION_MIN_CONFIDENCE = 0.60f
        private const val CALIBRATION_EDGE_MARGIN = 0.03f
        private const val REQUIRED_CALIBRATION_FRAMES = 3
        //人体高度不足显示区域的50%时才补偿，最高放大1.8倍，避免靠近后人物过大。
        private const val MIN_BODY_HEIGHT_RATIO = 0.50f
        private const val MAX_CALIBRATION_SCALE = 1.8f
        //MediaPipe中z越小越靠近摄像头；超过该差值才切换前后层，避免轮廓闪烁。
        private const val DEPTH_LAYER_THRESHOLD = 0.035f
        //项目内的曲线调节系数，并非标准软度指标：1为之前效果，略减小使过渡更紧凑。
        //仅调节肘膝切线长度及肩胯连接控制柄，不改变采样精度、半径或描边。
        private const val CURVE_SOFTNESS = 0.85f

        //参考火柴人设计尺寸。
        private const val BASE_VIEW_WIDTH = 135f
        private const val BASE_VIEW_HEIGHT = 240f
    }

    //当前需要显示的姿态帧
    private var poseFrame: PoseFrameData? = null
    private data class DepthOrder(
        var filtered: Float = 0f, var side: Int = 0, var candidate: Int = 0,
        var since: Long = 0L, var samples: Int = 0,
        var sequence: Long = -1L, var updatedAt: Long = 0L
    )
    private val depthOrders = mutableMapOf<Pair<Int, Int>, DepthOrder>()
    private var poseSequence = 0L

    //评分节点产生的关节级反馈；为空时保持原来的白色火柴人。
    private var jointCorrections: Map<CorrectionBodyPart, CorrectionLevel> = emptyMap()

    //临时性能诊断：统计姿态更新速度、实际绘制速度与单次绘制耗时。测试结束后删除。
    private var posePerfUpdateCount = 0L
    private var posePerfDrawCount = 0L
    private var posePerfDrawTotalNs = 0L
    private var posePerfDrawMaxNs = 0L
    private var posePerfWindowStartedAtMs = SystemClock.elapsedRealtime()

    //火柴人画笔
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        //正常显示使用柔白色 #FAFAF8，评分反馈仍保留等级颜色。
        color = Color.rgb(250, 250, 248)
        style = Paint.Style.FILL
    }

    //低饱和灰紫色，与白色身体和蓝色背景区分。
    private val bodyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(143, 130, 163)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * resources.displayMetrics.density
    }

    //绘制渐变背景的画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    //火柴人脚下3D椭圆底盘的分层画笔。
    private val platformShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val platformFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val platformOuterStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val platformHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val platformFrontEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    //火柴人显示区域本身的圆角，不额外绘制边框。
    private val backgroundCornerRadius = 16f * resources.displayMetrics.density

    //所有躯干、肢体和关节会先合并到同一个剪影中，再统一描边和填充。
    private val bodyPath = Path()
    private val bodyPartPath = Path()
    private val foregroundLimbPath = Path()
    private val bodyWithoutLimbPath = Path()
    private val rootProtectionPath = Path()

    //灰蓝到灰紫的半透明渐变：深度介于原版和浅色版之间。
    private val backgroundStartColor = Color.argb(85, 149, 202, 208)

    private val backgroundEndColor = Color.argb(75, 169, 154, 209)

    //光圈中心与人体显示区域底端共用同一位置，避免显示区域停在光圈上缘。
    private val platformCenterBottomInset = 17f * resources.displayMetrics.density

    private val stickLandmarkIds = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 0)

    //在135 × 240参考尺寸下，13个关节各自的圆半径。
    private val baseJointRadii = floatArrayOf(6.1f, 6.1f, 4.2f, 4.2f, 4.0f, 4.0f, 6.2f, 6.2f, 5.2f, 5.2f, 4.5f, 4.5f, 11.3f)

    //固定的火柴人整体缩放比例
    private val fixedStickScale = 1.0f

    //本次手机连接的人体基准大小；完成后固定使用同一个补偿倍数。
    private var isCalibratingBodySize = false
    private var isBodySizeCalibrationCompleted = false
    private var completeBodyFrameCount = 0
    private val calibrationBodyHeightSamples = mutableListOf<Float>()
    private var calibrationScale = 1.0f

    //经过坐标映射后，用于 Canvas 绘制的关节。
    private data class DrawJoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val radius: Float,
        val visibility: Float,
        val presence: Float
    ) {
        //判断该点是否足够可信
        fun isReliable(): Boolean { return visibility >= MIN_VISIBILITY && presence >= MIN_PRESENCE && x.isFinite() && y.isFinite() } }

    // MainActivity收到新的PoseFrameData后调用。
    fun updatePose(frame: PoseFrameData) {
        poseSequence++
        poseFrame = frame
        posePerfUpdateCount++
        postInvalidateOnAnimation()  //它相当于告诉Android：我的View内容变化了，请重新绘制。Android之后会调用：onDraw(canvas)
    }

    //倒计时开始时开启标定；同一次手机连接已经完成标定后不重复执行。
    fun startBodySizeCalibration() {
        if (isBodySizeCalibrationCompleted || isCalibratingBodySize) { return }
        isCalibratingBodySize = true
        completeBodyFrameCount = 0
        calibrationBodyHeightSamples.clear()
        calibrationScale = 1.0f
    }

    //倒计时期间只观察Pose数据，不显示火柴人。
    fun observeBodySizeCalibrationFrame(frame: PoseFrameData) {
        if (!isCalibratingBodySize || isBodySizeCalibrationCompleted) { return }
        val person = frame.persons.firstOrNull() ?: run {
            clearCurrentCalibrationSamples()
            return
        }
        if (!isCompleteBodyForCalibration(person.landmarks)) {
            clearCurrentCalibrationSamples()
            return
        }

        val joints = buildDrawJoints(frame, person.landmarks) ?: run {
            clearCurrentCalibrationSamples()
            return
        }
        val bodyHeight = calculateBodyHeight(joints)
        if (bodyHeight <= 0f) {
            clearCurrentCalibrationSamples()
            return
        }

        completeBodyFrameCount++
        calibrationBodyHeightSamples.add(bodyHeight)
        if (completeBodyFrameCount < REQUIRED_CALIBRATION_FRAMES) { return }

        val baselineHeight = calibrationBodyHeightSamples.sorted()[calibrationBodyHeightSamples.size / 2]
        val drawableHeight = (height.toFloat() - platformCenterBottomInset).coerceAtLeast(1f)
        val minimumRequiredHeight = drawableHeight * MIN_BODY_HEIGHT_RATIO
        calibrationScale = if (baselineHeight >= minimumRequiredHeight) {
            1.0f
        } else {
            (minimumRequiredHeight / baselineHeight).coerceIn(1.0f, MAX_CALIBRATION_SCALE)
        }
        isBodySizeCalibrationCompleted = true
        isCalibratingBodySize = false
        calibrationBodyHeightSamples.clear()
        Log.i(TAG, "Body size calibrated: height=$baselineHeight, target=$minimumRequiredHeight, scale=$calibrationScale")
    }

    //5秒结束仍未识别到完整人体时锁定原始手机画面映射。
    fun finishBodySizeCalibration() {
        if (isBodySizeCalibrationCompleted) { return }
        isCalibratingBodySize = false
        isBodySizeCalibrationCompleted = true
        clearCurrentCalibrationSamples()
        calibrationScale = 1.0f
        Log.i(TAG, "Body size calibration timed out; use original mapping")
    }

    //断开手机或重新扫码时才清除本次连接的标定结果。
    fun resetBodySizeCalibration() {
        isCalibratingBodySize = false
        isBodySizeCalibrationCompleted = false
        clearCurrentCalibrationSamples()
        calibrationScale = 1.0f
    }

    private fun clearCurrentCalibrationSamples() {
        completeBodyFrameCount = 0
        calibrationBodyHeightSamples.clear()
    }

    //手机没有检测到人体时调用。
    fun clearPose() {
        poseFrame = null
        depthOrders.clear()
        jointCorrections = emptyMap()
        postInvalidateOnAnimation()
    }

    fun updateCorrections(corrections: List<JointCorrection>) {
        jointCorrections = corrections.associate { it.bodyPart to it.level }
        postInvalidateOnAnimation()
    }

    fun clearCorrections() {
        if (jointCorrections.isEmpty()) { return }
        jointCorrections = emptyMap()
        postInvalidateOnAnimation()
    }

    //修改缩放尺寸
    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldW: Int,
        oldH: Int
    ) {
        super.onSizeChanged(w, h, oldW, oldH)
        rebuildBackgroundShader()
    }

    //渐变背景
    private fun rebuildBackgroundShader() { if (height <= 0) { return }
        backgroundPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), backgroundStartColor, backgroundEndColor, Shader.TileMode.CLAMP) }

    override fun onDraw(canvas: Canvas) {
        val drawStartedNs = System.nanoTime()
        try {
            super.onDraw(canvas)
            // 1. 先绘制背景。
            drawBackground(canvas)

            // 2. 在人体后方绘制脚下的透视光圈。
            drawPlatformRing(canvas)

            // 3. 没有姿态数据时，只显示背景和光圈。
            val frame = poseFrame ?: return

            // 4. 当前业务只绘制第一个人。
            val person = frame.persons.firstOrNull() ?: return

            // 5. 检查手机图像尺寸。
            if (frame.imageWidth <= 0 || frame.imageHeight <= 0) { return }

            // 6. 将 MediaPipe 坐标映射成 View 中的 13 个关节。
            val rawJoints = buildDrawJoints(frame = frame, landmarks = person.landmarks) ?: return
            val joints = applyCalibrationScale(rawJoints)

            if (jointCorrections.isEmpty()) {
                //连续人体统一描边，内部仅保留前景肢体的遮挡边界。
                drawUnifiedBody(canvas, joints)
            } else {
                //评分只改变同一人体轮廓内的填色，不再重建带关节圆的外轮廓。
                drawSmoothFeedbackBody(canvas, joints)
            }
            //小臂边框最后绘制，不受身体、头部或大臂遮挡。
            drawTopForearmOutlines(canvas, joints)
        } finally {
            recordPoseDrawPerformance(System.nanoTime() - drawStartedNs)
        }
    }

    //每两秒输出一次SkeletonView绘制性能，统一使用POSE_PERF标签。
    private fun recordPoseDrawPerformance(drawDurationNs: Long) {
        posePerfDrawCount++
        posePerfDrawTotalNs += drawDurationNs
        posePerfDrawMaxNs = maxOf(posePerfDrawMaxNs, drawDurationNs)

        val nowMs = SystemClock.elapsedRealtime()
        val elapsedMs = nowMs - posePerfWindowStartedAtMs
        if (elapsedMs < 2_000L) { return }

        val seconds = elapsedMs / 1_000f
        val averageDrawMs = if (posePerfDrawCount == 0L) 0.0
        else posePerfDrawTotalNs / posePerfDrawCount / 1_000_000.0

        Log.i(
            "POSE_PERF",
            "DRAW update=${"%.1f".format(posePerfUpdateCount / seconds)}fps, " +
                    "draw=${"%.1f".format(posePerfDrawCount / seconds)}fps, " +
                    "avg=${"%.2f".format(averageDrawMs)}ms, " +
                    "max=${"%.2f".format(posePerfDrawMaxNs / 1_000_000.0)}ms"
        )

        posePerfUpdateCount = 0L
        posePerfDrawCount = 0L
        posePerfDrawTotalNs = 0L
        posePerfDrawMaxNs = 0L
        posePerfWindowStartedAtMs = nowMs
    }

    //直接绘制圆角渐变背景，避免矩形背景从圆角外露出。
    private fun drawBackground(canvas: Canvas) {
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            backgroundCornerRadius,
            backgroundCornerRadius,
            backgroundPaint
        )
    }

    //使用阴影、渐变填充、后沿高光和前沿暗边叠加出扁椭圆底盘的3D感。
    private fun drawPlatformRing(canvas: Canvas) {
        if (width <= 0 || height <= 0) { return }

        val density = resources.displayMetrics.density
        val centerX = width / 2f
        val centerY = height - platformCenterBottomInset
        val radiusX = width * 0.42f
        val radiusY = 9f * density
        val oval = RectF(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY
        )

        //轻微下移的暗色椭圆让底盘脱离背景，形成悬浮阴影。
        platformShadowPaint.shader = null
        platformShadowPaint.color = Color.argb(65, 40, 31, 62)
        canvas.drawOval(
            RectF(
                oval.left + 2f * density,
                oval.top + 4f * density,
                oval.right - 2f * density,
                oval.bottom + 5f * density
            ),
            platformShadowPaint
        )

        //上浅下深的半透明填充表现盘面和厚度。
        platformFillPaint.shader = LinearGradient(
            0f,
            oval.top,
            0f,
            oval.bottom,
            Color.argb(105, 213, 242, 247),
            Color.argb(82, 91, 71, 132),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(oval, platformFillPaint)

        platformOuterStrokePaint.shader = null
        platformOuterStrokePaint.color = Color.argb(185, 193, 239, 247)
        platformOuterStrokePaint.strokeWidth = 1.4f * density
        canvas.drawOval(oval, platformOuterStrokePaint)

        //椭圆后半圈更亮，模拟顶部受光边缘。
        platformHighlightPaint.shader = null
        platformHighlightPaint.color = Color.argb(220, 235, 252, 255)
        platformHighlightPaint.strokeWidth = 1.8f * density
        canvas.drawArc(oval, 180f, 180f, false, platformHighlightPaint)

        //前半圈加深并加粗，形成朝向观众的底盘侧壁。
        platformFrontEdgePaint.shader = null
        platformFrontEdgePaint.color = Color.argb(180, 69, 52, 101)
        platformFrontEdgePaint.strokeWidth = 2.6f * density
        canvas.drawArc(oval, 0f, 180f, false, platformFrontEdgePaint)
    }

    //将手机摄像头的完整画面固定映射到 SkeletonView。缩放比例只由手机图像尺寸和 SkeletonView 尺寸决定，
    private fun buildDrawJoints(
        frame: PoseFrameData,
        landmarks: List<PosePoint>
    ): List<DrawJoint>? {

        //手机姿态识别图像的真实宽高。
        val imageWidth = frame.imageWidth.toFloat()
        val imageHeight = frame.imageHeight.toFloat()

        //OTT 端 SkeletonView 的实际像素宽高。
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableHeight = (viewHeight - platformCenterBottomInset).coerceAtLeast(1f)

        //尺寸不正确时，这一帧不绘制。
        if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) { return null }

        //建立“MediaPipe 关键点 ID -> PosePoint”的映射。
        val pointsById = arrayOfNulls<PosePoint>(33)

        for (point in landmarks) {
            if (point.id in 0..32 && point.x.isFinite() && point.y.isFinite()
            ) { pointsById[point.id] = point } }

        //将手机完整图像等比例缩放到 SkeletonView。
        val scaleX = viewWidth / imageWidth
        val scaleY = drawableHeight / imageHeight
        val imageScale = minOf(scaleX, scaleY)

        // 手机完整画面映射到 View 后的实际宽高。
        val mappedImageWidth = imageWidth * imageScale
        val mappedImageHeight = imageHeight * imageScale

        /**
         * 将手机完整画面放到 SkeletonView 中央。
         * 如果手机图像和 SkeletonView 都是 9:16，offsetX 和 offsetY 通常接近 0。
         * 如果比例不同，就会在上下或左右产生留白。
         */
        val imageOffsetX = (viewWidth - mappedImageWidth) / 2f
        val imageOffsetY = (drawableHeight - mappedImageHeight) / 2f

        //将 MediaPipe 的归一化 X 坐标转换成 View 中的 X 坐标。
        fun mapX(normalizedX: Float): Float {

            //第一步：归一化坐标恢复成手机图像像素坐标。
            val sourceX = normalizedX * imageWidth

            //第二步：使用固定的手机画面缩放比例映射到 View。
            val baseDrawX = imageOffsetX + sourceX * imageScale

            //第三步：以 SkeletonView 的中心为基准，应用固定的额外缩放。
            val viewCenterX = viewWidth / 2f
            return viewCenterX + (baseDrawX - viewCenterX) * fixedStickScale
        }

        fun mapY(normalizedY: Float): Float {
            val sourceY = normalizedY * imageHeight
            val baseDrawY = imageOffsetY + sourceY * imageScale
            val viewCenterY = drawableHeight / 2f
            return viewCenterY + (baseDrawY - viewCenterY) * fixedStickScale
        }

        //关节半径仍然只根据 SkeletonView 大小变化。
        val radiusScale = minOf(viewWidth / BASE_VIEW_WIDTH, viewHeight / BASE_VIEW_HEIGHT) * fixedStickScale

        //存放最终用于 Canvas 绘制的 13 个点。
        val result = ArrayList<DrawJoint>(stickLandmarkIds.size)

        //从 MediaPipe 33 个点中提取火柴人需要的 13 个点。
        for (index in stickLandmarkIds.indices) {
            val mediaPipeId = stickLandmarkIds[index]
            val point = pointsById[mediaPipeId]

            if (point == null) { result.add(DrawJoint(x = Float.NaN, y = Float.NaN, z = Float.NaN, radius = baseJointRadii[index] * radiusScale, visibility = 0f, presence = 0f)
                )
                continue
            }

            //使用固定画面映射计算绘制坐标。
            val drawX = mapX(point.x)
            var drawY = mapY(point.y)
            //头部略向上移，保留与肩部的间距；不增加脖子。
            if (index == 12) { drawY -= 3f * radiusScale }
            result.add(DrawJoint(x = drawX, y = drawY, z = point.z, radius = baseJointRadii[index] * radiusScale, visibility = point.visibility, presence = point.presence)
            )
        }
        return result
    }

    //标定要求火柴人使用的13个关键点都可靠且没有贴近画面边缘。
    private fun isCompleteBodyForCalibration(landmarks: List<PosePoint>): Boolean {
        val pointsById = landmarks.associateBy { it.id }
        return stickLandmarkIds.all { id ->
            val point = pointsById[id] ?: return@all false
            point.x.isFinite() && point.y.isFinite() &&
                    point.visibility >= CALIBRATION_MIN_CONFIDENCE &&
                    point.presence >= CALIBRATION_MIN_CONFIDENCE &&
                    point.x in CALIBRATION_EDGE_MARGIN..(1f - CALIBRATION_EDGE_MARGIN) &&
                    point.y in CALIBRATION_EDGE_MARGIN..(1f - CALIBRATION_EDGE_MARGIN)
        }
    }

    //使用鼻子到双脚踝中点的距离衡量原始映射后的人体显示高度。
    private fun calculateBodyHeight(joints: List<DrawJoint>): Float {
        val nose = joints[12]
        val leftAnkle = joints[10]
        val rightAnkle = joints[11]
        if (!nose.isReliable() || !leftAnkle.isReliable() || !rightAnkle.isReliable()) { return 0f }
        val ankleCenterY = (leftAnkle.y + rightAnkle.y) / 2f
        return kotlin.math.abs(ankleCenterY - nose.y)
    }

    //围绕当前人体中心仅补偿关节位置，保留原始绘制半径，避免头部和四肢随之变粗。
    //补偿倍数一次确定，后续前进后退仍保留自然大小变化。
    private fun applyCalibrationScale(joints: List<DrawJoint>): List<DrawJoint> {
        if (calibrationScale <= 1.0f) { return joints }

        val leftHip = joints[6]
        val rightHip = joints[7]
        val leftShoulder = joints[0]
        val rightShoulder = joints[1]
        val center = when {
            leftHip.isReliable() && rightHip.isReliable() ->
                ((leftHip.x + rightHip.x) / 2f) to ((leftHip.y + rightHip.y) / 2f)
            leftShoulder.isReliable() && rightShoulder.isReliable() ->
                ((leftShoulder.x + rightShoulder.x) / 2f) to ((leftShoulder.y + rightShoulder.y) / 2f)
            else -> return joints
        }

        return joints.map { joint ->
            if (!joint.isReliable()) {
                joint
            } else {
                joint.copy(
                    x = center.first + (joint.x - center.first) * calibrationScale,
                    y = center.second + (joint.y - center.second) * calibrationScale
                )
            }
        }
    }

    //将躯干、四肢、关节和头部合并成一条没有内部接缝的人体剪影。
    private fun buildBodySilhouette(joints: List<DrawJoint>) {
        buildSmoothSilhouette(joints, bodyPath)
    }

    //构造完整人体或排除某条肢体的底层人体，供真实遮挡裁剪使用。
    private fun buildSmoothSilhouette(joints: List<DrawJoint>, target: Path, excludedRoot: Int = -1, foregroundDepth: Float? = null) {
        val torso = Path()
        bodyPath.reset()
        addTorsoToSilhouette(joints)
        torso.set(bodyPath)
        if (foregroundDepth != null && !isStableBehind(excludedRoot, -1, foregroundDepth, averageDepth(joints, intArrayOf(0,1,6,7)))) {
            torso.reset()
        }
        for (root in intArrayOf(0, 1, 6, 7)) {
            if (root == excludedRoot) { continue }
            if (foregroundDepth != null && !isStableBehind(excludedRoot, root, foregroundDepth, averageDepth(joints, intArrayOf(root+2,root+4)))) { continue }
            val limb = Path()
            buildBodyLimbPath(limb, joints, root)
            torso.op(limb, Path.Op.UNION)
        }
        if (foregroundDepth == null) {
            //替换肩根、腿根附近的旧边界，而不是再叠一块形状掩盖凸起。
            rebuildRootConnections(torso, joints)
        }
        val head = joints[12]
        if (excludedRoot != 12 && head.isReliable() && (foregroundDepth == null || isStableBehind(excludedRoot, 12, foregroundDepth, head.z))) {
            val headPath = Path()
            headPath.addCircle(head.x, head.y, head.radius, Path.Direction.CW)
            torso.op(headPath, Path.Op.UNION)
        }
        //并集只消除内部边界，不会消除交点的尖角；最后统一重建连续外轮廓。
        if (foregroundDepth == null) {
            val radius = joints.filter { it.isReliable() }.map { it.radius }.minOrNull() ?: 1f
            smoothClosedContours(torso, target, (radius*0.85f).coerceAtLeast(1f))
        } else {
            //遮挡裁剪仅需要区域，不重复做外轮廓重建，降低每帧开销。
            target.set(torso)
        }
    }

    //在四个连接区内删除原拼接边缘，以两端边线的方向重建三次曲线。
    //仅处理同一闭合轮廓内的一段，不跨过腋下/裆部开口，也不处理头部。
    private fun rebuildRootConnections(path: Path, joints: List<DrawJoint>) {
        val roots = intArrayOf(0, 1, 6, 7).filter { joints[it].isReliable() && joints[it+2].isReliable() }
        if (roots.isEmpty()) { return }
        val zones = roots.map { index ->
            val root = joints[index]
            val middle = joints[index+2]
            val reach = minOf(root.radius*1.8f, hypot(middle.x-root.x, middle.y-root.y)*0.32f)
            floatArrayOf(root.x, root.y, reach)
        }
        val measure = PathMeasure(path, false)
        val rebuilt = Path().apply { fillType = path.fillType }
        val position = FloatArray(2)
        val spacing = (roots.minOf { joints[it].radius }*0.25f).coerceAtLeast(1f)
        do {
            val length = measure.length
            if (length <= 0.01f) { continue }
            val count = kotlin.math.ceil(length/spacing).toInt().coerceIn(16,2048)
            val points = Array(count) { i ->
                measure.getPosTan(length*i/count, position, null)
                position.copyOf()
            }
            val inside = BooleanArray(count) { i ->
                zones.any { z -> hypot(points[i][0]-z[0], points[i][1]-z[1]) < z[2] }
            }
            val first = inside.indexOfFirst { !it }
            if (first < 0) {
                //极短或退化轮廓不强行重建，避免把整条肢体抹掉。
                val original = Path()
                measure.getSegment(0f,length,original,true)
                original.close()
                rebuilt.addPath(original)
                continue
            }
            fun p(i: Int) = points[((first+i)%count+count)%count]
            fun inZone(i: Int) = inside[(first+i)%count]
            rebuilt.moveTo(p(0)[0],p(0)[1])
            var i = 0
            while (i < count) {
                if (i+1 < count && inZone(i+1)) {
                    var end = i+1
                    while (end < count && inZone(end)) { end++ }
                    val a = p(i)
                    val b = p(end)
                    val before = p(i-1)
                    val after = p(end+1)
                    val tx1 = a[0]-before[0]
                    val ty1 = a[1]-before[1]
                    val tx2 = after[0]-b[0]
                    val ty2 = after[1]-b[1]
                    val n1 = hypot(tx1,ty1).coerceAtLeast(0.01f)
                    val n2 = hypot(tx2,ty2).coerceAtLeast(0.01f)
                    val chord = hypot(b[0]-a[0],b[1]-a[1])
                    //短控制柄限制外扩；接入点顺着躯干/肢体原有方向。
                    val handle = minOf(chord*0.32f, (end-i)*length/count*0.22f) * CURVE_SOFTNESS
                    rebuilt.cubicTo(a[0]+tx1/n1*handle,a[1]+ty1/n1*handle,
                        b[0]-tx2/n2*handle,b[1]-ty2/n2*handle,b[0],b[1])
                    i = end
                } else {
                    val next = p(i+1)
                    rebuilt.lineTo(next[0],next[1])
                    i++
                }
            }
            rebuilt.close()
        } while (measure.nextContour())
        path.set(rebuilt)
    }

    //各闭合边界分别重建，保留头部、腋下及裆部开口，不跨越不同轮廓连接。
    //相邻二次曲线共用中点与切线，消除腰胯、肩肘路径交点处的直角。
    private fun smoothClosedContours(source: Path, target: Path, spacing: Float) {
        val measure = PathMeasure(source, false)
        val result = Path()
        result.fillType = source.fillType
        val position = FloatArray(2)
        do {
            val length = measure.length
            if (length <= 0.01f) { continue }
            val count = kotlin.math.ceil(length / spacing).toInt().coerceIn(12, 2048)
            val points = Array(count) { FloatArray(2) }
            for (i in 0 until count) {
                measure.getPosTan(length*i/count, position, null)
                points[i][0] = position[0]
                points[i][1] = position[1]
            }
            val first = points[0]
            val last = points[count-1]
            result.moveTo((last[0]+first[0])/2f, (last[1]+first[1])/2f)
            for (i in 0 until count) {
                val point = points[i]
                val next = points[(i+1)%count]
                result.quadTo(point[0], point[1], (point[0]+next[0])/2f, (point[1]+next[1])/2f)
            }
            result.close()
        } while (measure.nextContour())
        target.set(result)
    }

    private fun drawSmoothFeedbackBody(canvas: Canvas, joints: List<DrawJoint>) {
        buildBodySilhouette(joints)
        val silhouette = Path(bodyPath)
        if (silhouette.isEmpty) { return }
        drawPathFill(canvas, silhouette, torsoFeedbackColor())
        val checkpoint = canvas.save()
        canvas.clipPath(silhouette)
        val roots = intArrayOf(6, 7, 0, 1)
        val rootParts = arrayOf(CorrectionBodyPart.LEFT_HIP, CorrectionBodyPart.RIGHT_HIP,
            CorrectionBodyPart.LEFT_SHOULDER, CorrectionBodyPart.RIGHT_SHOULDER)
        val endParts = arrayOf(CorrectionBodyPart.LEFT_KNEE, CorrectionBodyPart.RIGHT_KNEE,
            CorrectionBodyPart.LEFT_ELBOW, CorrectionBodyPart.RIGHT_ELBOW)
        for (i in roots.indices) {
            val root = roots[i]
            val limb = Path()
            buildBodyLimbPath(limb, joints, root)
            val limbCheckpoint = canvas.save()
            canvas.clipPath(limb)
            drawPathFill(canvas, limb, feedbackColor(rootParts[i]))
            drawFeedbackSegment(canvas, joints[root+2], joints[root+4], feedbackColor(endParts[i]))
            canvas.restoreToCount(limbCheckpoint)
        }
        val head = joints[12]
        if (head.isReliable()) {
            val originalColor = bodyPaint.color
            bodyPaint.color = feedbackColor(CorrectionBodyPart.HEAD)
            canvas.drawCircle(head.x, head.y, head.radius, bodyPaint)
            bodyPaint.color = originalColor
        }
        canvas.restoreToCount(checkpoint)
        canvas.drawPath(silhouette, bodyOutlinePaint)
        drawOverlapSeparators(canvas, joints)
    }

    //普通显示使用一个完整人体剪影；内部只保留真实前后遮挡产生的分隔线。
    private fun drawUnifiedBody(canvas: Canvas, joints: List<DrawJoint>) {
        buildBodySilhouette(joints)
        if (bodyPath.isEmpty) { return }

        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, bodyOutlinePaint)
        drawOverlapSeparators(canvas, joints)
    }

    //前景手臂压在躯干上、或双腿交叉时，仅在重叠范围内显示边界。
    private fun drawOverlapSeparators(canvas: Canvas, joints: List<DrawJoint>) {
        drawLimbOverlapSeparator(canvas, joints, 0, 2, 4)
        drawLimbOverlapSeparator(canvas, joints, 1, 3, 5)
        drawLimbOverlapSeparator(canvas, joints, 6, 8, 10)
        drawLimbOverlapSeparator(canvas, joints, 7, 9, 11)
        drawHeadOverlapSeparator(canvas, joints)
    }

    //头在手臂前方时也要补边界；仅画手臂的边界会漏掉这种遮挡方向。
    private fun drawHeadOverlapSeparator(canvas: Canvas, joints: List<DrawJoint>) {
        val head = joints[12]
        if (!head.isReliable() || !head.z.isFinite()) { return }
        buildSmoothSilhouette(joints, bodyWithoutLimbPath, 12, head.z)
        if (bodyWithoutLimbPath.isEmpty) { return }
        val checkpoint = canvas.save()
        canvas.clipPath(bodyWithoutLimbPath)
        canvas.drawCircle(head.x, head.y, head.radius, bodyOutlinePaint)
        canvas.restoreToCount(checkpoint)
    }

    //每对部位共用一个前后顺序，避免交叉双臂同时把自己判为前景。
    //至少3个新姿态帧且持续140ms才换层；接近同深度时保留已有顺序。
    private fun isStableBehind(front: Int, back: Int, frontZ: Float, backZ: Float?): Boolean {
        if (!frontZ.isFinite() || backZ == null || !backZ.isFinite()) { return false }
        val direction = if (front < back) 1 else -1
        val key = minOf(front, back) to maxOf(front, back)
        val state = depthOrders.getOrPut(key) { DepthOrder() }
        if (state.sequence != poseSequence) {
            val now = SystemClock.elapsedRealtime()
            val difference = (backZ - frontZ) * direction
            if (state.sequence < 0L || now - state.updatedAt > 300L) {
                state.filtered = difference
                state.side = 0
                state.candidate = 0
                state.samples = 0
            } else {
                state.filtered = state.filtered * 0.65f + difference * 0.35f
            }
            val wanted = when {
                state.filtered > DEPTH_LAYER_THRESHOLD -> 1
                state.filtered < -DEPTH_LAYER_THRESHOLD -> -1
                //深度几乎相同且没有历史顺序时，提供稳定的显示层级。
                //双腿默认左腿在前；手臂与头默认手臂在前。这只是显示回退，不是识别结论。
                state.side == 0 && (key == (6 to 7) || key == (0 to 12) || key == (1 to 12)) -> 1
                else -> state.side
            }
            if (wanted == state.side) {
                state.candidate = 0
                state.samples = 0
            } else {
                if (wanted != state.candidate) {
                    state.candidate = wanted
                    state.since = now
                    state.samples = 0
                }
                state.samples++
                if (state.samples >= 3 && now - state.since >= 140L) {
                    state.side = wanted
                    state.candidate = 0
                    state.samples = 0
                }
            }
            state.sequence = poseSequence
            state.updatedAt = now
        }
        return state.side == direction
    }

    private fun averageDepth(joints: List<DrawJoint>, indices: IntArray): Float? {
        var total = 0f
        var count = 0
        for (index in indices) {
            val joint = joints.getOrNull(index) ?: return null
            if (!joint.isReliable() || !joint.z.isFinite()) { return null }
            total += joint.z
            count++
        }
        return if (count > 0) total / count else null
    }

    private fun drawLimbOverlapSeparator(
        canvas: Canvas,
        joints: List<DrawJoint>,
        rootIndex: Int,
        middleIndex: Int,
        endIndex: Int
    ) {
        val root = joints[rootIndex]
        val middle = joints[middleIndex]
        val end = joints[endIndex]
        if (!root.isReliable() || !middle.isReliable() || !end.isReliable()) { return }
        if (!middle.z.isFinite() || !end.z.isFinite()) { return }

        //肢体没有明显靠近摄像头时不画内部线，避免把后方肢体误画到身体前面。
        val limbDepth = (middle.z + end.z) / 2f
        //前后关系由下面的成对稳定判定统一决定，裁剪阶段不再直接比较单帧深度。

        buildBodyLimbPath(foregroundLimbPath, joints, rootIndex)

        //必须独立构造下方身体，不能从人体并集中减掉肢体（那会同时丢失交叠区域）。
        buildSmoothSilhouette(joints, bodyWithoutLimbPath, rootIndex, limbDepth)

        //肩或髋是自然连接点，该范围不允许出现内部接缝。
        rootProtectionPath.reset()
        rootProtectionPath.addCircle(
            root.x,
            root.y,
            root.radius * 1.35f,
            Path.Direction.CW
        )
        bodyWithoutLimbPath.op(rootProtectionPath, Path.Op.DIFFERENCE)
        if (bodyWithoutLimbPath.isEmpty) { return }

        val checkpoint = canvas.save()
        canvas.clipPath(bodyWithoutLimbPath)
        canvas.drawPath(foregroundLimbPath, bodyOutlinePaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawTopForearmOutlines(canvas: Canvas, joints: List<DrawJoint>) {
        for (root in intArrayOf(0, 1)) {
            val elbow = joints[root + 2]
            val wrist = joints[root + 4]
            if (!elbow.isReliable() || !wrist.isReliable()) { continue }
            val shoulder = if (joints[root].isReliable()) joints[root] else elbow.copy(
                x = elbow.x - (wrist.x - elbow.x), y = elbow.y - (wrist.y - elbow.y)
            )
            //开放边框：两侧及腕端半圆，不闭合肘端，也不画腕部完整圆圈。
            buildLimbPath(foregroundLimbPath, shoulder, elbow, wrist, forearmOnly = true)
            canvas.drawPath(foregroundLimbPath, bodyOutlinePaint)
        }
    }

    //腿根不补圆帽，沿身体方向伸入躯干少量距离，避免取消圆帽后产生缝隙。
    private fun buildBodyLimbPath(target: Path, joints: List<DrawJoint>, rootIndex: Int) {
        var root = joints[rootIndex]
        val isLeg = rootIndex == 6 || rootIndex == 7
        if (isLeg && root.isReliable()) {
            val shoulder = joints[rootIndex - 6]
            if (shoulder.isReliable()) {
                val dx = shoulder.x-root.x
                val dy = shoulder.y-root.y
                val distance = hypot(dx,dy).coerceAtLeast(0.01f)
                val inset = minOf(root.radius*0.45f, distance*0.1f)
                root = root.copy(x = root.x+dx/distance*inset, y = root.y+dy/distance*inset)
            }
        }
        //肩根和腿根都不补圆帽，外形交给专门的连接曲线。
        buildLimbPath(target, root, joints[rootIndex+2], joints[rootIndex+4], roundRoot = false)
    }

    private fun buildLimbPath(
        target: Path,
        root: DrawJoint,
        middle: DrawJoint,
        end: DrawJoint,
        forearmOnly: Boolean = false,
        roundRoot: Boolean = true
    ) {
        target.reset()
        if (!root.isReliable() || !middle.isReliable() || !end.isReliable()) { return }
        val dx1 = middle.x - root.x
        val dy1 = middle.y - root.y
        val dx2 = end.x - middle.x
        val dy2 = end.y - middle.y
        val length1 = hypot(dx1, dy1).coerceAtLeast(0.01f)
        val length2 = hypot(dx2, dy2).coerceAtLeast(0.01f)
        var tx = dx1 / length1 + dx2 / length2
        var ty = dy1 / length1 + dy2 / length2
        //完全折叠时平均方向接近零，保留入射方向以免中心轮廓塌缩。
        if (hypot(tx,ty) < 0.01f) { tx = dx1/length1; ty = dy1/length1 }
        val tangentLength = hypot(tx, ty).coerceAtLeast(0.01f)
        val bendLength = minOf(length1, length2) * 0.65f * CURVE_SOFTNESS
        val midTx = tx / tangentLength * bendLength
        val midTy = ty / tangentLength * bendLength
        val left = ArrayList<Pair<Float, Float>>(25)
        val right = ArrayList<Pair<Float, Float>>(25)
        //整条肢体共享肘/膝切线，不再在关节上叠圆；宽度平滑地由粗变细。
        for (segment in (if (forearmOnly) 1 else 0)..1) {
            val a = if (segment == 0) root else middle
            val b = if (segment == 0) middle else end
            val ax = if (segment == 0) dx1 else midTx
            val ay = if (segment == 0) dy1 else midTy
            val bx = if (segment == 0) midTx else dx2
            val by = if (segment == 0) midTy else dy2
            for (step in (if (segment == 0 || forearmOnly) 0 else 1)..12) {
                val t = step / 12f
                val t2 = t * t
                val t3 = t2 * t
                val x = (2*t3-3*t2+1)*a.x + (t3-2*t2+t)*ax + (-2*t3+3*t2)*b.x + (t3-t2)*bx
                val y = (2*t3-3*t2+1)*a.y + (t3-2*t2+t)*ay + (-2*t3+3*t2)*b.y + (t3-t2)*by
                val vx = (6*t2-6*t)*a.x + (3*t2-4*t+1)*ax + (-6*t2+6*t)*b.x + (3*t2-2*t)*bx
                val vy = (6*t2-6*t)*a.y + (3*t2-4*t+1)*ay + (-6*t2+6*t)*b.y + (3*t2-2*t)*by
                val len = hypot(vx, vy).coerceAtLeast(0.01f)
                val radius = a.radius + (b.radius-a.radius)*(3*t2-2*t3)
                left.add((x-vy/len*radius) to (y+vx/len*radius))
                right.add((x+vy/len*radius) to (y-vx/len*radius))
            }
        }
        target.moveTo(left.first().first, left.first().second)
        left.drop(1).forEach { target.lineTo(it.first, it.second) }
        if (forearmOnly) {
            //两个四分之一圆组成腕端半圆，两侧在肘端开放，不产生内部封口线。
            val ux = dx2/length2
            val uy = dy2/length2
            val nx = -uy
            val ny = ux
            val r = end.radius
            val k = r*0.5522848f
            val tipX = end.x+ux*r
            val tipY = end.y+uy*r
            val l = left.last()
            val q = right.last()
            target.cubicTo(l.first+ux*k,l.second+uy*k,tipX+nx*k,tipY+ny*k,tipX,tipY)
            target.cubicTo(tipX-nx*k,tipY-ny*k,q.first+ux*k,q.second+uy*k,q.first,q.second)
            right.asReversed().drop(1).forEach { target.lineTo(it.first,it.second) }
            return
        }
        right.asReversed().forEach { target.lineTo(it.first, it.second) }
        target.close()
        //腿根隐藏于躯干，不再增加向外突出的圆帽。
        for (joint in (if (roundRoot) listOf(root, end) else listOf(end))) {
            val cap = Path()
            cap.addCircle(joint.x, joint.y, joint.radius, Path.Direction.CW)
            target.op(cap, Path.Op.UNION)
        }
    }

    //在身体自身坐标系内构建胸、腰、骨盆，倾斜时随身体转动。
    private fun addTorsoToSilhouette(joints: List<DrawJoint>) {
        val a = joints[0]
        val b = joints[1]
        val c = joints[6]
        val d = joints[7]
        if (!a.isReliable() || !b.isReliable() || !c.isReliable() || !d.isReliable()) { return }
        val sx = (a.x+b.x)/2f
        val sy = (a.y+b.y)/2f
        val dx = (c.x+d.x)/2f-sx
        val dy = (c.y+d.y)/2f-sy
        val length = hypot(dx,dy)
        if (length < 0.01f) { return }
        val vx = dx/length
        val vy = dy/length
        val ux = vy
        val uy = -vx
        val sr = (a.radius+b.radius)/2f
        val hr = (c.radius+d.radius)/2f
        val shoulderCenterWidth = kotlin.math.abs((b.x-a.x)*ux+(b.y-a.y)*uy)/2f
        //胸侧在肩点内侧，顶部另行衔接肩圆，避免胸部随肩圆一起外扩。
        val chestWidth = (shoulderCenterWidth - sr*0.25f).coerceAtLeast(shoulderCenterWidth*0.65f)
        val chestStart = minOf(sr*1.2f, length*0.18f)
        val hipWidth = kotlin.math.abs((d.x-c.x)*ux+(d.y-c.y)*uy)/2f + hr*0.8f
        val torsoBottom = (length - hr*0.15f).coerceAtLeast(length*0.8f)
        val sideTransition = (torsoBottom - chestStart) / 3f
        fun x(u:Float,v:Float) = sx+u*ux+v*vx
        fun y(u:Float,v:Float) = sy+u*uy+v*vy
        fun curve(u1:Float,v1:Float,u2:Float,v2:Float,u3:Float,v3:Float) {
            bodyPartPath.cubicTo(x(u1,v1),y(u1,v1),x(u2,v2),y(u2,v2),x(u3,v3),y(u3,v3))
        }
        bodyPartPath.reset()
        bodyPartPath.moveTo(x(-shoulderCenterWidth,-sr),y(-shoulderCenterWidth,-sr))
        curve(-shoulderCenterWidth*0.4f,-sr,shoulderCenterWidth*0.4f,-sr,shoulderCenterWidth,-sr)
        //顶部与肩圆共用水平切线，然后收进胸侧。
        curve(shoulderCenterWidth+sr*0.55f,-sr,chestWidth,0f,chestWidth,chestStart)
        //胸侧直接平滑过渡到胯部，不再额外缩窄腰部。
        curve(chestWidth,chestStart+sideTransition,hipWidth,torsoBottom-sideTransition,hipWidth,torsoBottom)
        //底边位于胯点中心略上方，保留与大腿根部的重叠，不单独描腰胯接缝。
        curve(hipWidth*0.5f,torsoBottom,-hipWidth*0.5f,torsoBottom,-hipWidth,torsoBottom)
        curve(-hipWidth,torsoBottom-sideTransition,-chestWidth,chestStart+sideTransition,-chestWidth,chestStart)
        curve(-chestWidth,0f,-shoulderCenterWidth-sr*0.55f,-sr,-shoulderCenterWidth,-sr)
        bodyPartPath.close()
        unionBodyPart()
    }

    private fun drawFeedbackSegment(
        canvas: Canvas,
        start: DrawJoint,
        end: DrawJoint,
        color: Int
    ) {
        if (!start.isReliable() || !end.isReliable()) { return }

        foregroundLimbPath.reset()
        addTaperedLimbToPath(foregroundLimbPath, start, end)
        for (joint in listOf(start, end)) {
            bodyPartPath.reset()
            bodyPartPath.addCircle(joint.x, joint.y, joint.radius, Path.Direction.CW)
            unionPathPart(foregroundLimbPath)
        }

        drawPathFill(canvas, foregroundLimbPath, color)
    }

    private fun feedbackColor(bodyPart: CorrectionBodyPart): Int {
        return when (jointCorrections[bodyPart]) {
            // 柔和但仍保持绿、黄、红的等级语义。
            CorrectionLevel.GOOD -> Color.rgb(126, 178, 150)   // 鼠尾草绿
            CorrectionLevel.WARNING -> Color.rgb(214, 179, 108) // 燕麦黄
            CorrectionLevel.ERROR -> Color.rgb(205, 126, 126)  // 灰豆沙红
            null -> bodyPaint.color
        }
    }

    //上半身颜色由8个四肢评分节点汇总：任一正确则为绿；全部错误时按最严重等级显示。
    private fun torsoFeedbackColor(): Int {
        val limbParts = listOf(
            CorrectionBodyPart.LEFT_SHOULDER,
            CorrectionBodyPart.RIGHT_SHOULDER,
            CorrectionBodyPart.LEFT_ELBOW,
            CorrectionBodyPart.RIGHT_ELBOW,
            CorrectionBodyPart.LEFT_HIP,
            CorrectionBodyPart.RIGHT_HIP,
            CorrectionBodyPart.LEFT_KNEE,
            CorrectionBodyPart.RIGHT_KNEE
        )
        val levels = limbParts.mapNotNull(jointCorrections::get)
        return when {
            levels.any { it == CorrectionLevel.GOOD } -> feedbackColorForLevel(CorrectionLevel.GOOD)
            levels.size == limbParts.size && levels.any { it == CorrectionLevel.ERROR } ->
                feedbackColorForLevel(CorrectionLevel.ERROR)
            levels.size == limbParts.size -> feedbackColorForLevel(CorrectionLevel.WARNING)
            else -> bodyPaint.color
        }
    }

    private fun feedbackColorForLevel(level: CorrectionLevel): Int {
        return when (level) {
            CorrectionLevel.GOOD -> Color.rgb(126, 178, 150)
            CorrectionLevel.WARNING -> Color.rgb(214, 179, 108)
            CorrectionLevel.ERROR -> Color.rgb(205, 126, 126)
        }
    }

    private fun drawPathFill(canvas: Canvas, path: Path, color: Int) {
        val originalColor = bodyPaint.color
        bodyPaint.color = color
        canvas.drawPath(path, bodyPaint)
        bodyPaint.color = originalColor
    }

    //生成一段肢体并合并到指定的剪影Path。
    private fun addTaperedLimbToPath(
        targetPath: Path,
        start: DrawJoint,
        end: DrawJoint
    ) {
        val directionX = end.x - start.x
        val directionY = end.y - start.y

        val length = hypot(directionX.toDouble(), directionY.toDouble()).toFloat()

        // 两点几乎重叠时不绘制，避免除以 0。
        if (length < 0.001f) { return }

        //得到与肢体方向垂直的单位向量。
        val normalX = -directionY / length
        val normalY = directionX / length

        //起点两侧的两个顶点。
        val startLeftX = start.x + normalX * start.radius
        val startLeftY = start.y + normalY * start.radius
        val startRightX = start.x - normalX * start.radius
        val startRightY = start.y - normalY * start.radius

        //终点两侧的两个顶点。
        val endRightX = end.x - normalX * end.radius
        val endRightY = end.y - normalY * end.radius
        val endLeftX = end.x + normalX * end.radius
        val endLeftY = end.y + normalY * end.radius

        //两侧轻微向外弯曲，向外12%的幅度。
        val curveAmount = minOf(start.radius, end.radius) * 0.12f
        val firstControlRatio = 0.34f
        val secondControlRatio = 0.68f

        bodyPartPath.reset()
        bodyPartPath.moveTo(startLeftX, startLeftY)
        bodyPartPath.cubicTo(
            startLeftX + directionX * firstControlRatio + normalX * curveAmount,
            startLeftY + directionY * firstControlRatio + normalY * curveAmount,
            startLeftX + directionX * secondControlRatio + normalX * (end.radius - start.radius) * secondControlRatio + normalX * curveAmount,
            startLeftY + directionY * secondControlRatio + normalY * (end.radius - start.radius) * secondControlRatio + normalY * curveAmount,
            endLeftX,
            endLeftY
        )
        bodyPartPath.lineTo(endRightX, endRightY)
        bodyPartPath.cubicTo(
            endRightX - directionX * (1f - secondControlRatio) - normalX * curveAmount,
            endRightY - directionY * (1f - secondControlRatio) - normalY * curveAmount,
            endRightX - directionX * (1f - firstControlRatio) - normalX * (start.radius - end.radius) * (1f - firstControlRatio) - normalX * curveAmount,
            endRightY - directionY * (1f - firstControlRatio) - normalY * (start.radius - end.radius) * (1f - firstControlRatio) - normalY * curveAmount,
            startRightX,
            startRightY
        )
        bodyPartPath.close()
        unionPathPart(targetPath)
    }

    //Path 并集只保留整个人体最外层轮廓，不会在关节连接处产生内部描边。
    private fun unionBodyPart() {
        unionPathPart(bodyPath)
    }

    //将当前 bodyPartPath 合并进目标 Path。
    private fun unionPathPart(targetPath: Path) {
        if (targetPath.isEmpty) {
            targetPath.set(bodyPartPath)
        } else {
            targetPath.op(bodyPartPath, Path.Op.UNION)
        }
    }
}
