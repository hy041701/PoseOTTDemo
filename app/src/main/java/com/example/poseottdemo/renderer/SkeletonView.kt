package com.example.poseottdemo.renderer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.poseottdemo.model.PoseFrameData
import com.example.poseottdemo.model.PosePoint
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

        //参考火柴人设计尺寸。当前XML中的火柴人容器是90dp × 160dp，因此以高度240作为半径缩放基准。
        private const val BASE_VIEW_WIDTH = 135f
        private const val BASE_VIEW_HEIGHT = 240f
    }

    //当前需要显示的姿态帧
    private var poseFrame: PoseFrameData? = null

    //临时性能诊断：统计姿态更新速度、实际绘制速度与单次绘制耗时。测试结束后删除。
    private var posePerfUpdateCount = 0L
    private var posePerfDrawCount = 0L
    private var posePerfDrawTotalNs = 0L
    private var posePerfDrawMaxNs = 0L
    private var posePerfWindowStartedAtMs = SystemClock.elapsedRealtime()

    //火柴人画笔
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    //深灰紫色轮廓同时区别白色身体和蓝紫渐变背景。
    private val bodyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(98, 87, 119)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * resources.displayMetrics.density
    }

    //绘制渐变背景的画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    //火柴人显示区域本身的圆角，不额外绘制边框。
    private val backgroundCornerRadius = 16f * resources.displayMetrics.density

    //所有躯干、肢体和关节会先合并到同一个剪影中，再统一描边和填充。
    private val bodyPath = Path()
    private val bodyPartPath = Path()
    private val foregroundLimbPath = Path()

    //顶部使用评分青色的深色版本。
    private var backgroundStartColor = Color.rgb(92, 183, 195)

    //底部使用进度条紫色的深色版本。
    private var backgroundEndColor = Color.rgb(147, 126, 216)

    private val stickLandmarkIds = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28, 0)

    //在90 × 160参考尺寸下，13个关节各自的圆半径。
    private val baseJointRadii = floatArrayOf(6.7f, 6.7f, 4.0f, 4.0f, 2.8f, 2.8f, 7.7f, 7.7f, 5.4f, 5.4f, 4.0f, 4.0f, 11.5f)

    //固定的火柴人整体缩放比例
    private var fixedStickScale = 1.0f

    //一段肢体，由两个内部关节下标组成。
    private val limbConnections = listOf(
        0 to 2,   // 左肩 -> 左肘
        2 to 4,   // 左肘 -> 左腕
        1 to 3,   // 右肩 -> 右肘
        3 to 5,   // 右肘 -> 右腕
        6 to 8,   // 左髋 -> 左膝
        8 to 10,  // 左膝 -> 左踝
        7 to 9,   // 右髋 -> 右膝
        9 to 11   // 右膝 -> 右踝
    )

    //经过坐标映射后，用于 Canvas 绘制的关节。
    private data class DrawJoint(val x: Float, val y: Float, val radius: Float, val visibility: Float, val presence: Float) {
        //判断该点是否足够可信
        fun isReliable(): Boolean { return visibility >= MIN_VISIBILITY && presence >= MIN_PRESENCE && x.isFinite() && y.isFinite() } }

    // MainActivity收到新的PoseFrameData后调用。
    fun updatePose(frame: PoseFrameData) {
        poseFrame = frame
        posePerfUpdateCount++
        postInvalidateOnAnimation()  //它相当于告诉Android：我的View内容变化了，请重新绘制。Android之后会调用：onDraw(canvas)
    }

    //手机没有检测到人体时调用。
    fun clearPose() {
        poseFrame = null
        postInvalidateOnAnimation()
    }

    //修改火柴人颜色
    fun setStickColor(color: Int) {
        bodyPaint.color = color
        postInvalidateOnAnimation()
    }

    //修改渐变背景颜色
    fun setBackgroundGradient(startColor: Int, endColor: Int
    ) {
        backgroundStartColor = startColor
        backgroundEndColor = endColor
        rebuildBackgroundShader()
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

            // 2. 没有姿态数据时，只显示背景。
            val frame = poseFrame ?: return

            // 3. 当前业务只绘制第一个人。
            val person = frame.persons.firstOrNull() ?: return

            // 4. 检查手机图像尺寸。
            if (frame.imageWidth <= 0 || frame.imageHeight <= 0) { return }

            // 5. 将 MediaPipe 坐标映射成 View 中的 13 个关节。
            val joints = buildDrawJoints(frame = frame, landmarks = person.landmarks) ?: return

            //当前参考效果不绘制躯干，因此不再额外构建一次完整人体并集。
            //直接绘制四条独立肢体，可减少每帧重复的 Path.Op.UNION 运算。
            drawHead(canvas, joints[12])
            drawForegroundLimb(canvas, joints, 6, 8, 10)
            drawForegroundLimb(canvas, joints, 7, 9, 11)
            drawForegroundLimb(canvas, joints, 0, 2, 4)
            drawForegroundLimb(canvas, joints, 1, 3, 5)
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

        //尺寸不正确时，这一帧不绘制。
        if (imageWidth <= 0f || imageHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) { return null }

        //建立“MediaPipe 关键点 ID -> PosePoint”的映射。
        val pointsById = arrayOfNulls<PosePoint>(33)

        for (point in landmarks) {
            if (point.id in 0..32 && point.x.isFinite() && point.y.isFinite()
            ) { pointsById[point.id] = point } }

        //将手机完整图像等比例缩放到 SkeletonView。
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
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
        val imageOffsetY = (viewHeight - mappedImageHeight) / 2f

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
            val viewCenterY = viewHeight / 2f
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

            if (point == null) { result.add(DrawJoint(x = Float.NaN, y = Float.NaN, radius = baseJointRadii[index] * radiusScale, visibility = 0f, presence = 0f)
                )
                continue
            }

            //使用固定画面映射计算绘制坐标。
            val drawX = mapX(point.x)
            var drawY = mapY(point.y)
            //头部向下移一点
            if (index == 12) { drawY -= 5f * radiusScale }
            result.add(DrawJoint(x = drawX, y = drawY, radius = baseJointRadii[index] * radiusScale, visibility = point.visibility, presence = point.presence)
            )
        }
        return result
    }

    //将躯干、四肢、关节和头部合并成一条没有内部接缝的人体剪影。
    private fun buildBodySilhouette(joints: List<DrawJoint>) {
        bodyPath.reset()

        //参考代码效果：暂不绘制左右肩与左右髋之间的上半身躯干。
        //肩、髋关节圆和四肢仍然保留，方便直接对比两种绘制方式。

        for ((startIndex, endIndex) in limbConnections) {
            val start = joints[startIndex]
            val end = joints[endIndex]
            if (!start.isReliable() || !end.isReliable()) { continue }
            addTaperedLimbToSilhouette(start = start, end = end)
        }

        //关节圆与躯干、肢体做并集，使肩、髋、肘、膝等位置自然连成一体。
        for (joint in joints) {
            if (!joint.isReliable()) { continue }
            bodyPartPath.reset()
            bodyPartPath.addCircle(joint.x, joint.y, joint.radius, Path.Direction.CW)
            unionBodyPart()
        }
    }

    //躯干沿左右肩圆和左右髋圆的外缘生成平滑曲线。
    private fun addTorsoToSilhouette(joints: List<DrawJoint>) {
        val shoulders = listOf(joints[0], joints[1]).sortedBy { it.x }
        val hips = listOf(joints[6], joints[7]).sortedBy { it.x }
        val leftShoulder = shoulders[0]
        val rightShoulder = shoulders[1]
        val leftHip = hips[0]
        val rightHip = hips[1]

        if (!leftShoulder.isReliable() || !rightShoulder.isReliable() || !leftHip.isReliable() || !rightHip.isReliable()
        ) { return }

        val topControlY = minOf(leftShoulder.y - leftShoulder.radius, rightShoulder.y - rightShoulder.radius)
        val bottomControlY = maxOf(leftHip.y + leftHip.radius, rightHip.y + rightHip.radius)

        bodyPartPath.reset()
        bodyPartPath.moveTo(leftShoulder.x - leftShoulder.radius, leftShoulder.y)
        bodyPartPath.cubicTo(
            leftShoulder.x - leftShoulder.radius * 0.35f, topControlY,
            rightShoulder.x + rightShoulder.radius * 0.35f, topControlY,
            rightShoulder.x + rightShoulder.radius, rightShoulder.y
        )
        bodyPartPath.cubicTo(
            rightShoulder.x + rightShoulder.radius * 0.75f, rightShoulder.y + rightShoulder.radius,
            rightHip.x + rightHip.radius * 0.75f, rightHip.y - rightHip.radius,
            rightHip.x + rightHip.radius, rightHip.y
        )
        bodyPartPath.cubicTo(
            rightHip.x + rightHip.radius * 0.35f, bottomControlY,
            leftHip.x - leftHip.radius * 0.35f, bottomControlY,
            leftHip.x - leftHip.radius, leftHip.y
        )
        bodyPartPath.cubicTo(
            leftHip.x - leftHip.radius * 0.75f, leftHip.y - leftHip.radius,
            leftShoulder.x - leftShoulder.radius * 0.75f, leftShoulder.y + leftShoulder.radius,
            leftShoulder.x - leftShoulder.radius, leftShoulder.y
        )
        bodyPartPath.close()
        unionBodyPart()
    }

    //生成一段两端宽度不同的肢体，并加入完整人体剪影。
    private fun addTaperedLimbToSilhouette(
        start: DrawJoint,
        end: DrawJoint
    ) {
        addTaperedLimbToPath(bodyPath, start, end)
    }

    //把一条完整肢体作为独立前景层绘制，保留它与躯干或其他肢体重叠时的边界。
    private fun drawForegroundLimb(
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

        foregroundLimbPath.reset()
        addTaperedLimbToPath(foregroundLimbPath, root, middle)
        addTaperedLimbToPath(foregroundLimbPath, middle, end)

        for (joint in listOf(root, middle, end)) {
            bodyPartPath.reset()
            bodyPartPath.addCircle(joint.x, joint.y, joint.radius, Path.Direction.CW)
            unionPathPart(foregroundLimbPath)
        }

        canvas.drawPath(foregroundLimbPath, bodyOutlinePaint)
        canvas.drawPath(foregroundLimbPath, bodyPaint)
    }

    //头部不需要几何并集，直接描边和填充即可。
    private fun drawHead(canvas: Canvas, head: DrawJoint) {
        if (!head.isReliable()) { return }
        canvas.drawCircle(head.x, head.y, head.radius, bodyOutlinePaint)
        canvas.drawCircle(head.x, head.y, head.radius, bodyPaint)
    }

    //生成一段肢体并合并到指定的剪影 Path。
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

        bodyPartPath.reset()
        bodyPartPath.moveTo(startLeftX, startLeftY)
        bodyPartPath.lineTo(startRightX, startRightY)
        bodyPartPath.lineTo(endRightX, endRightY)
        bodyPartPath.lineTo(endLeftX, endLeftY)
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
