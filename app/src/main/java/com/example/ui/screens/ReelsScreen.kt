package com.example.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.ReelCommentItem
import com.example.data.model.ReelItem
import com.example.ui.components.FoulCopyShareDialog
import com.example.ui.components.copyTextToClipboard
import com.example.ui.components.shareTextIntent
import com.example.ui.theme.FoulBlackBg
import com.example.ui.theme.FoulCardDark
import com.example.ui.theme.FoulGoldStar
import com.example.ui.theme.FoulLiveGreen
import com.example.ui.theme.FoulPurpleBright
import com.example.ui.theme.FoulPurplePrimary
import com.example.ui.theme.FoulTextSecondary

private val FoulGoldAccent = FoulGoldStar
private val FoulPurpleSoft = Color(0xFFB388FF)

@Composable
fun ReelsScreen(
    reels: List<ReelItem>,
    isLoading: Boolean,
    isUploading: Boolean,
    uploadStatusMessage: String?,
    selectedReelIdFromDeepLink: String?,
    officialChannelUrl: String,
    commentsForActiveReel: List<ReelCommentItem>,
    isLoadingComments: Boolean,
    onRefreshReels: () -> Unit,
    onUploadReelVideo: (Uri, String, String) -> Unit,
    onToggleLike: (ReelItem) -> Unit,
    onRecordView: (ReelItem) -> Unit,
    onRecordShare: (ReelItem) -> Unit,
    onOpenComments: (ReelItem) -> Unit,
    onAddComment: (ReelItem, String) -> Unit,
    onConsumeDeepLinkReelId: () -> Unit
) {
    val context = LocalContext.current

    var pendingUploadUri by remember { mutableStateOf<Uri?>(null) }
    var activeCommentsReel by remember { mutableStateOf<ReelItem?>(null) }
    var activeShareDialogReel by remember { mutableStateOf<ReelItem?>(null) }
    var showPublishAccountRequiredDialog by remember { mutableStateOf(false) }

    // Zero-permission Android Photo Picker for selecting a video from device
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUploadUri = uri
        }
    }

    val triggerPublishReel: () -> Unit = {
        if (!com.example.data.repository.SocialFirebaseRepository.isUserAuthenticatedWithCompleteProfile()) {
            showPublishAccountRequiredDialog = true
        } else {
            videoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { reels.size.coerceAtLeast(1) }
    )

    // Scroll to deep-linked Reel if present
    LaunchedEffect(selectedReelIdFromDeepLink, reels.size) {
        val targetId = selectedReelIdFromDeepLink?.trim().orEmpty()
        if (targetId.isNotEmpty() && reels.isNotEmpty()) {
            val idx = reels.indexOfFirst {
                it.id.equals(targetId, ignoreCase = true) ||
                    "${it.chatId}_${it.messageId}".equals(targetId, ignoreCase = true)
            }
            if (idx >= 0) {
                pagerState.scrollToPage(idx)
                onConsumeDeepLinkReelId()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FoulBlackBg)
            .testTag("reels_screen_root")
    ) {
        if (isLoading && reels.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = FoulPurplePrimary)
                Text(
                    text = "جاري تحميل مقاطع FOUL REELS...",
                    color = FoulTextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (reels.isEmpty()) {
            EmptyReelsState(
                officialChannelUrl = officialChannelUrl,
                onPublishClick = triggerPublishReel,
                onRefreshClick = onRefreshReels
            )
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("reels_vertical_pager"),
                beyondViewportPageCount = 1,
                key = { page -> reels.getOrNull(page)?.id ?: "empty_$page" }
            ) { page ->
                val reel = reels.getOrNull(page)
                if (reel != null) {
                    val isCurrentPage = pagerState.settledPage == page
                    ReelPagePlayerItem(
                        reel = reel,
                        isCurrentPage = isCurrentPage,
                        officialChannelUrl = officialChannelUrl,
                        onLikeClick = { onToggleLike(reel) },
                        onCommentClick = {
                            activeCommentsReel = reel
                            onOpenComments(reel)
                        },
                        onViewRecorded = { onRecordView(reel) },
                        onShareClick = {
                            onRecordShare(reel)
                            activeShareDialogReel = reel
                        }
                    )
                }
            }
        }

        // Top Overlay Bar with Prominent Publish Button + Refresh + Copy Official Channel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Prominent Top Publish Reel Button
            Surface(
                onClick = triggerPublishReel,
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.28f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(FoulPurplePrimary, Color(0xFFAA00FF))
                        )
                    )
                    .testTag("publish_reel_top_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "نشر ريلز",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isUploading) "جاري الرفع..." else "نشر ريلز",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = {
                        copyTextToClipboard(
                            context = context,
                            label = "FOUL Official Channel",
                            text = officialChannelUrl,
                            toastMessage = "تم نسخ رابط القناة الرسمية!"
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, FoulGoldAccent.copy(alpha = 0.55f)),
                    modifier = Modifier.testTag("reels_copy_official_channel_top_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = "نسخ رابط القناة الرسمية",
                            tint = FoulGoldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "القناة الرسمية",
                            color = FoulGoldAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = onRefreshReels,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                        .testTag("reels_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "تحديث الريلز",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Upload progress banner if uploading
        AnimatedVisibility(
            visible = isUploading || !uploadStatusMessage.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 62.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1A142D).copy(alpha = 0.95f),
                border = BorderStroke(1.dp, FoulPurpleBright),
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            color = FoulPurpleSoft,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = uploadStatusMessage ?: "جاري رفع ونشر الريلز على FOUL TV...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Publish Reel Dialog when a video URI has been picked
    if (pendingUploadUri != null) {
        PublishReelModalDialog(
            videoUri = pendingUploadUri!!,
            onDismiss = { pendingUploadUri = null },
            onConfirmPublish = { caption, overlayText ->
                val uriToUpload = pendingUploadUri!!
                pendingUploadUri = null
                onUploadReelVideo(uriToUpload, caption, overlayText)
            }
        )
    }

    // Comments Dialog
    if (activeCommentsReel != null) {
        ReelCommentsDialog(
            reel = activeCommentsReel!!,
            comments = commentsForActiveReel,
            isLoading = isLoadingComments,
            onDismiss = { activeCommentsReel = null },
            onSendComment = { commentText ->
                onAddComment(activeCommentsReel!!, commentText)
            }
        )
    }

    // Copy & Share Dialog
    if (activeShareDialogReel != null) {
        val reel = activeShareDialogReel!!
        FoulCopyShareDialog(
            title = reel.caption.ifBlank { "مقطع ريلز — FOUL TV" },
            videoStreamUrl = reel.copyVideoUrl.ifBlank { reel.streamUrl },
            smartShareUrl = reel.shareUrl,
            officialChannelUrl = reel.channelUrl.ifBlank { officialChannelUrl },
            onDismiss = { activeShareDialogReel = null }
        )
    }

    // Account Required Dialog when a Guest/Skipped user tries to Publish a Reel
    if (showPublishAccountRequiredDialog) {
        Dialog(onDismissRequest = { showPublishAccountRequiredDialog = false }) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF141122),
                border = BorderStroke(1.2.dp, FoulGoldAccent.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "نشر الريلز يتطلب إنشاء حساب",
                        color = FoulGoldAccent,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "لقد اخترت تخطي إنشاء الحساب عند الدخول. لا يمكنك نشر مقاطع ريلز أو نسخ أي رابط فيديو أو قنوات إلا بعد إكمال المطلوب وإنشاء حساب أو تسجيل الدخول.",
                        color = FoulTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            showPublishAccountRequiredDialog = false
                            com.example.data.repository.SocialFirebaseRepository.requestOpenFullAuthScreen()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("reels_publish_locked_create_account_button")
                    ) {
                        Text(
                            text = "إنشاء حساب / تسجيل الدخول الآن",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                    Surface(
                        onClick = { showPublishAccountRequiredDialog = false },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "إغلاق",
                                color = FoulTextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReelPagePlayerItem(
    reel: ReelItem,
    isCurrentPage: Boolean,
    officialChannelUrl: String,
    onLikeClick: () -> Unit,
    onCommentClick: () -> Unit,
    onViewRecorded: () -> Unit,
    onShareClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isPausedManually by remember(reel.id) { mutableStateOf(false) }
    var isBuffering by remember(reel.id) { mutableStateOf(true) }
    var hasRecordedView by remember(reel.id) { mutableStateOf(false) }
    var usedFallbackUrl by remember(reel.id) { mutableStateOf(false) }

    val exoPlayer = remember(reel.id) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("FOUL-TV-ExoPlayer/1.2.2")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1_500,
                15_000,
                500,
                1_000
            )
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                val initialUrl = reel.streamUrl.ifBlank { reel.videoUrl }
                if (initialUrl.isNotBlank()) {
                    setMediaItem(MediaItem.fromUri(initialUrl))
                    prepare()
                }
            }
    }

    DisposableEffect(exoPlayer, reel.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY && isCurrentPage && !hasRecordedView) {
                    hasRecordedView = true
                    onViewRecorded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                if (!usedFallbackUrl && reel.videoUrl.isNotBlank() && reel.videoUrl != reel.streamUrl) {
                    usedFallbackUrl = true
                    exoPlayer.setMediaItem(MediaItem.fromUri(reel.videoUrl))
                    exoPlayer.prepare()
                    if (isCurrentPage && !isPausedManually) {
                        exoPlayer.playWhenReady = true
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isCurrentPage, isPausedManually) {
        if (isCurrentPage && !isPausedManually) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer, isCurrentPage) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> {
                    if (isCurrentPage && !isPausedManually) {
                        exoPlayer.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isPausedManually = !isPausedManually
            }
    ) {
        // Google Media3 ExoPlayer Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exoPlayer
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("reel_exoplayer_view_${reel.id}")
        )

        // Bottom gradient scrim for readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.90f)
                        )
                    )
                )
        )

        // Center Play icon indicator when paused manually
        AnimatedVisibility(
            visible = isPausedManually,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "تشغيل",
                    tint = Color.White,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        if (isBuffering && isCurrentPage) {
            CircularProgressIndicator(
                color = FoulPurpleBright,
                strokeWidth = 2.5.dp,
                modifier = Modifier
                    .size(38.dp)
                    .align(Alignment.Center)
            )
        }

        // Right-Side Interactive Action Rail (Like, Comment, Views, Copy Video, Copy Share, Share)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Like Button
            ReelActionCircleButton(
                icon = if (reel.isLikedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                iconTint = if (reel.isLikedByMe) Color(0xFFFF1744) else Color.White,
                label = reel.likesCount.toString(),
                testTag = "reel_like_button_${reel.id}",
                onClick = onLikeClick
            )

            // Comments Button
            ReelActionCircleButton(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconTint = Color.White,
                label = "تعليق",
                testTag = "reel_comment_button_${reel.id}",
                onClick = onCommentClick
            )

            // Views Counter
            ReelActionCircleButton(
                icon = Icons.Default.Visibility,
                iconTint = FoulLiveGreen,
                label = reel.viewsCount.toString(),
                testTag = "reel_views_badge_${reel.id}",
                onClick = {}
            )

            // Direct Copy Video URL (.mp4 / stream)
            ReelActionCircleButton(
                icon = Icons.Default.ContentCopy,
                iconTint = FoulPurpleSoft,
                label = "نسخ الفيديو",
                testTag = "reel_copy_video_button_${reel.id}",
                onClick = {
                    val urlToCopy = reel.copyVideoUrl.ifBlank { reel.streamUrl }
                    copyTextToClipboard(
                        context = context,
                        label = "FOUL Reel Video URL",
                        text = urlToCopy,
                        toastMessage = "تم نسخ رابط الفيديو المباشر!"
                    )
                }
            )

            // Direct Copy Smart Share Link (/open/reel)
            ReelActionCircleButton(
                icon = Icons.Default.Link,
                iconTint = FoulGoldAccent,
                label = "نسخ الرابط",
                testTag = "reel_copy_share_button_${reel.id}",
                onClick = {
                    copyTextToClipboard(
                        context = context,
                        label = "FOUL Reel Share Link",
                        text = reel.shareUrl,
                        toastMessage = "تم نسخ رابط المشاركة الذكي!"
                    )
                }
            )

            // Full Share Dialog (Telegram / WhatsApp / Official Channel)
            ReelActionCircleButton(
                icon = Icons.Default.Share,
                iconTint = Color.White,
                label = "مشاركة",
                testTag = "reel_share_button_${reel.id}",
                onClick = onShareClick
            )
        }

        // Bottom-Start Caption, User Info & Quick Copy Pills
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.78f)
                .padding(start = 16.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = FoulPurplePrimary,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "F",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                }
                Column {
                    Text(
                        text = reel.userName.ifBlank { "FOUL TV" },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = reel.userHandle.ifBlank { "@foultv" },
                        color = FoulPurpleSoft,
                        fontSize = 12.sp
                    )
                }
            }

            if (reel.caption.isNotBlank()) {
                Text(
                    text = reel.caption,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Quick Copy & Share Row Pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        copyTextToClipboard(
                            context = context,
                            label = "FOUL Official Channel",
                            text = reel.channelUrl.ifBlank { officialChannelUrl },
                            toastMessage = "تم نسخ رابط القناة الرسمية!"
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                ) {
                    Text(
                        text = "نسخ رابط القناة الرسمية",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Surface(
                    onClick = {
                        val shareMsg = buildString {
                            append(reel.caption.ifBlank { "شاهد هذا الريلز على FOUL TV" })
                            append("\n\nرابط الفتح المباشر في تطبيق FOUL TV:\n")
                            append(reel.shareUrl)
                            append("\n\nرابط الفيديو المباشر:\n")
                            append(reel.copyVideoUrl.ifBlank { reel.streamUrl })
                        }
                        shareTextIntent(context, "FOUL REELS", shareMsg)
                    },
                    shape = RoundedCornerShape(999.dp),
                    color = FoulPurplePrimary.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, FoulPurpleBright.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "مشاركة فورية",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelActionCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.52f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(23.dp)
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyReelsState(
    officialChannelUrl: String,
    onPublishClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = FoulPurplePrimary.copy(alpha = 0.18f),
            border = BorderStroke(1.5.dp, FoulPurplePrimary.copy(alpha = 0.55f)),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = FoulPurpleSoft,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "مرحباً بك في قسم FOUL REELS",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "متصل بقاعدة بيانات FOUL الجديدة ومشغل Google Media3 ExoPlayer. يمكنك نشر أول مقطع ريلز من هاتفك الآن أو تحديث القائمة.",
            color = FoulTextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(22.dp))
        Button(
            onClick = onPublishClick,
            colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("empty_reels_publish_button")
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "نشر مقطع ريلز من الهاتف",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onRefreshClick,
                shape = RoundedCornerShape(14.dp),
                color = FoulCardDark,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "تحديث المقاطع",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
            Surface(
                onClick = {
                    copyTextToClipboard(
                        context = context,
                        label = "FOUL Official Channel",
                        text = officialChannelUrl,
                        toastMessage = "تم نسخ رابط القناة الرسمية!"
                    )
                },
                shape = RoundedCornerShape(14.dp),
                color = FoulCardDark,
                border = BorderStroke(1.dp, FoulGoldAccent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "نسخ رابط القناة",
                        color = FoulGoldAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishReelModalDialog(
    videoUri: Uri,
    onDismiss: () -> Unit,
    onConfirmPublish: (caption: String, overlayText: String) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    var overlayText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF141122),
            border = BorderStroke(1.dp, FoulPurplePrimary.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "نشر مقطع ريلز جديد",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = FoulTextSecondary
                        )
                    }
                }

                Text(
                    text = "تم اختيار الفيديو بنجاح (${videoUri.lastPathSegment ?: "video.mp4"}). سيتم رفعه ومزامنته مباشرة مع قاعدة بيانات FOUL وتوليد روابط النسخ والمشاركة الذكية.",
                    color = FoulTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("وصف المقطع (Caption)") },
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FoulPurplePrimary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = FoulPurpleSoft,
                        unfocusedLabelColor = FoulTextSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("publish_reel_caption_input")
                )

                OutlinedTextField(
                    value = overlayText,
                    onValueChange = { overlayText = it },
                    label = { Text("نص إضافي على الفيديو (اختياري)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FoulPurplePrimary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = FoulPurpleSoft,
                        unfocusedLabelColor = FoulTextSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("publish_reel_overlay_input")
                )

                Button(
                    onClick = { onConfirmPublish(caption.trim(), overlayText.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_publish_reel_button")
                ) {
                    Text(
                        text = "رفع ونشر الآن",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelCommentsDialog(
    reel: ReelItem,
    comments: List<ReelCommentItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSendComment: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF141122),
            border = BorderStroke(1.dp, FoulPurplePrimary.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "التعليقات (${comments.size})",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = FoulTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isLoading && comments.isEmpty()) {
                        CircularProgressIndicator(
                            color = FoulPurplePrimary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (comments.isEmpty()) {
                        Text(
                            text = "لا توجد تعليقات بعد. كن أول من يعلق على هذا المقطع!",
                            color = FoulTextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(comments, key = { it.id }) { comment ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = comment.userName,
                                            color = FoulPurpleSoft,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            text = comment.text,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("أضف تعليقاً...") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FoulPurplePrimary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reel_comment_input")
                    )
                    IconButton(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSendComment(trimmed)
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(FoulPurplePrimary)
                            .testTag("reel_comment_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "إرسال التعليق",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
