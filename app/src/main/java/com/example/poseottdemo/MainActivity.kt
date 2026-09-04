package com.example.poseottdemo
import android.animation.ObjectAnimator
import android.util.Log
import com.example.poseottdemo.network.PoseWebSocketServer
import com.example.poseottdemo.model.PoseFrameData
import com.example.poseottdemo.renderer.SkeletonView
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.poseottdemo.pairing.QrCodeGenerator
import com.example.poseottdemo.network.LocalIpProvider
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.poseottdemo.ui.BusinessState
import com.example.poseottdemo.ui.PlaybackMode
import com.example.poseottdemo.ui.OverlayState
import android.widget.Button
import com.example.poseottdemo.scoring.PoseScoringEngine
import com.example.poseottdemo.scoring.PoseConfigLoader
import com.example.poseottdemo.scoring.WorkoutScoreConfig
import com.example.poseottdemo.scoring.JointCorrection
import com.example.poseottdemo.scoring.JointCorrectionMapper
import com.example.poseottdemo.scoring.PoseScoreResult
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import android.widget.ProgressBar
import android.view.animation.LinearInterpolator
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private enum class MainVideoDisplayMode {
        CARD,
        FULLSCREEN,
        SPLIT
    }

    private lateinit var mainVideoPlayerView: PlayerView
    private lateinit var mainVideoCoverView: ImageView
    private lateinit var leftContentContainer: FrameLayout
    private var mainVideoPlayer: ExoPlayer? = null
    private var mainVideoDisplayMode = MainVideoDisplayMode.CARD
    private var poseWebSocketServer: PoseWebSocketServer? = null
    private lateinit var skeletonView: SkeletonView
    private lateinit var practicePanel: FrameLayout
    private lateinit var practicePlayerView: PlayerView
    private var practicePlayer: ExoPlayer? = null
    private lateinit var qrImageView: ImageView
    private lateinit var pairingStatusText: TextView
    private lateinit var videoSelectionPanel: View
    private lateinit var playingPanel: View
    private lateinit var pairingOverlay: View
    //等待用户完成准备动作的独立页面。
    private lateinit var readyCheckOverlay: View

    //动作检测页面上的状态文字。
    private lateinit var tvReadyCheckStatus: TextView
    private lateinit var disconnectedOverlay: View
    private lateinit var exitConfirmationOverlay: View
    private lateinit var skeletonContainer: View
    private lateinit var btnScanPhone: Button
    private lateinit var btnQrBack: Button
    private lateinit var btnDisconnectKnown: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnConfirmExitWorkout: Button
    private lateinit var btnCancelExitWorkout: Button
    private var businessState = BusinessState.HOME
    private var playbackMode = PlaybackMode.NONE
    private var overlayState = OverlayState.NONE
    private var isPhoneConnected = false
    //手机是否正在进行准备动作检测。
    private var isWaitingReadyCheck = false
    private var selectedWorkoutId: String? = null
    private lateinit var workoutPreviewPlayerView: PlayerView
    private var workoutPreviewPlayer: ExoPlayer? = null
    private lateinit var workoutPreviewTitle: TextView
    private lateinit var workoutPreviewIndex: TextView
    private lateinit var btnStartWorkout: Button
    //当前课程评分配置。
    private var scoreConfig: WorkoutScoreConfig? = null
    //当前课程评分引擎。
    private var scoringEngine: PoseScoringEngine? = null
    //下一个需要处理的评分节点下标。
    private var nextScoreNodeIndex = 0
    //保存当前课程已经得到的分数。
    private val workoutScores = mutableListOf<Int>()
    //只保存真正识别到人体并完成角度计算的分数，用于动作平均分和完成率。
    private val validWorkoutScores = mutableListOf<Int>()
    //在主线程定期检查视频播放位置。
    private val scoreHandler = Handler(Looper.getMainLooper())
    //防止重复启动评分检查任务。
    private var isScoreChecking = false
    //评分信息显示区域。
    private lateinit var scorePanel: View
    private lateinit var tvCurrentScore: TextView
    private lateinit var tvScoreFeedback: TextView
    private lateinit var tvJointCorrection: TextView

    //训练结束总分浮层。
    private lateinit var finalScoreOverlay: View
    private lateinit var tvFinalScoreTitle: TextView
    private lateinit var tvFinalScore: TextView
    private lateinit var tvFinalAverageScore: TextView
    private lateinit var tvFinalCompletionRate: TextView
    private lateinit var btnFinalBack: Button
    private lateinit var btnFinalAdjacent: Button

    //正式训练开始前的倒计时控件。
    private lateinit var preparationOverlay: View
    private lateinit var tvPreparationCountdown: TextView
    private lateinit var preparationCountdownProgress: ProgressBar
    private var preparationCountdownAnimator: ObjectAnimator? = null

    //当前课程是否已经正式开始。
    private var hasWorkoutStarted = false

    //训练完成页显示后，忽略迟到的手机断连事件。
    private var isWorkoutCompleted = false

    //倒计时是否正在运行。
    private var isPreparingWorkout = false

    //当前显示的倒计时数字。
    private var preparationCount = 5

    //倒计时结束后是否正在停留视频第一帧。
    private var isHoldingIntroFrame = false

    //自定义视频进度控件。
    private lateinit var playbackProgressPanel: View
    private lateinit var tvPlaybackTime: TextView
    private lateinit var playbackProgressBar: ProgressBar

    //当前评分节点从什么时候开始等待有效人体。
    private var scoreNodeWaitStartedAtMs: Long? = null

    //评分点最多等待500毫秒。
    private val scoreNodeWaitTimeoutMs = 500L

    //防止重复启动进度更新任务。
    private var isPlaybackProgressUpdating = false

    //WebSocket可能比电视屏幕刷新更快；这里只保存最新帧，避免旧姿态在UI线程中排队。
    private val latestPendingPoseFrame = AtomicReference<PoseFrameData?>(null)
    private val isPoseUiUpdateScheduled = AtomicBoolean(false)

    //临时性能诊断：统计网络接收、最新帧覆盖和UI实际消费速度。测试结束后删除。
    private val posePerfReceivedCount = AtomicLong(0L)
    private val posePerfConsumedCount = AtomicLong(0L)
    private val posePerfOverwrittenCount = AtomicLong(0L)
    private val latestPoseReceivedAtMs = AtomicLong(0L)
    private val posePerfQueueDelayTotalMs = AtomicLong(0L)
    private val posePerfQueueDelayMaxMs = AtomicLong(0L)
    private val posePerfWindowStartedAtMs = AtomicLong(SystemClock.elapsedRealtime())

    //每个屏幕刷新周期最多处理一帧姿态；处理期间到达的新帧会覆盖旧的等待帧。
    private val poseUiUpdateTask = object : Runnable {
        override fun run() {
            val frame = latestPendingPoseFrame.getAndSet(null)
            if (frame != null) { applyLatestPoseFrameOnUi(frame) }

            isPoseUiUpdateScheduled.set(false)
            if (latestPendingPoseFrame.get() != null && isPoseUiUpdateScheduled.compareAndSet(false, true)
            ) {
                skeletonView.postOnAnimation(this)
            }
        }
    }

    //单次评分显示结束后隐藏评分面板，淡出变透明的效果。
    private val hideScorePanelTask = Runnable {
        if (::scorePanel.isInitialized && scorePanel.visibility == View.VISIBLE) {
            scorePanel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(220L).withEndAction {
                    scorePanel.visibility = View.GONE
                    scorePanel.alpha = 1f
                    scorePanel.scaleX = 1f
                    scorePanel.scaleY = 1f
                    if (::skeletonView.isInitialized) { skeletonView.clearCorrections() }
                    hideJointCorrection()
                }.start()
        }
    }
    private val serverPort = 8765

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)//MainActivity使用activity_main.xml作为自己的界面
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            }
        )

        practicePanel = findViewById(R.id.practicePanel)
        leftContentContainer = findViewById(R.id.leftContentContainer)
        mainVideoPlayerView = findViewById(R.id.mainVideoPlayerView)
        mainVideoCoverView = findViewById(R.id.mainVideoCoverView)
        qrImageView = findViewById(R.id.qrImageView)
        pairingStatusText = findViewById(R.id.pairingStatusText)
        videoSelectionPanel = findViewById(R.id.videoSelectionPanel)
        playingPanel = findViewById(R.id.playingPanel)
        pairingOverlay = findViewById(R.id.pairingOverlay)
        readyCheckOverlay = findViewById(R.id.readyCheckOverlay)
        tvReadyCheckStatus = findViewById(R.id.tvReadyCheckStatus)
        disconnectedOverlay = findViewById(R.id.disconnectedOverlay)
        exitConfirmationOverlay = findViewById(R.id.exitConfirmationOverlay)
        skeletonContainer = findViewById(R.id.skeletonContainer)
        btnScanPhone = findViewById(R.id.btnScanPhone)
        btnQrBack = findViewById(R.id.btnQrBack)
        btnDisconnectKnown = findViewById(R.id.btnDisconnectKnown)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnConfirmExitWorkout = findViewById(R.id.btnConfirmExitWorkout)
        btnCancelExitWorkout = findViewById(R.id.btnCancelExitWorkout)
        practicePlayerView = findViewById(R.id.practicePlayerView)
        workoutPreviewPlayerView = findViewById(R.id.workoutPreviewPlayerView)
        workoutPreviewTitle = findViewById(R.id.workoutPreviewTitle)
        workoutPreviewIndex = findViewById(R.id.workoutPreviewIndex)
        btnStartWorkout = findViewById(R.id.btnStartWorkout)

        //倒计时
        preparationOverlay = findViewById(R.id.preparationOverlay)
        tvPreparationCountdown = findViewById(R.id.tvPreparationCountdown)
        preparationCountdownProgress = findViewById(R.id.preparationCountdownProgress)

        //单次评分
        scorePanel = findViewById(R.id.scorePanel)
        tvCurrentScore = findViewById(R.id.tvCurrentScore)
        tvScoreFeedback = findViewById(R.id.tvScoreFeedback)
        tvJointCorrection = findViewById(R.id.tvJointCorrection)

        //最终评分
        finalScoreOverlay = findViewById(R.id.finalScoreOverlay)
        tvFinalScoreTitle = findViewById(R.id.tvFinalScoreTitle)
        tvFinalScore = findViewById(R.id.tvFinalScore)
        tvFinalAverageScore = findViewById(R.id.tvFinalAverageScore)
        tvFinalCompletionRate = findViewById(R.id.tvFinalCompletionRate)
        btnFinalBack = findViewById(R.id.btnFinalBack)
        btnFinalAdjacent = findViewById(R.id.btnFinalAdjacent)

        //自定义进度条
        playbackProgressPanel = findViewById(R.id.playbackProgressPanel)
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime)
        playbackProgressBar = findViewById(R.id.playbackProgressBar)

        //获取activity_main.xml里的SkeletonView
        skeletonView = findViewById(R.id.skeletonView)//去已经加载好的activity_main.xml里，找到id为skeletonView的那个View

        //主视频与右侧训练视频使用两个独立播放器，任何一边结束都不影响另一边。
        initMainVideoPlayer()

        // 初始化正式训练播放器
        initPracticePlayer()

        // 初始化课程预览播放器
        initWorkoutPreviewPlayer()

        btnStartWorkout.setOnClickListener { startSelectedWorkout() }
        btnQrBack.setOnClickListener { closeQrAndContinueVideoOnly() }
        btnScanPhone.setOnClickListener { if (!isPhoneConnected) { showPairingOverlay() } }
        btnDisconnectKnown.setOnClickListener { acknowledgePhoneDisconnected() }
        btnReconnect.setOnClickListener { reconnectPhone() }
        btnConfirmExitWorkout.setOnClickListener { confirmExitWorkout() }
        btnCancelExitWorkout.setOnClickListener { cancelExitWorkout() }
        btnFinalBack.setOnClickListener { stopCurrentWorkoutKeepConnection() }
        btnFinalAdjacent.setOnClickListener { startAdjacentWorkoutFromCompletion() }
        mainVideoCoverView.setOnClickListener { showMainVideoFullscreen() }

        //Activity一启动，服务器就启动
        startPoseServer()

        //应用启动只显示主视频的小封面，并把遥控器焦点放到封面上。
        showMainVideoCard()

        //适配系统窗口区域
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.rootLayout)    //获取根布局
        ) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private data class WorkoutItem(
        val workoutId: String,
        val videoResId: Int,
        val title: String
    )
    private var currentWorkoutIndex = 0

    //课程列表，后续要加视频在这加即可
    private val workoutItems = listOf(
            WorkoutItem(workoutId = "workout_1", videoResId = R.raw.workout_1, title = "健身视频 1"),
            WorkoutItem(workoutId = "workout_2", videoResId = R.raw.workout_2, title = "健身视频 2")
        )

    private fun startSelectedWorkout() {
        if (workoutItems.isEmpty()) { return }
        val workout = workoutItems[currentWorkoutIndex]

        // 离开选择页
        workoutPreviewPlayer?.pause()
        selectWorkout(workoutId = workout.workoutId, workoutResId = workout.videoResId)
    }

    //读取课程评分配置并创建评分引擎。
    private fun initializeScoring(workoutId: String) {
        if (::finalScoreOverlay.isInitialized) {
            finalScoreOverlay.animate().cancel()
            finalScoreOverlay.visibility = View.GONE
        }
        if (::exitConfirmationOverlay.isInitialized) {
            exitConfirmationOverlay.visibility = View.GONE
        }

        try {
            //防止上一节课程的节点下标和平均分进入下一节课程
            stopScoreChecking()
            nextScoreNodeIndex = 0
            scoreNodeWaitStartedAtMs = null
            workoutScores.clear()
            validWorkoutScores.clear()

            //课程ID同时作为assets中的评分素材目录名。
            scoreConfig = PoseConfigLoader.load(context = this, assetDirectory = workoutId)
            scoringEngine = PoseScoringEngine(config = requireNotNull(scoreConfig))

            //每门课程的第一个配置节点位于视频开头，只用于动作起始对齐，不弹出评分。
            nextScoreNodeIndex = if (scoreConfig?.scoreNodes.isNullOrEmpty()) 0 else 1
            //新课程开始时取消旧弹窗和动画。
            hideScorePanel()
        } catch (error: Exception) {
            clearScoring()
        }
    }

    //运动开始前倒计时5—>1，每秒更新一次准备倒计时。
    private val preparationTask = object : Runnable {
        override fun run() {
            if (!isPreparingWorkout) { return }
            if (preparationCount <= 0) {
                finishPreparationCountdown()
                return
            }
            tvPreparationCountdown.text = preparationCount.toString()
            val progressStart = preparationCount * 1000
            val progressEnd = (preparationCount - 1) * 1000
            preparationCountdownProgress.progress = progressStart
            preparationCountdownAnimator?.cancel()
            //执行动画
            preparationCountdownAnimator = ObjectAnimator.ofInt(
                preparationCountdownProgress,
                "progress",
                progressStart,
                progressEnd
            ).apply {
                duration = 1000L
                interpolator = LinearInterpolator()
                start()
            }
            tvPreparationCountdown.animate().cancel()
            tvPreparationCountdown.alpha = 0f
            tvPreparationCountdown.scaleX = 0.65f
            tvPreparationCountdown.scaleY = 0.65f
            tvPreparationCountdown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250L).start()
            preparationCount--
            scoreHandler.postDelayed(this, 1000L)  //this指当前对象，再进行循环
        }
    }

    //每250毫秒刷新一次自定义视频进度。
    private val playbackProgressTask = object : Runnable {
        override fun run() {
            if (!isPlaybackProgressUpdating) { return }
            updatePlaybackProgress()
            scoreHandler.postDelayed(this, 250L)  //延迟执行
        }
    }

    //将播放器位置更新到自定义进度条。
    private fun updatePlaybackProgress() {
        val player = practicePlayer ?: return
        val duration = player.duration    //获取媒体总时长

        if (duration <= 0L) {
            playbackProgressBar.progress = 0
            tvPlaybackTime.text = "00:00 / 00:00"
            return
        }
        val position = player.currentPosition.coerceIn(0L, duration)
        playbackProgressBar.progress = ((position * 1000L) / duration).toInt()
        tvPlaybackTime.text = "${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}"
    }

    //视频第一帧停留结束后，才正式启动训练流程。
    private val introFrameTask = Runnable {
        if (isHoldingIntroFrame && businessState == BusinessState.PLAYING) {
            startWorkoutAfterIntroFrame()
        }
    }

    //开始刷新视频进度。
    private fun startPlaybackProgressUpdating() {
        if (isPlaybackProgressUpdating) { return }
        isPlaybackProgressUpdating = true
        playbackProgressPanel.visibility = View.VISIBLE
        scoreHandler.post(playbackProgressTask)
    }

    //停止刷新视频进度。
    private fun stopPlaybackProgressUpdating() {
        isPlaybackProgressUpdating = false
        // 移除尚未执行的任务
        scoreHandler.removeCallbacks(playbackProgressTask)
    }

    //将毫秒转换成分:秒。
    private fun formatPlaybackTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    //倒计时结束后先显示视频第一帧一秒钟。
    private fun finishPreparationCountdown() {
        isPreparingWorkout = false
        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected) {
            skeletonView.finishBodySizeCalibration()
        }
        scoreHandler.removeCallbacks(preparationTask)
        preparationCountdownAnimator?.cancel()
        preparationCountdownAnimator = null
        preparationCountdownProgress.progress = 0
        preparationOverlay.visibility = View.GONE
        practicePlayerView.visibility = View.VISIBLE
        practicePlayer?.seekTo(0)
        practicePlayer?.pause()
        hasWorkoutStarted = false
        isHoldingIntroFrame = true
        scoreHandler.postDelayed(introFrameTask, 1000L)
    }

    //第一帧展示结束后开始播放、显示火柴人并启动评分。
    private fun startWorkoutAfterIntroFrame() {
        isHoldingIntroFrame = false
        scoreHandler.removeCallbacks(introFrameTask)
        hasWorkoutStarted = true

        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected
        ) {
            btnScanPhone.visibility = View.GONE
            skeletonContainer.visibility = View.VISIBLE
            startScoreChecking()
        } else {
            skeletonContainer.visibility = View.GONE
            stopScoreChecking()
            refreshScanButton()
        }
        practicePlayer?.play()
        startPlaybackProgressUpdating()
    }

    //退出课程或页面时取消准备倒计时。
    private fun stopPreparationCountdown() {
        isPreparingWorkout = false
        isHoldingIntroFrame = false
        scoreHandler.removeCallbacks(preparationTask)
        scoreHandler.removeCallbacks(introFrameTask)
        preparationCountdownAnimator?.cancel()
        preparationCountdownAnimator = null
        if (::preparationOverlay.isInitialized) { preparationOverlay.visibility = View.GONE }
    }

    //从5开始显示正式训练准备倒计时。
    private fun startPreparationCountdown() {
        if (isPreparingWorkout) { return }
        isWorkoutCompleted = false
        isHoldingIntroFrame = false
        scoreHandler.removeCallbacks(introFrameTask)
        practicePlayer?.pause()
        practicePlayer?.seekTo(0)
        //倒计时使用独立准备页面，期间不显示背后的视频画面。
        practicePlayerView.visibility = View.INVISIBLE
        stopPlaybackProgressUpdating()
        playbackProgressPanel.visibility = View.GONE
        stopScoreChecking()
        hideScorePanel()
        skeletonView.clearPose()
        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected) {
            //INVISIBLE仍参与布局测量，倒计时期间才能按真实显示区域计算50%基准。
            skeletonView.startBodySizeCalibration()
            skeletonContainer.visibility = View.INVISIBLE
        } else {
            skeletonContainer.visibility = View.GONE
        }
        btnScanPhone.visibility = View.GONE
        preparationCount = 5
        preparationCountdownProgress.progress = 5000
        isPreparingWorkout = true
        preparationOverlay.visibility = View.VISIBLE
        scoreHandler.post(preparationTask)
    }

    //每50毫秒检查一次视频是否到达评分节点。
    private val scoreCheckTask = object : Runnable {
        override fun run() {
            if (!isScoreChecking) { return }
            checkScoreProgress()
            scoreHandler.postDelayed(this, 50L)
        }
    }

    //开始检查视频评分节点。
    private fun startScoreChecking() {
        if (isScoreChecking) { return }
        isScoreChecking = true
        scoreHandler.post(scoreCheckTask)
    }

    //停止检查视频评分节点。
    private fun stopScoreChecking() {
        isScoreChecking = false
        scoreHandler.removeCallbacks(scoreCheckTask)
        scoreNodeWaitStartedAtMs = null
    }

    //中途连接手机时跳过视频已经播放过去的评分节点。
    private fun alignNextScoreNodeToCurrentPosition() {
        val player = practicePlayer ?: return
        val config = scoreConfig ?: return
        val currentVideoFrame = (player.currentPosition * 24L / 1000L).toInt() + 1

        while (nextScoreNodeIndex in config.scoreNodes.indices) {
            val triggerFrame = config.scoreNodes[nextScoreNodeIndex].frameId + 9
            if (triggerFrame >= currentVideoFrame) { break }
            nextScoreNodeIndex++
        }
    }

    //根据视频播放帧检查并触发评分。
    private fun checkScoreProgress() {
        val player = practicePlayer ?: return
        val config = scoreConfig ?: return
        val engine = scoringEngine ?: return

        if (!player.isPlaying) { return }
        if (businessState != BusinessState.PLAYING || playbackMode != PlaybackMode.VIDEO_WITH_POSE || !isPhoneConnected) { return }
        if (nextScoreNodeIndex !in config.scoreNodes.indices) { return }

        //标准视频按照24 FPS换算帧号，config帧号从1开始。
        val currentVideoFrame = (player.currentPosition * 24L / 1000L).toInt() + 1
        val scoreNode = config.scoreNodes[nextScoreNodeIndex]

        //评分引擎需要8帧延迟，并使用小于当前帧的标准帧。
        val triggerFrame = scoreNode.frameId + 9

        if (currentVideoFrame < triggerFrame) { return }

        //等待有效人体期间固定使用当前节点的标准帧窗口，避免窗口随视频继续播放而漂移。
        val result = engine.score(currentVideoFrame = triggerFrame, difficulty = scoreNode.difficulty)
        if (result != null) {
            workoutScores.add(result.score)
            validWorkoutScores.add(result.score)
            nextScoreNodeIndex++
            scoreNodeWaitStartedAtMs = null
            showScoreResult(result)
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val waitStartedAt = scoreNodeWaitStartedAtMs

        if (waitStartedAt == null) {
            scoreNodeWaitStartedAtMs = nowMs
            return
        }
        if (nowMs - waitStartedAt < scoreNodeWaitTimeoutMs) { return }

        //等待500毫秒仍没有有效人体，本次记0分并进入下一节点。
        workoutScores.add(0)
        nextScoreNodeIndex++
        scoreNodeWaitStartedAtMs = null
        showMissingPoseResult()
    }

    //立即隐藏评分面板并取消等待中的隐藏任务。
    private fun hideScorePanel() {
        scoreHandler.removeCallbacks(hideScorePanelTask)
        if (::skeletonView.isInitialized) { skeletonView.clearCorrections() }
        hideJointCorrection()
        if (::scorePanel.isInitialized) {
            scorePanel.animate().cancel()
            scorePanel.visibility = View.GONE
            scorePanel.alpha = 1f
            scorePanel.scaleX = 1f
            scorePanel.scaleY = 1f
        }
    }

    //在评分帧后动态显示评价和分数。
    private fun showScoreResult(result: PoseScoreResult) {
        val corrections = JointCorrectionMapper.from(result)
        showScorePresentation(
            score = result.score,
            correctionMessage = JointCorrectionMapper.coverageMessage(result)
                ?: JointCorrectionMapper.primaryMessage(corrections, result.score),
            corrections = corrections
        )
    }

    private fun showMissingPoseResult() {
        showScorePresentation(
            score = 0,
            correctionMessage = null,
            corrections = emptyList()
        )
    }

    private fun showScorePresentation(
        score: Int,
        correctionMessage: String?,
        corrections: List<JointCorrection>
    ) {
        scoreHandler.removeCallbacks(hideScorePanelTask)
        scorePanel.animate().cancel()
        tvCurrentScore.text = "+$score"
        //顶部评分面板保持原来的总分评价方式。
        tvScoreFeedback.text = when {
            score == 0 -> "未识别到动作"
            score >= 90 -> "真棒!"
            score >= 75 -> "很好~"
            score >= 60 -> "继续加油"
            else -> "注意动作"
        }
        if (corrections.isEmpty()) {
            skeletonView.clearCorrections()
            hideJointCorrection()
        } else {
            skeletonView.updateCorrections(corrections)
            showJointCorrection(correctionMessage)
        }

        scorePanel.alpha = 0f
        scorePanel.scaleX = 0.65f
        scorePanel.scaleY = 0.65f
        scorePanel.visibility = View.VISIBLE

        scorePanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).start()

        //浮字停留1.5秒后淡出。
        scoreHandler.postDelayed(hideScorePanelTask, 1500L)
    }

    //纠错文字在现有火柴人面板顶部以紧凑卡片显示，不改变面板尺寸。
    private fun showJointCorrection(message: String?) {
        if (!::tvJointCorrection.isInitialized || message.isNullOrBlank()) {
            hideJointCorrection()
            return
        }
        tvJointCorrection.animate().cancel()
        tvJointCorrection.text = message
        tvJointCorrection.alpha = 0f
        tvJointCorrection.translationY = -6f * resources.displayMetrics.density
        tvJointCorrection.visibility = View.VISIBLE
        tvJointCorrection.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun hideJointCorrection() {
        if (!::tvJointCorrection.isInitialized) { return }
        tvJointCorrection.animate().cancel()
        tvJointCorrection.visibility = View.GONE
        tvJointCorrection.alpha = 1f
        tvJointCorrection.translationY = 0f
    }

    //清空当前训练的评分数据。
    private fun clearScoring() {
        stopPreparationCountdown()
        stopPlaybackProgressUpdating()
        hasWorkoutStarted = false
        isWorkoutCompleted = false
        if (::finalScoreOverlay.isInitialized) {
            finalScoreOverlay.animate().cancel()
            finalScoreOverlay.visibility = View.GONE
        }

        //退出训练时停止评分检查
        stopScoreChecking()
        //关闭正在显示或等待隐藏的评分弹窗
        hideScorePanel()
        //清除最近5帧用户姿态
        scoringEngine?.reset()
        //清除当前课程评分状态
        scoringEngine = null
        scoreConfig = null
        nextScoreNodeIndex = 0
        scoreNodeWaitStartedAtMs = null
        workoutScores.clear()
        validWorkoutScores.clear()

        if (::playbackProgressPanel.isInitialized) {
            playbackProgressPanel.visibility = View.GONE
            playbackProgressBar.progress = 0
            tvPlaybackTime.text = "00:00 / 00:00"
        }
    }

    //初始化预览播放器
    private fun initWorkoutPreviewPlayer() {
        if (workoutPreviewPlayer != null) { return }
        workoutPreviewPlayer = ExoPlayer.Builder(this).build()
        workoutPreviewPlayerView.player = workoutPreviewPlayer

        //预览视频不要有声音
        workoutPreviewPlayer?.volume = 0f

        //预览循环播放。
        workoutPreviewPlayer?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE

        //不显示播放控制条。
        workoutPreviewPlayerView.useController = false
    }

    private fun initMainVideoPlayer() {
        if (mainVideoPlayer != null) return
        mainVideoPlayer = ExoPlayer.Builder(this).build().also { player ->
            mainVideoPlayerView.player = player
            mainVideoPlayerView.useController = false
            //整个应用始终只播放主视频声音。
            player.volume = 1f
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItem(
                MediaItem.fromUri("android.resource://$packageName/${R.raw.main_video}")
            )
            player.prepare()
            //准备首帧作为封面，不自动播放。
            player.playWhenReady = false
        }
    }

    private fun showMainVideoCard() {
        mainVideoDisplayMode = MainVideoDisplayMode.CARD
        businessState = BusinessState.HOME
        practicePanel.visibility = View.GONE
        setLeftContainerSplitMode(enabled = false)
        mainVideoPlayer?.pause()
        mainVideoPlayerView.visibility = View.GONE
        mainVideoCoverView.visibility = View.VISIBLE

        val params = mainVideoCoverView.layoutParams as FrameLayout.LayoutParams
        params.width = dpToPx(420)
        params.height = dpToPx(236)
        params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        params.marginStart = dpToPx(72)
        params.topMargin = 0
        params.marginEnd = 0
        params.bottomMargin = 0
        mainVideoCoverView.layoutParams = params
        mainVideoCoverView.requestFocus()
    }

    private fun showMainVideoFullscreen() {
        mainVideoDisplayMode = MainVideoDisplayMode.FULLSCREEN
        businessState = BusinessState.HOME
        practicePanel.visibility = View.GONE
        setLeftContainerSplitMode(enabled = false)
        mainVideoCoverView.visibility = View.GONE
        mainVideoPlayerView.visibility = View.VISIBLE

        val params = mainVideoPlayerView.layoutParams as FrameLayout.LayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        params.setMargins(0, 0, 0, 0)
        mainVideoPlayerView.layoutParams = params
        mainVideoPlayerView.clearFocus()

        //播放结束后再次从封面进入时从头播放；普通分屏返回则保留原进度。
        if (mainVideoPlayer?.playbackState == Player.STATE_ENDED) {
            mainVideoPlayer?.seekTo(0)
        }
        mainVideoPlayer?.play()
    }

    private fun showSplitPractice() {
        if (mainVideoDisplayMode != MainVideoDisplayMode.FULLSCREEN) return
        mainVideoDisplayMode = MainVideoDisplayMode.SPLIT
        setLeftContainerSplitMode(enabled = true)
        mainVideoCoverView.visibility = View.GONE
        mainVideoPlayerView.visibility = View.VISIBLE

        val params = mainVideoPlayerView.layoutParams as FrameLayout.LayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.CENTER
        params.setMargins(0, 0, 0, 0)
        mainVideoPlayerView.layoutParams = params

        //现有课程选择、扫码、动作检测、倒计时、评分流程全部保留在右侧40%。
        showVideoSelectionState()
    }

    /** 全屏/封面时左侧真正占满屏幕；分屏时恢复主视频60%、训练区40%。 */
    private fun setLeftContainerSplitMode(enabled: Boolean) {
        val params = leftContentContainer.layoutParams as LinearLayout.LayoutParams
        if (enabled) {
            params.width = 0
            params.weight = 3f
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.weight = 0f
        }
        leftContentContainer.layoutParams = params
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    //视频播放器初始化
    private fun initPracticePlayer() {
        // 已经创建过就不要重复创建
        if (practicePlayer != null) { return }
        practicePlayer = ExoPlayer.Builder(this).build()

        // 把播放器核心和XML里的PlayerView绑定
        practicePlayerView.player = practicePlayer
        //边看边练课程只显示画面，避免与主视频声音重叠。
        practicePlayer?.volume = 0f
        //关闭默认的暂停、快进、快退和默认进度条。
        practicePlayerView.useController = false

        //监听视频播放解释
        practicePlayer?.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) { showFinalScore() }
                }
            }
        )
    }

    //训练结束后在屏幕中央显示总分。
    private fun showFinalScore() {
        //先进入完成状态，阻止视频结束时同时到达的手机断连回调覆盖完成页。
        isWorkoutCompleted = true
        stopPreparationCountdown()
        stopScoreChecking()
        stopPlaybackProgressUpdating()
        hideScorePanel()
        practicePlayer?.pause()
        practicePlayerView.visibility = View.INVISIBLE
        playbackProgressPanel.visibility = View.GONE
        skeletonView.clearPose()
        skeletonContainer.visibility = View.GONE
        btnScanPhone.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        exitConfirmationOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE

        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected) { poseWebSocketServer?.sendPracticeStop() }

        val ignoredLeadingNodeCount = if (scoreConfig?.scoreNodes.isNullOrEmpty()) 0 else 1
        val expectedScoreNodeCount = ((scoreConfig?.scoreNodes?.size ?: 0) - ignoredLeadingNodeCount).coerceAtLeast(0)
        val validScoreNodeCount = validWorkoutScores.size.coerceAtMost(expectedScoreNodeCount)
        val averageScore = if (validWorkoutScores.isEmpty()) 0 else validWorkoutScores.average().toInt()
        val completionRate = if (expectedScoreNodeCount == 0) {
            0
        } else {
            (validScoreNodeCount * 100f / expectedScoreNodeCount).toInt().coerceIn(0, 100)
        }
        val finalScore = (averageScore * completionRate / 100f).toInt().coerceIn(0, 100)

        tvFinalScoreTitle.text = "运动结束"
        if (playbackMode == PlaybackMode.VIDEO_ONLY) {
            // 纯视频模式没有动作评分数据。
            tvFinalScore.text = "—"
            tvFinalAverageScore.text = "—"
            tvFinalCompletionRate.text = "—"
        } else {
            tvFinalScore.text = finalScore.toString()
            tvFinalAverageScore.text = averageScore.toString()
            tvFinalCompletionRate.text = "$completionRate%"
        }

        //最后一节课程只能返回上一节；其余课程统一进入下一节。
        btnFinalAdjacent.text = if (currentWorkoutIndex == workoutItems.lastIndex) "上一个" else "下一个"
        btnFinalAdjacent.isEnabled = workoutItems.size > 1

        finalScoreOverlay.alpha = 0f
        finalScoreOverlay.scaleX = 0.85f
        finalScoreOverlay.scaleY = 0.85f
        finalScoreOverlay.visibility = View.VISIBLE

        finalScoreOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300L).start()
        btnFinalAdjacent.requestFocus()
    }

    //从完成页进入相邻课程：除最后一节进入下一节，最后一节返回上一节。
    private fun startAdjacentWorkoutFromCompletion() {
        if (!isWorkoutCompleted || workoutItems.size <= 1) { return }

        val offset = if (currentWorkoutIndex == workoutItems.lastIndex) -1 else 1
        val targetIndex = currentWorkoutIndex + offset
        if (targetIndex !in workoutItems.indices) { return }

        //复用播放中的课程切换流程；先解除完成标记，保留当前纯视频/火柴人模式。
        isWorkoutCompleted = false
        switchWorkoutDuringPlayback(offset)
    }

    //更新视频预览
    private fun updateWorkoutPreview() {
        if (workoutItems.isEmpty()) { return }
        val workout = workoutItems[currentWorkoutIndex]
        // 更新标题
        workoutPreviewTitle.text = workout.title
        // 更新序号如：1/2，2/2
        workoutPreviewIndex.text = "${currentWorkoutIndex + 1} / ${workoutItems.size}"

        // 构造raw视频URI
        val videoUri = "android.resource://" + packageName + "/" + workout.videoResId
        val mediaItem = MediaItem.fromUri(videoUri)
        val player = workoutPreviewPlayer ?: return

        // 切换预览视频
        player.setMediaItem(mediaItem)
        player.prepare()
        player.seekTo(0)

        // 自动静音循环播放
        player.play()
    }

    //显示上一个健身视频
    private fun showPreviousWorkout() {
        if (workoutItems.isEmpty()) { return }
        currentWorkoutIndex--
        if (currentWorkoutIndex < 0) {
            currentWorkoutIndex = workoutItems.lastIndex    //当前List中最后一个元素的下标
        }
        updateWorkoutPreview()
    }

    //显示下一个健身视频
    private fun showNextWorkout() {
        if (workoutItems.isEmpty()) { return }
        currentWorkoutIndex++
        if (currentWorkoutIndex > workoutItems.lastIndex) {
            currentWorkoutIndex = 0
        }
        updateWorkoutPreview()
    }

    //播放过程中切换到相邻课程；到达列表边界后不循环切换。
    private fun switchWorkoutDuringPlayback(offset: Int) {
        if (businessState != BusinessState.PLAYING
            || overlayState != OverlayState.NONE
            || isWorkoutCompleted
            || workoutItems.isEmpty()
        ) { return }

        val targetIndex = currentWorkoutIndex + offset
        if (targetIndex !in workoutItems.indices) { return }

        //记住切换前是否正在使用火柴人。切换课程时保留现有手机连接和动作检测结果。
        val keepPoseMode = playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected

        practicePlayer?.pause()
        stopPreparationCountdown()
        stopPlaybackProgressUpdating()
        stopScoreChecking()
        hideScorePanel()

        //只停止旧课程的Pose上传，不断开WebSocket，也不让手机退出动作检测。
        if (keepPoseMode) {
            poseWebSocketServer?.sendPracticeStop()
        }

        skeletonView.clearPose()
        skeletonContainer.visibility = View.GONE

        currentWorkoutIndex = targetIndex
        val workout = workoutItems[currentWorkoutIndex]
        selectedWorkoutId = workout.workoutId
        hasWorkoutStarted = false
        isWorkoutCompleted = false

        initializeScoring(workout.workoutId)
        prepareWorkoutVideo(workout.videoResId)

        if (keepPoseMode) {
            //向已连接手机切换workoutId，然后直接从倒计时开始，不再扫码或动作检测。
            startVideoWithPose()
        } else {
            //纯视频切换后仍保持纯视频模式，不重新弹出二维码。
            playbackMode = PlaybackMode.VIDEO_ONLY
            overlayState = OverlayState.NONE
            refreshScanButton()
            startPreparationCountdown()
        }
    }

    //视频选择页面
    private fun showVideoSelectionState() {
        businessState = BusinessState.VIDEO_SELECTION
        playbackMode = PlaybackMode.NONE
        overlayState = OverlayState.NONE
        practicePanel.visibility = View.VISIBLE
        workoutPreviewPlayerView.player = workoutPreviewPlayer
        workoutPreviewPlayerView.visibility = View.VISIBLE
        videoSelectionPanel.visibility = View.VISIBLE
        playingPanel.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        skeletonContainer.visibility = View.GONE
        btnScanPhone.visibility = View.GONE
        skeletonView.clearPose()
        btnStartWorkout.requestFocus()
        // 显示当前课程预览
        updateWorkoutPreview()
        //焦点给立即开始
        workoutPreviewPlayer?.play()
    }

    private fun selectWorkout(
        workoutId: String,
        workoutResId: Int
    ) {
        hasWorkoutStarted = false
        stopPreparationCountdown()
        selectedWorkoutId = workoutId
        //初始化评分
        initializeScoring(workoutId)
        // 进入播放页面
        businessState = BusinessState.PLAYING
        practicePanel.visibility = View.VISIBLE
        practicePlayerView.player = practicePlayer
        practicePlayerView.visibility = View.VISIBLE
        videoSelectionPanel.visibility = View.GONE
        playingPanel.visibility = View.VISIBLE
        // 先加载视频，但不要播放
        prepareWorkoutVideo(workoutResId)
        //如果手机已经连接，用户换一个视频时无需重新扫码。
        if (isPhoneConnected) { startVideoWithPose()
        } else {
            //第一次选择视频：视频作为背景已经加载，但暂停，然后弹二维码。
            playbackMode = PlaybackMode.VIDEO_ONLY
            showPairingOverlay()
        }
    }

    //展示二维码
    private fun showPairingQrCode() {
        //自动获取OTT当前IPv4
        val host = LocalIpProvider.getLocalIpv4Address(this)

        //如果没有获取到IP
        if (host == null) {
            qrImageView.setImageDrawable(null)
            pairingStatusText.text = "未检测到局域网IPv4\n请检查OTT网络连接"
            return
        }

        //自动构造二维码内容
        val qrContent = Uri.Builder()
            .scheme("poseott")
            .authority("pair")
            .appendQueryParameter("host", host)
            .appendQueryParameter("port", serverPort.toString())
            .appendQueryParameter("version", "1")
            .build()
            .toString()

        //String→二维码Bitmap
        val qrBitmap = QrCodeGenerator.generate(qrContent)

        // 显示到ImageView
        qrImageView.setImageBitmap(qrBitmap)

        //调试阶段同时显示IP
        pairingStatusText.text = "等待手机连接...\n"
    }

    //监听遥控器
    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                // HOME按↑： 进入边看边练主界面= VIDEO_SELECTION
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (businessState == BusinessState.HOME &&
                        mainVideoDisplayMode == MainVideoDisplayMode.FULLSCREEN
                    ) {
                        showSplitPractice()
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    when (businessState) {
                        BusinessState.VIDEO_SELECTION -> {
                            showPreviousWorkout()
                            return true
                        }
                        BusinessState.PLAYING -> {
                            //只有正常播放页面才拦截左右键；弹窗显示时交给系统切换按钮焦点。
                            if (overlayState == OverlayState.NONE && !isWorkoutCompleted) {
                                switchWorkoutDuringPlayback(offset = -1)
                                //边界位置也消费按键，避免播放器按钮焦点被移动。
                                return true
                            }
                        }
                        else -> Unit
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (businessState) {
                        BusinessState.VIDEO_SELECTION -> {
                            showNextWorkout()
                            return true
                        }
                        BusinessState.PLAYING -> {
                            //只有正常播放页面才拦截左右键；弹窗显示时交给系统切换按钮焦点。
                            if (overlayState == OverlayState.NONE && !isWorkoutCompleted) {
                                switchWorkoutDuringPlayback(offset = 1)
                                //边界位置也消费按键，避免播放器按钮焦点被移动。
                                return true
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackPressed() {
        if (mainVideoDisplayMode == MainVideoDisplayMode.FULLSCREEN) {
            showMainVideoCard()
            return
        }

        if (mainVideoDisplayMode == MainVideoDisplayMode.CARD) {
            finish()
            return
        }

        //退出确认弹窗显示时，返回键等同于取消退出。
        if (overlayState == OverlayState.EXIT_CONFIRMATION) {
            cancelExitWorkout()
            return
        }
        //当前有二维码浮层，返回键等同于二维码里的“返回”。
        if (overlayState == OverlayState.PAIRING_QR) {
            closeQrAndContinueVideoOnly()
            return
        }
        if (overlayState == OverlayState.READY_CHECK) {
            cancelReadyCheckAndContinueVideoOnly()
            return
        }
        //手机断线弹窗中，返回键等同于“知道了”。
        if (overlayState == OverlayState.PHONE_DISCONNECTED) {
            acknowledgePhoneDisconnected()
            return
        }
        if (businessState == BusinessState.PLAYING) {
            //训练已完成时直接返回课程选择页：只停止本课程的Pose上传，保留手机WebSocket连接，
            //下一门课程可直接复用连接，不再扫码和重复准备动作检测。
            if (isWorkoutCompleted) {
                stopCurrentWorkoutKeepConnection()
                return
            }
            //视频+动作检测状态下，第一次返回只退出检测，电视继续纯视频。
            if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected) {
                exitPoseDetectionToVideoOnly()
                return
            }
            if (hasWorkoutStarted && !isWorkoutCompleted) {
                showExitConfirmation()
            } else {
                stopCurrentWorkoutKeepConnection()
            }
            return
        }
        //只有视频选择页按返回才退出边看边练，并恢复主视频全屏。
        if (businessState == BusinessState.VIDEO_SELECTION) {
            exitPracticeBusiness()
            showMainVideoFullscreen()
            return
        }

        //主页没有更多内部页面可返回时，退出当前 Activity。
        finish()
    }

    //播放中按返回键时暂停训练并显示退出确认。
    private fun showExitConfirmation() {
        if (overlayState != OverlayState.NONE || isWorkoutCompleted) { return }
        practicePlayer?.pause()
        stopPlaybackProgressUpdating()
        stopScoreChecking()
        hideScorePanel()
        overlayState = OverlayState.EXIT_CONFIRMATION
        exitConfirmationOverlay.visibility = View.VISIBLE
        btnCancelExitWorkout.requestFocus()
    }

    //确认退出当前训练并返回课程选择。
    private fun confirmExitWorkout() {
        exitConfirmationOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE
        stopCurrentWorkoutKeepConnection()
    }

    //取消退出后从原播放位置继续训练。
    private fun cancelExitWorkout() {
        exitConfirmationOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE
        if (businessState != BusinessState.PLAYING || isWorkoutCompleted) { return }
        practicePlayer?.play()
        startPlaybackProgressUpdating()
        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected) {
            startScoreChecking()
        }
    }

    //视频播放
    private fun prepareWorkoutVideo(
        rawResId: Int
    ) {
        val player = practicePlayer ?: return
        val videoUri = "android.resource://" + packageName + "/" + rawResId
        val mediaItem = MediaItem.fromUri(videoUri)

        player.setMediaItem(mediaItem)
        player.prepare()

        // 只准备，不自动播放
        player.playWhenReady = false
        player.seekTo(0)
    }

    //恢复正式视频并继续刷新自定义进度。
    private fun resumeWorkoutVideo() {
        practicePlayerView.visibility = View.VISIBLE
        practicePlayer?.play()
        if (hasWorkoutStarted) { startPlaybackProgressUpdating() }
    }

    private fun showPairingOverlay() {
        if (businessState != BusinessState.PLAYING) { return }

        //只暂停视频，不隐藏PlayerView，避免SurfaceView消失时露出中间过渡画面。
        practicePlayer?.pause()
        stopPlaybackProgressUpdating()
        stopScoreChecking()
        hideScorePanel()

        overlayState = OverlayState.PAIRING_QR

        //先关闭其他覆盖页和训练控件，保证扫码页是当前唯一页面。
        disconnectedOverlay.visibility = View.GONE
        exitConfirmationOverlay.visibility = View.GONE
        preparationOverlay.visibility = View.GONE
        finalScoreOverlay.visibility = View.GONE
        btnScanPhone.visibility = View.GONE
        skeletonContainer.visibility = View.GONE
        skeletonView.clearPose()

        //最后显示扫码页并移动到最上层，避免切换过程中露出播放器背景。
        pairingOverlay.visibility = View.VISIBLE
        pairingOverlay.bringToFront()
        showPairingQrCode()
        btnQrBack.requestFocus()
    }

    //手机配对成功后显示准备动作检测页面。
    private fun showReadyCheckPage() {
        if (businessState != BusinessState.PLAYING || !isPhoneConnected) { return }
        practicePlayer?.pause()
        stopPlaybackProgressUpdating()
        stopScoreChecking()
        hideScorePanel()

        isWaitingReadyCheck = true
        overlayState = OverlayState.READY_CHECK

        pairingOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        exitConfirmationOverlay.visibility = View.GONE
        preparationOverlay.visibility = View.GONE
        finalScoreOverlay.visibility = View.GONE

        btnScanPhone.visibility = View.GONE
        skeletonContainer.visibility = View.GONE
        skeletonView.clearPose()

        tvReadyCheckStatus.text = "请面对手机，举起右手"
        readyCheckOverlay.visibility = View.VISIBLE
        readyCheckOverlay.bringToFront()
    }

    //收到手机准备完成消息后进入原有训练启动流程。
    private fun finishReadyCheck() {
        if (businessState != BusinessState.PLAYING) { return }
        if (!isPhoneConnected) { return }
        if (!isWaitingReadyCheck || overlayState != OverlayState.READY_CHECK) { return }

        isWaitingReadyCheck = false
        tvReadyCheckStatus.text = "动作检测成功"

        scoreHandler.postDelayed({
            if (businessState != BusinessState.PLAYING || !isPhoneConnected) { return@postDelayed }
            readyCheckOverlay.visibility = View.GONE
            overlayState = OverlayState.NONE

            //沿用原有逻辑：发送practice_start并进入5秒倒计时。
            startVideoWithPose()
        }, 800L)
    }

    //取消动作检测，断开手机并继续纯视频模式。
    private fun cancelReadyCheckAndContinueVideoOnly() {
        isWaitingReadyCheck = false
        readyCheckOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE

        //电视主动退出检测：先让手机回到扫码页，再关闭本次连接。
        poseWebSocketServer?.sendExitDetection()
        poseWebSocketServer?.disconnectActiveClient("OTT exited ready check")
        isPhoneConnected = false
        skeletonView.resetBodySizeCalibration()
        playbackMode = PlaybackMode.VIDEO_ONLY
        skeletonContainer.visibility = View.GONE
        skeletonView.clearPose()
        stopScoreChecking()
        hideScorePanel()
        refreshScanButton()
        if (hasWorkoutStarted) { resumeWorkoutVideo() } else { startPreparationCountdown() }
    }

    //正式训练中由电视退出动作检测，保留当前视频进度并切换为纯视频。
    private fun exitPoseDetectionToVideoOnly() {
        poseWebSocketServer?.sendExitDetection()
        poseWebSocketServer?.disconnectActiveClient("OTT exited pose detection")
        isPhoneConnected = false
        skeletonView.resetBodySizeCalibration()
        isWaitingReadyCheck = false
        playbackMode = PlaybackMode.VIDEO_ONLY
        overlayState = OverlayState.NONE
        readyCheckOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        skeletonView.clearPose()
        skeletonContainer.visibility = View.GONE
        stopScoreChecking()
        hideScorePanel()
        refreshScanButton()
        resumeWorkoutVideo()
    }

    //二维码底部“返回”
    private fun closeQrAndContinueVideoOnly() {
        //用户明确退出二维码流程。如果此时有一个还没完成HELLO的连接，一并取消。
        if (!isPhoneConnected) { poseWebSocketServer?.disconnectActiveClient("User cancelled QR pairing") }
        pairingOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE
        playbackMode = PlaybackMode.VIDEO_ONLY
        //纯视频模式不显示评分。
        stopScoreChecking()
        hideScorePanel()
        skeletonContainer.visibility = View.GONE
        skeletonView.clearPose()
        refreshScanButton()
        // 遥控器焦点给右上角扫码按钮
        if (btnScanPhone.visibility == View.VISIBLE) { btnScanPhone.requestFocus() }
        if (hasWorkoutStarted) {
            //训练中重新关闭扫码浮层，继续原进度。
            resumeWorkoutVideo()
        } else {
            //第一次进入课程，先进行5秒准备。
            startPreparationCountdown()
        }
    }

    //右上角扫码按钮统一由一个函数控制
    private fun refreshScanButton() {
        val shouldShow = businessState == BusinessState.PLAYING
                && playbackMode == PlaybackMode.VIDEO_ONLY
                && overlayState == OverlayState.NONE
                && !isPhoneConnected
        btnScanPhone.visibility = if (shouldShow) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    //扫码成功以后进入视频+火柴人
    private fun startVideoWithPose() {
        val workoutId = selectedWorkoutId
        if (workoutId == null) {
            Log.e("PracticeControl", "Cannot start pose mode: " + "no workout selected")
            return
        }
        if (!isPhoneConnected) {
            Log.w("PracticeControl", "Cannot start pose mode: " + "phone disconnected")
            return
        }
        val success = poseWebSocketServer?.sendPracticeStart(workoutId) == true
        if (!success) {
            Log.w("PracticeControl", "PRACTICE_START failed")
            isPhoneConnected = false
            showPairingOverlay()
            pairingStatusText.text = "连接已失效，请重新扫码"
            return
        }

        // 二维码或断线弹窗全部隐藏
        pairingOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE

        // 切换到视频+Pose
        playbackMode = PlaybackMode.VIDEO_WITH_POSE
        skeletonView.clearPose()

        //第一次开始前，倒计时阶段先隐藏火柴人。
        skeletonContainer.visibility = if (hasWorkoutStarted) View.VISIBLE else View.GONE

        // 已连接，不显示右上扫码按钮
        btnScanPhone.visibility = View.GONE

        if (hasWorkoutStarted) {
            //训练中重新连接手机，直接恢复训练。
            alignNextScoreNodeToCurrentPosition()
            resumeWorkoutVideo()
            skeletonContainer.visibility = View.VISIBLE
            startScoreChecking()
        } else {
            //第一次进入课程，先显示5秒倒计时。
            startPreparationCountdown()
        }
    }

    private fun startPoseServer() {
        poseWebSocketServer =
            PoseWebSocketServer(
                serverPort = serverPort,
                listener = object : PoseWebSocketServer.ServerListener {
                    override fun onServerStarted() { Log.i("MainActivity", "OTT WebSocket Server started") }
                    override fun onClientConnected() { Log.i("MainActivity", "Phone connected") }
                    override fun onPairingSucceeded() { Log.i("MainActivity", "Phone pairing succeeded")

                        runOnUiThread {
                            // 只有当前仍然处于二维码扫码流程， 才接受这次配对结果。
                            if (businessState == BusinessState.PLAYING && overlayState == OverlayState.PAIRING_QR
                            ) {
                                isPhoneConnected = true
                                skeletonView.resetBodySizeCalibration()
                                Log.i("Pairing", "Pairing accepted, start VIDEO_WITH_POSE")
                                showReadyCheckPage()
                            } else {
                                Log.i("Pairing", "Pairing ignored because QR overlay is no longer active")
                                isPhoneConnected = false
                                poseWebSocketServer?.disconnectActiveClient("Pairing cancelled")
                                refreshScanButton()
                            }
                        }
                    }

                    override fun onReadyCheckPassed() {
                        Log.i("MainActivity", "Phone ready check passed")
                        runOnUiThread {
                            finishReadyCheck()
                        }
                    }

                    override fun onPhoneExitedDetection() {
                        Log.i("MainActivity", "Phone exited detection")
                        runOnUiThread {
                            if (businessState != BusinessState.PLAYING || isWorkoutCompleted) {
                                return@runOnUiThread
                            }
                            isPhoneConnected = false
                            showPhoneDisconnectedOverlay()
                        }
                    }

                    override fun onClientDisconnected() {
                        Log.i("MainActivity", "Phone disconnected")
                        runOnUiThread {
                            val wasUsingPose = businessState == BusinessState.PLAYING
                                    && playbackMode == PlaybackMode.VIDEO_WITH_POSE
                                    && !isWorkoutCompleted
                            val wasWaitingReadyCheck = businessState == BusinessState.PLAYING
                                    && overlayState == OverlayState.READY_CHECK
                                    && !isWorkoutCompleted
                            isPhoneConnected = false
                            if (wasUsingPose || wasWaitingReadyCheck) {
                                showPhoneDisconnectedOverlay()
                            } else {
                                //例如当前处于视频选择界面，手机断线只更新连接状态，不需要弹窗。
                                refreshScanButton()
                                if (overlayState == OverlayState.PAIRING_QR) {
                                    pairingStatusText.text = "等待手机连接..."
                                }
                            }
                        }
                    }

                    override fun onMessageReceived(message: String) {
                    }

                    override fun onPoseFrame(frame: PoseFrameData) {
                        enqueueLatestPoseFrame(frame)
                    }

                    override fun onServerError(error: String) { Log.e("MainActivity", "Server error: $error") }
                }
            )
        poseWebSocketServer?.start()
    }

    //网络帧采用“最新帧优先”，避免主线程处理已经过时的动作。
    private fun enqueueLatestPoseFrame(frame: PoseFrameData) {
        val receivedAtMs = SystemClock.elapsedRealtime()
        posePerfReceivedCount.incrementAndGet()
        latestPoseReceivedAtMs.set(receivedAtMs)
        if (latestPendingPoseFrame.getAndSet(frame) != null) {
            posePerfOverwrittenCount.incrementAndGet()
        }
        reportPosePipelinePerformance(receivedAtMs)

        if (isPoseUiUpdateScheduled.compareAndSet(false, true)) {
            scoreHandler.post {
                if (::skeletonView.isInitialized) {
                    skeletonView.postOnAnimation(poseUiUpdateTask)
                } else {
                    isPoseUiUpdateScheduled.set(false)
                }
            }
        }
    }

    //只在主线程消费最新姿态，显示与评分仍使用同一份未滤波原始数据。
    private fun applyLatestPoseFrameOnUi(frame: PoseFrameData) {
        posePerfConsumedCount.incrementAndGet()
        val queueDelayMs = (SystemClock.elapsedRealtime() - latestPoseReceivedAtMs.get())
            .coerceAtLeast(0L)
        posePerfQueueDelayTotalMs.addAndGet(queueDelayMs)
        posePerfQueueDelayMaxMs.getAndAccumulate(queueDelayMs, ::maxOf)

        if (businessState != BusinessState.PLAYING
            || playbackMode != PlaybackMode.VIDEO_WITH_POSE
            || !isPhoneConnected
            || isWorkoutCompleted
        ) { return }

        //倒计时期间不绘制火柴人，只收集完整人体帧用于本次连接的大小标定。
        if (isPreparingWorkout) {
            skeletonView.observeBodySizeCalibrationFrame(frame)
            return
        }
        if (!hasWorkoutStarted) { return }

        if (frame.persons.isEmpty()) {
            skeletonView.clearPose()
            scoringEngine?.clearUserFrames()
        } else {
            skeletonView.updatePose(frame)
            scoringEngine?.addUserFrame(frame)
        }
    }

    //每两秒输出一次姿态数据管线状态，统一使用 POSE_PERF 标签。
    private fun reportPosePipelinePerformance(nowMs: Long) {
        val windowStartMs = posePerfWindowStartedAtMs.get()
        val elapsedMs = nowMs - windowStartMs
        if (elapsedMs < 2_000L
            || !posePerfWindowStartedAtMs.compareAndSet(windowStartMs, nowMs)
        ) { return }

        val received = posePerfReceivedCount.getAndSet(0L)
        val consumed = posePerfConsumedCount.getAndSet(0L)
        val overwritten = posePerfOverwrittenCount.getAndSet(0L)
        val queueDelayTotalMs = posePerfQueueDelayTotalMs.getAndSet(0L)
        val queueDelayMaxMs = posePerfQueueDelayMaxMs.getAndSet(0L)
        val seconds = elapsedMs / 1_000f
        val averageQueueDelayMs = if (consumed == 0L) 0f
        else queueDelayTotalMs.toFloat() / consumed

        Log.i(
            "POSE_PERF",
            "PIPELINE receive=${"%.1f".format(received / seconds)}fps, " +
                    "consume=${"%.1f".format(consumed / seconds)}fps, " +
                    "overwritten=$overwritten/$received, " +
                    "queueAvg=${"%.1f".format(averageQueueDelayMs)}ms, " +
                    "queueMax=${queueDelayMaxMs}ms"
        )
    }

    //手机断开连接弹窗
    private fun showPhoneDisconnectedOverlay() {
        //训练已经结束时以完成页为最高优先级，不再显示断连弹窗。
        if (isWorkoutCompleted) { return }
        // 视频暂停，等用户决定
        practicePlayer?.pause()
        stopPlaybackProgressUpdating()
        stopPreparationCountdown()

        //评分停止
        stopScoreChecking()
        hideScorePanel()

        // 手机已经不存在，所以先切纯视频语义
        playbackMode = PlaybackMode.VIDEO_ONLY
        skeletonView.resetBodySizeCalibration()
        overlayState = OverlayState.PHONE_DISCONNECTED
        // 清火柴人
        skeletonView.clearPose()
        skeletonContainer.visibility = View.GONE
        // 暂时隐藏扫码按钮
        btnScanPhone.visibility = View.GONE
        // 二维码层隐藏
        pairingOverlay.visibility = View.GONE
        //动作检测判断
        isWaitingReadyCheck = false
        readyCheckOverlay.visibility = View.GONE
        // 断线层显示
        exitConfirmationOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.VISIBLE
        btnDisconnectKnown.requestFocus()
    }

    //知道啦
    private fun acknowledgePhoneDisconnected() {
        disconnectedOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE
        playbackMode = PlaybackMode.VIDEO_ONLY
        skeletonContainer.visibility = View.GONE
        skeletonView.clearPose()
        refreshScanButton()
        if (btnScanPhone.visibility == View.VISIBLE
        ) {
            //获得焦点
            btnScanPhone.requestFocus()
        }

        //第一次训练仍需完成准备倒计时；训练中断线则直接继续。
        if (hasWorkoutStarted) {
            resumeWorkoutVideo()
        } else {
            startPreparationCountdown()
        }
    }

    //重新扫码连接
    private fun reconnectPhone() {
        disconnectedOverlay.visibility = View.GONE
        overlayState = OverlayState.NONE
        //showPairingOverlay内部会继续保持视频暂停
        showPairingOverlay()
    }

    //从视频播放到视频选择界面
    private fun stopCurrentWorkoutKeepConnection() {
        //如果手机正在发送Pose：告诉手机停止上传。注意：这里不关闭WebSocket。
        if (playbackMode == PlaybackMode.VIDEO_WITH_POSE && isPhoneConnected
        ) {
            poseWebSocketServer?.sendPracticeStop()
        }
        practicePlayer?.pause()
        practicePlayer?.seekTo(0)
        clearScoring()
        skeletonView.clearPose()
        skeletonContainer.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        exitConfirmationOverlay.visibility = View.GONE
        btnScanPhone.visibility = View.GONE
        playbackMode = PlaybackMode.NONE
        overlayState = OverlayState.NONE
        selectedWorkoutId = null
        showVideoSelectionState()
    }

    //退出边看边练
    private fun exitPracticeBusiness() {
        if (isPhoneConnected) {
            poseWebSocketServer?.sendPracticeStop()
            //电视主动退出整个边看边练区域时，让手机同步回到扫码页。
            poseWebSocketServer?.sendExitDetection()
        }
        practicePlayer?.pause()
        workoutPreviewPlayer?.pause()
        //SurfaceView即使父容器隐藏也可能短暂留下最后一帧，退出时主动解绑。
        practicePlayerView.visibility = View.GONE
        practicePlayerView.player = null
        workoutPreviewPlayerView.visibility = View.GONE
        workoutPreviewPlayerView.player = null
        practicePlayer?.seekTo(0)
        stopPreparationCountdown()
        stopPlaybackProgressUpdating()
        isWaitingReadyCheck = false
        clearScoring()
        skeletonView.clearPose()
        skeletonView.resetBodySizeCalibration()
        businessState = BusinessState.HOME
        playbackMode = PlaybackMode.NONE
        overlayState = OverlayState.NONE
        isPhoneConnected = false
        selectedWorkoutId = null
        practicePanel.visibility = View.GONE
        videoSelectionPanel.visibility = View.GONE
        playingPanel.visibility = View.GONE
        pairingOverlay.visibility = View.GONE
        readyCheckOverlay.visibility = View.GONE
        preparationOverlay.visibility = View.GONE
        finalScoreOverlay.visibility = View.GONE
        scorePanel.visibility = View.GONE
        playbackProgressPanel.visibility = View.GONE
        disconnectedOverlay.visibility = View.GONE
        exitConfirmationOverlay.visibility = View.GONE
        btnScanPhone.visibility = View.GONE

        //最后真正断开OTT和手机
        poseWebSocketServer?.disconnectActiveClient("Exit practice business")
    }

    //退出OTT APP时退出端口释放资源
    override fun onDestroy() {
        latestPendingPoseFrame.set(null)
        isPoseUiUpdateScheduled.set(false)
        if (::skeletonView.isInitialized) {
            skeletonView.removeCallbacks(poseUiUpdateTask)
        }
        stopPreparationCountdown()
        stopScoreChecking()
        stopPlaybackProgressUpdating()
        //释放正式训练播放器资源。
        practicePlayer?.release()
        practicePlayer = null
        practicePlayerView.player = null

        //释放视频预览资源
        workoutPreviewPlayer?.release()
        workoutPreviewPlayer = null
        workoutPreviewPlayerView.player = null

        //释放独立主视频播放器。
        mainVideoPlayer?.release()
        mainVideoPlayer = null
        mainVideoPlayerView.player = null

        //关闭WebSocket Server
        try { poseWebSocketServer?.stop() }
        catch (e: Exception) {
            Log.e("MainActivity", "Stop server failed", e)
        }
        super.onDestroy()
    }
}
