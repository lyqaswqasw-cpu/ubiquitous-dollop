package com.example.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.AppAvatarSpec
import com.example.data.model.SocialUserProfile
import com.example.data.repository.SocialFirebaseRepository
import com.example.ui.theme.FoulBlackBg
import com.example.ui.theme.FoulCardDark
import com.example.ui.theme.FoulDivider
import com.example.ui.theme.FoulGoldStar
import com.example.ui.theme.FoulLiveGreen
import com.example.ui.theme.FoulLiveRed
import com.example.ui.theme.FoulPurpleBright
import com.example.ui.theme.FoulPurpleDeep
import com.example.ui.theme.FoulPurpleGlow
import com.example.ui.theme.FoulPurplePrimary
import com.example.ui.theme.FoulTeamAwayCyan
import com.example.ui.theme.FoulTextSecondary

enum class OnboardingAuthStep {
    WELCOME_CHOICE,
    AUTH_FORM,
    GOOGLE_COMPLETE_PROFILE
}

enum class AuthFormTab {
    SIGN_UP,
    SIGN_IN
}

data class DeviceGoogleAccountItem(
    val email: String,
    val displayName: String,
    val badgeLabel: String = "حساب Google في الجهاز"
)

@Composable
fun FoulAuthOnboardingScreen(
    currentUser: SocialUserProfile?,
    isLoading: Boolean,
    errorMessage: String?,
    startDirectlyOnAuthForm: Boolean = false,
    allowBackToApp: Boolean = false,
    onClearError: () -> Unit,
    onConfirmSkipAndStart: () -> Unit,
    onRegisterWithEmail: (personalName: String, username: String, email: String, password: String, avatarId: String) -> Unit,
    onLoginWithEmail: (email: String, password: String) -> Unit,
    onSignInWithGoogleGmail: (googleEmail: String, suggestedName: String) -> Unit,
    onCompleteGoogleProfile: (personalName: String, username: String, avatarId: String) -> Unit,
    onBackToApp: () -> Unit = {}
) {
    val context = LocalContext.current

    var currentStep by remember(startDirectlyOnAuthForm) {
        mutableStateOf(
            when {
                currentUser != null && !currentUser.isProfileComplete -> OnboardingAuthStep.GOOGLE_COMPLETE_PROFILE
                startDirectlyOnAuthForm -> OnboardingAuthStep.AUTH_FORM
                else -> OnboardingAuthStep.WELCOME_CHOICE
            }
        )
    }

    // Automatically transition to Google profile completion step if user signed in via Google and profile is incomplete
    LaunchedEffect(currentUser?.uid, currentUser?.isProfileComplete) {
        if (currentUser != null && !currentUser.isProfileComplete) {
            currentStep = OnboardingAuthStep.GOOGLE_COMPLETE_PROFILE
        }
    }

    var showSkipWarningModal by remember { mutableStateOf(false) }
    var showGoogleAccountPickerSheet by remember { mutableStateOf(false) }

    // Discover Google accounts registered on this Android device
    val deviceGoogleAccounts = remember { mutableStateListOf<DeviceGoogleAccountItem>() }

    fun refreshDeviceGoogleAccounts() {
        val discovered = SocialFirebaseRepository.getDeviceGoogleAccounts(context)
        deviceGoogleAccounts.clear()
        for ((email, name) in discovered) {
            deviceGoogleAccounts.add(
                DeviceGoogleAccountItem(
                    email = email,
                    displayName = name.ifBlank { email.substringBefore("@") },
                    badgeLabel = "متصل على هذا الجهاز"
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        refreshDeviceGoogleAccounts()
    }

    // Native Android System Google Account Chooser (`AccountManager.newChooseAccountIntent`)
    // Pops up the real system sheet of Google accounts on the phone/tablet without requiring any password!
    val systemAccountChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val selectedEmail = result.data
                ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?.trim()
                ?.lowercase()
                .orEmpty()
            if (selectedEmail.contains("@")) {
                val suggestedName = selectedEmail.substringBefore("@")
                    .replace(".", " ")
                    .replace("_", " ")
                    .trim()
                SocialFirebaseRepository.rememberDeviceGoogleAccount(context, selectedEmail, suggestedName)
                refreshDeviceGoogleAccounts()
                showGoogleAccountPickerSheet = false
                onSignInWithGoogleGmail(selectedEmail, suggestedName)
                return@rememberLauncherForActivityResult
            }
        }
        // If user canceled the system dialog or the device is an emulator with no OS accounts configured,
        // keep the in-app Google Account Chooser Sheet open so they can pick with 1 tap.
        refreshDeviceGoogleAccounts()
        showGoogleAccountPickerSheet = true
    }

    fun launchSystemGoogleAccountPicker() {
        try {
            @Suppress("DEPRECATION")
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
            systemAccountChooserLauncher.launch(intent)
        } catch (_: Exception) {
            showGoogleAccountPickerSheet = true
        }
    }

    fun triggerGoogleSignInFlow() {
        onClearError()
        refreshDeviceGoogleAccounts()
        launchSystemGoogleAccountPicker()
    }

    BackHandler(enabled = currentStep != OnboardingAuthStep.WELCOME_CHOICE || allowBackToApp) {
        when {
            currentStep == OnboardingAuthStep.AUTH_FORM && !startDirectlyOnAuthForm -> {
                onClearError()
                currentStep = OnboardingAuthStep.WELCOME_CHOICE
            }
            allowBackToApp -> onBackToApp()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(FoulBlackBg)
            .testTag("foul_auth_onboarding_root")
    ) {
        val isWideScreen = maxWidth >= 640.dp

        // Luxury Broadcast Aurora Background Canvas
        FoulLuxuryAmbientBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isWideScreen) 36.dp else 16.dp,
                    vertical = if (isWideScreen) 24.dp else 14.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (isWideScreen) 600.dp else 500.dp)
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    label = "onboarding_auth_step_transition"
                ) { step ->
                    when (step) {
                        OnboardingAuthStep.WELCOME_CHOICE -> {
                            LuxuryWelcomeChoiceScreen(
                                isWideScreen = isWideScreen,
                                onWantCreateAccount = {
                                    onClearError()
                                    showSkipWarningModal = false
                                    currentStep = OnboardingAuthStep.AUTH_FORM
                                },
                                onClickSkipInitial = {
                                    showSkipWarningModal = true
                                }
                            )
                        }

                        OnboardingAuthStep.AUTH_FORM -> {
                            LuxuryFullScreenAuthCard(
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                canNavigateBack = !startDirectlyOnAuthForm || allowBackToApp,
                                deviceGoogleAccounts = deviceGoogleAccounts,
                                onBackClick = {
                                    onClearError()
                                    if (!startDirectlyOnAuthForm) {
                                        currentStep = OnboardingAuthStep.WELCOME_CHOICE
                                    } else if (allowBackToApp) {
                                        onBackToApp()
                                    }
                                },
                                onClearError = onClearError,
                                onSubmitSignUp = onRegisterWithEmail,
                                onSubmitSignIn = onLoginWithEmail,
                                onOpenGoogleAccountPicker = ::triggerGoogleSignInFlow,
                                onQuickSelectGoogleAccount = { acc ->
                                    onClearError()
                                    onSignInWithGoogleGmail(acc.email, acc.displayName)
                                },
                                onSkipToAppClick = {
                                    showSkipWarningModal = true
                                }
                            )
                        }

                        OnboardingAuthStep.GOOGLE_COMPLETE_PROFILE -> {
                            LuxuryGoogleCompleteProfileCard(
                                googleEmail = currentUser?.email.orEmpty(),
                                initialPersonalName = currentUser?.personalName.orEmpty(),
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                onClearError = onClearError,
                                onSubmitProfile = onCompleteGoogleProfile
                            )
                        }
                    }
                }
            }
        }
    }

    // Luxury Skip Warning Modal Dialog (Triggered from Welcome Screen or Auth Form)
    if (showSkipWarningModal) {
        LuxurySkipRestrictionDialog(
            onDismiss = { showSkipWarningModal = false },
            onCreateAccountNow = {
                showSkipWarningModal = false
                onClearError()
                currentStep = OnboardingAuthStep.AUTH_FORM
            },
            onConfirmSkipAndStart = {
                showSkipWarningModal = false
                onConfirmSkipAndStart()
            }
        )
    }

    // Google Device Accounts Picker Bottom Sheet (1-Tap Email Selection without Password!)
    if (showGoogleAccountPickerSheet) {
        GoogleDeviceAccountsBottomSheet(
            isLoading = isLoading,
            deviceAccounts = deviceGoogleAccounts,
            onDismiss = { showGoogleAccountPickerSheet = false },
            onLaunchAndroidSystemPicker = ::launchSystemGoogleAccountPicker,
            onSelectGoogleAccount = { email, displayName ->
                SocialFirebaseRepository.rememberDeviceGoogleAccount(context, email, displayName)
                refreshDeviceGoogleAccounts()
                showGoogleAccountPickerSheet = false
                onSignInWithGoogleGmail(email, displayName)
            }
        )
    }
}

/**
 * Ambient Cinema & Broadcast Background with glowing aurora orbs and subtle architectural grid lines
 */
@Composable
private fun FoulLuxuryAmbientBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    Canvas(modifier = modifier) {
        // Deep Obsidian Base
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0E081C),
                    Color(0xFF07050E),
                    Color(0xFF040308)
                )
            )
        )

        // Top-Right Royal Purple Aurora Orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    FoulPurplePrimary.copy(alpha = 0.34f * pulse),
                    FoulPurpleDeep.copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.85f, size.height * 0.12f),
                radius = size.minDimension * 0.65f
            ),
            radius = size.minDimension * 0.65f,
            center = Offset(size.width * 0.85f, size.height * 0.12f)
        )

        // Bottom-Left Cyber Cyan & Gold Orb
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    FoulTeamAwayCyan.copy(alpha = 0.14f * pulse),
                    FoulGoldStar.copy(alpha = 0.07f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.15f, size.height * 0.86f),
                radius = size.minDimension * 0.60f
            ),
            radius = size.minDimension * 0.60f,
            center = Offset(size.width * 0.15f, size.height * 0.86f)
        )
    }
}

/**
 * STEP 1: Ultra-Polished First-Entry Welcome & Skip/Start Screen
 * ("تصميم تخطي وبدأ بشكل تصميم احترافي جدا وجميل ومرتب جدا")
 */
@Composable
private fun LuxuryWelcomeChoiceScreen(
    isWideScreen: Boolean,
    onWantCreateAccount: () -> Unit,
    onClickSkipInitial: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF100C1D).copy(alpha = 0.94f),
        border = BorderStroke(
            width = 1.3.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    FoulPurpleBright.copy(alpha = 0.85f),
                    FoulGoldStar.copy(alpha = 0.55f),
                    FoulTeamAwayCyan.copy(alpha = 0.45f),
                    FoulPurplePrimary.copy(alpha = 0.75f)
                )
            )
        ),
        shadowElevation = 24.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("welcome_choice_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            FoulPurpleDeep.copy(alpha = 0.48f),
                            Color(0xFF100C1D),
                            Color(0xFF090712)
                        )
                    )
                )
                .padding(horizontal = if (isWideScreen) 28.dp else 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top VIP Broadcast Pill Badge
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF1B1333),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(FoulGoldStar.copy(alpha = 0.7f), FoulPurpleBright.copy(alpha = 0.7f))
                    )
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(FoulLiveGreen)
                    )
                    Text(
                        text = "FOUL TV • البث المباشر والريلز والمكتبة السينمائية",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Official FOUL TV Launcher Crest with Double Neon Ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    FoulPurpleBright.copy(alpha = 0.45f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = FoulBlackBg,
                    border = BorderStroke(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(FoulPurpleBright, FoulGoldStar, FoulTeamAwayCyan)
                        )
                    ),
                    shadowElevation = 14.dp,
                    modifier = Modifier.size(82.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_foul_tv_launcher),
                        contentDescription = "شعار FOUL TV",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Main Welcome Headlines
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "مرحباً بك في عالم FOUL TV",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "هل تريد إنشاء حساب للاستمتاع بكامل المزايا أم تخطي والبدء فوراً؟",
                    color = Color(0xFFD6CEE8),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Structured Comparison Cards: Full Account vs Guest Skip Mode
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card 1: Full Membership Benefits
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF17112B),
                    border = BorderStroke(
                        width = 1.1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                FoulLiveGreen.copy(alpha = 0.7f),
                                FoulPurpleBright.copy(alpha = 0.55f)
                            )
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = FoulLiveGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "مزايا إنشاء الحساب وتسجيل الدخول",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.5.sp
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = FoulLiveGreen.copy(alpha = 0.16f),
                                border = BorderStroke(0.8.dp, FoulLiveGreen.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "كامل الصلاحيات",
                                    color = FoulLiveGreen,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        WelcomePrivilegeItem(
                            icon = Icons.Default.VideoLibrary,
                            text = "نشر مقاطع الريلز (Reels) من جهازك والتفاعل الكامل",
                            iconTint = FoulPurpleGlow
                        )
                        WelcomePrivilegeItem(
                            icon = Icons.Default.ContentCopy,
                            text = "نسخ روابط الفيديوهات، القنوات المباشرة، وأفلام المكتبة",
                            iconTint = FoulTeamAwayCyan
                        )
                        WelcomePrivilegeItem(
                            icon = Icons.Default.AccountCircle,
                            text = "دخول فوري بنقرة واحدة عبر حساب Google في جهازك أو البريد",
                            iconTint = FoulGoldStar
                        )
                    }
                }

                // Card 2: Skip / Quick Start Notice Summary
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1A1423).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, FoulGoldStar.copy(alpha = 0.38f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(FoulGoldStar.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = FoulGoldStar,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "عند اختيار تخطي والبدء فوراً:",
                                color = FoulGoldStar,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "يفتح التطبيق للمشاهدة مباشرة، ولكن لا يمكنك نشر ريلزات أو نسخ روابط الفيديو والقنوات والمكتبة إلا بعد إنشاء حساب.",
                                color = FoulTextSecondary,
                                fontSize = 11.2.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Primary Action Button: أريد إنشاء حساب / تسجيل الدخول
            Surface(
                onClick = onWantCreateAccount,
                shape = RoundedCornerShape(18.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    width = 1.2.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(FoulPurpleGlow, FoulGoldStar.copy(alpha = 0.7f))
                    )
                ),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("welcome_want_create_account_button")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6),
                                    FoulPurplePrimary,
                                    Color(0xFF5B21B6)
                                )
                            )
                        )
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "أريد إنشاء حساب / تسجيل الدخول",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.5.sp
                        )
                    }
                }
            }

            // Secondary Action Button: تخطي والبدء فوراً
            Surface(
                onClick = onClickSkipInitial,
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF1A152B),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            FoulGoldStar.copy(alpha = 0.45f)
                        )
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("welcome_skip_initial_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleFilled,
                        contentDescription = null,
                        tint = FoulGoldStar,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تخطي والبدء فوراً",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePrivilegeItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconTint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * STEP 2: Full-Screen Isolated Account Creation & Login Card
 * - Top: Brand header + segmented switcher (إنشاء حساب جديد | تسجيل الدخول)
 * - Middle: Form inputs (Personal Name, Username, Email, Password) + Primary Submit button
 * - Bottom ("جوجل تحت"): Official Google Sign-In button + 1-Tap Device Account Email chips
 */
@Composable
private fun LuxuryFullScreenAuthCard(
    isLoading: Boolean,
    errorMessage: String?,
    canNavigateBack: Boolean,
    deviceGoogleAccounts: List<DeviceGoogleAccountItem>,
    onBackClick: () -> Unit,
    onClearError: () -> Unit,
    onSubmitSignUp: (personalName: String, username: String, email: String, password: String, avatarId: String) -> Unit,
    onSubmitSignIn: (email: String, password: String) -> Unit,
    onOpenGoogleAccountPicker: () -> Unit,
    onQuickSelectGoogleAccount: (DeviceGoogleAccountItem) -> Unit,
    onSkipToAppClick: () -> Unit
) {
    var activeTab by remember { mutableStateOf(AuthFormTab.SIGN_UP) }
    var personalName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var selectedAvatarId by remember {
        mutableStateOf(SocialFirebaseRepository.APP_AVATARS.first().id)
    }

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF100C1D).copy(alpha = 0.96f),
        border = BorderStroke(
            width = 1.3.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    FoulPurpleBright.copy(alpha = 0.85f),
                    FoulTeamAwayCyan.copy(alpha = 0.45f),
                    FoulPurplePrimary.copy(alpha = 0.7f)
                )
            )
        ),
        shadowElevation = 24.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("full_screen_auth_form_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            FoulPurpleDeep.copy(alpha = 0.42f),
                            Color(0xFF100C1D),
                            Color(0xFF080610)
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            // 1. Top Header Row with FOUL TV Logo & Back Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (canNavigateBack) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                .testTag("auth_form_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = FoulBlackBg,
                        border = BorderStroke(1.dp, FoulPurpleBright),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_foul_tv_launcher),
                            contentDescription = "شعار FOUL TV",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column {
                        Text(
                            text = if (activeTab == AuthFormTab.SIGN_UP) "إنشاء حساب جديد" else "تسجيل الدخول",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "منظومة حسابات FOUL TV السحابية",
                            color = FoulTextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = FoulPurplePrimary.copy(alpha = 0.22f),
                    border = BorderStroke(1.dp, FoulPurpleBright.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = "FOUL ID",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // 2. Segmented Tab Switcher: إنشاء حساب جديد | تسجيل الدخول
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0xFF090612))
                    .border(1.dp, FoulDivider, RoundedCornerShape(15.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AuthTabButton(
                    title = "إنشاء حساب جديد",
                    selected = activeTab == AuthFormTab.SIGN_UP,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("auth_tab_sign_up"),
                    onClick = {
                        onClearError()
                        activeTab = AuthFormTab.SIGN_UP
                    }
                )
                AuthTabButton(
                    title = "تسجيل الدخول",
                    selected = activeTab == AuthFormTab.SIGN_IN,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("auth_tab_sign_in"),
                    onClick = {
                        onClearError()
                        activeTab = AuthFormTab.SIGN_IN
                    }
                )
            }

            // Error Banner if any
            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = FoulLiveRed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, FoulLiveRed.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFF8A80),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF8A80),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 3. Form Input Fields
            if (activeTab == AuthFormTab.SIGN_UP) {
                // Personal Name (الاسم الشخصي)
                OutlinedTextField(
                    value = personalName,
                    onValueChange = {
                        onClearError()
                        personalName = it
                    },
                    label = { Text("الاسم الشخصي الكامل") },
                    placeholder = { Text("مثال: محمد العراقي") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = FoulPurpleBright
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    colors = foulAuthTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("signup_personal_name_input")
                )

                // Username (اسم المستخدم الخاص به)
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        onClearError()
                        username = it
                    },
                    label = { Text("اسم المستخدم الخاص بك (Username)") },
                    placeholder = { Text("مثال: mohammed_99") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AlternateEmail,
                            contentDescription = null,
                            tint = FoulTeamAwayCyan
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    colors = foulAuthTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("signup_username_input")
                )
            }

            // Email (البريد الإلكتروني)
            OutlinedTextField(
                value = email,
                onValueChange = {
                    onClearError()
                    email = it
                },
                label = { Text("البريد الإلكتروني") },
                placeholder = { Text("example@gmail.com") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = FoulPurpleBright
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = foulAuthTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_email_input")
            )

            // Password (كلمة المرور)
            OutlinedTextField(
                value = password,
                onValueChange = {
                    onClearError()
                    password = it
                },
                label = { Text("كلمة المرور (6 أحرف أو أرقام فأكثر)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = FoulPurpleBright
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "إظهار/إخفاء كلمة المرور",
                            tint = FoulTextSecondary
                        )
                    }
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = foulAuthTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_password_input")
            )

            // Avatar selector on Sign Up
            if (activeTab == AuthFormTab.SIGN_UP) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "اختر صورتك الرمزية:",
                        color = FoulTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = SocialFirebaseRepository.getAvatarById(selectedAvatarId).nameAr,
                        color = FoulPurpleGlow,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(SocialFirebaseRepository.APP_AVATARS.take(8), key = { it.id }) { av ->
                        AvatarMiniChoiceChip(
                            avatar = av,
                            isSelected = av.id == selectedAvatarId,
                            onClick = { selectedAvatarId = av.id }
                        )
                    }
                }
            }

            // 4. Primary Email Submit Button
            Surface(
                onClick = {
                    if (!isLoading) {
                        if (activeTab == AuthFormTab.SIGN_UP) {
                            onSubmitSignUp(personalName, username, email, password, selectedAvatarId)
                        } else {
                            onSubmitSignIn(email, password)
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(FoulPurpleGlow, FoulPurplePrimary)
                    )
                ),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("auth_submit_primary_button")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF8B5CF6),
                                    FoulPurplePrimary,
                                    Color(0xFF5B21B6)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            text = if (activeTab == AuthFormTab.SIGN_UP) {
                                "إنشاء الحساب والبدء فوراً"
                            } else {
                                "تسجيل الدخول والبدء فوراً"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // 5. Divider Before Bottom Google Sign-In Section ("يضهر جوجل تحت")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(FoulDivider)
                )
                Text(
                    text = "أو المتابعة السريعة بحسابات جهازك (بدون كلمة مرور)",
                    color = Color(0xFFD6CEE8),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(FoulDivider)
                )
            }

            // 6. BOTTOM GOOGLE SIGN-IN SECTION ("يضهر جوجل تحت + تظهر الحسابات بشكل البريدات إلي في الأجهزة")
            Surface(
                onClick = onOpenGoogleAccountPicker,
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF18132B),
                border = BorderStroke(
                    width = 1.2.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF4285F4).copy(alpha = 0.85f),
                            Color(0xFF34A853).copy(alpha = 0.70f),
                            Color(0xFFFBBC05).copy(alpha = 0.70f),
                            Color(0xFFEA4335).copy(alpha = 0.85f)
                        )
                    )
                ),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_google_gmail_button")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White,
                                shadowElevation = 4.dp,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    GoogleGLogoCanvas(modifier = Modifier.size(21.dp))
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "المتابعة باستخدام Google Gmail",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "اختر بريدك المسجل في الجهاز مباشرة بنقرة واحدة",
                                    color = FoulTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "اختر الحساب",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    // If device already has discovered/saved Google accounts, display them right inside the bottom Google card as 1-tap email pills!
                    if (deviceGoogleAccounts.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "الحسابات المكتشفة على هذا الجهاز (اضغط للدخول فوراً):",
                                color = Color(0xFF90CAF9),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            deviceGoogleAccounts.take(3).forEach { acc ->
                                DeviceGoogleEmailOneTapRow(
                                    account = acc,
                                    onClick = { onQuickSelectGoogleAccount(acc) }
                                )
                            }
                        }
                    }
                }
            }

            // 7. Bottom Skip Option Link
            Surface(
                onClick = onSkipToAppClick,
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_form_skip_link_button")
            ) {
                Text(
                    text = "تخطي الآن والبدء بدون حساب (مع تقييد النشر ونسخ الروابط)",
                    color = FoulTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * 1-Tap Device Google Account Row (Displays Avatar + Display Name + Email without requiring any password)
 */
@Composable
private fun DeviceGoogleEmailOneTapRow(
    account: DeviceGoogleAccountItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = Color(0xFF221B3A),
        border = BorderStroke(1.dp, Color(0xFF4285F4).copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_google_account_row_${account.email}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF4285F4), Color(0xFF8B5CF6))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = account.displayName.take(1).uppercase().ifBlank { "G" },
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName.ifBlank { account.email.substringBefore("@") },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = account.email,
                        color = Color(0xFF90CAF9),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = FoulLiveGreen.copy(alpha = 0.16f),
                border = BorderStroke(0.8.dp, FoulLiveGreen.copy(alpha = 0.55f))
            ) {
                Text(
                    text = "دخول فوري",
                    color = FoulLiveGreen,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Official-Style Google Device Accounts Picker Bottom Sheet
 * ("تظهر الحسابات بشكل البريدات إلي في الأجهزة وإمكانية الدخول وليس يكتب البريد وكلمة مرور")
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoogleDeviceAccountsBottomSheet(
    isLoading: Boolean,
    deviceAccounts: List<DeviceGoogleAccountItem>,
    onDismiss: () -> Unit,
    onLaunchAndroidSystemPicker: () -> Unit,
    onSelectGoogleAccount: (email: String, displayName: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddNewAccountInline by remember { mutableStateOf(false) }
    var newAccountEmail by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141024),
        scrimColor = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("google_device_accounts_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Google Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            GoogleGLogoCanvas(modifier = Modifier.size(19.dp))
                        }
                    }
                    Column {
                        Text(
                            text = "اختر حساب Google للمتابعة",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "تسجيل الدخول السريع إلى FOUL TV بدون كلمة مرور",
                            color = FoulTextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(FoulDivider)
            )

            // 1. Native Android OS Account Picker Button (Opens the system Google account selector dialog)
            Surface(
                onClick = onLaunchAndroidSystemPicker,
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1D243B),
                border = BorderStroke(1.2.dp, Color(0xFF4285F4).copy(alpha = 0.75f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("google_sheet_system_picker_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4285F4).copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = Color(0xFF82B1FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "اختيار من حسابات Google المسجلة في النظام",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = "يفتح نافذة حسابات أندرويد لاختيار بريدك بنقرة واحدة",
                                color = Color(0xFF90CAF9),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }

            // 2. List of Google Accounts on this Device
            if (deviceAccounts.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "البريدات والحسابات المتوفرة في الجهاز:",
                        color = FoulTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )

                    deviceAccounts.forEach { acc ->
                        DeviceGoogleEmailOneTapRow(
                            account = acc,
                            onClick = {
                                onSelectGoogleAccount(acc.email, acc.displayName)
                            }
                        )
                    }
                }
            }

            // 3. Add / Use Another Google Account on Device (Email only, NO password!)
            if (!showAddNewAccountInline) {
                Surface(
                    onClick = { showAddNewAccountInline = true },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("google_sheet_use_another_account_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = FoulPurpleGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "إضافة أو استخدام بريد Google آخر في الجهاز (بدون كلمة مرور)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1B1530),
                    border = BorderStroke(1.dp, FoulPurpleBright.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "أدخل عنوان بريد Google فقط (بدون كلمة مرور) لإضافته لقائمة حسابات جهازك والدخول فوراً:",
                            color = Color(0xFFD6CEE8),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        if (!localError.isNullOrBlank()) {
                            Text(
                                text = localError!!,
                                color = Color(0xFFFF8A80),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedTextField(
                            value = newAccountEmail,
                            onValueChange = {
                                localError = null
                                newAccountEmail = it
                            },
                            label = { Text("بريد Google Gmail") },
                            placeholder = { Text("name@gmail.com") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = FoulPurpleBright
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp),
                            colors = foulAuthTextFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("google_sheet_quick_email_input")
                        )

                        Button(
                            onClick = {
                                val raw = newAccountEmail.trim().lowercase()
                                val normalized = if (raw.isNotBlank() && !raw.contains("@")) {
                                    "$raw@gmail.com"
                                } else raw

                                if (normalized.isBlank() || !normalized.contains("@")) {
                                    localError = "يرجى كتابة بريد Gmail صحيح"
                                } else {
                                    val derivedName = normalized.substringBefore("@")
                                        .replace(".", " ")
                                        .replace("_", " ")
                                        .trim()
                                    onSelectGoogleAccount(normalized, derivedName)
                                }
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("google_sheet_quick_continue_button")
                        ) {
                            Text(
                                text = "متابعة بهذا الحساب فوراً",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/**
 * STEP 3: Mandatory Post-Google-Login Profile Completion Screen
 * Requires Personal Name (`personalName`) and Username (`username`) after Google Gmail selection
 */
@Composable
private fun LuxuryGoogleCompleteProfileCard(
    googleEmail: String,
    initialPersonalName: String,
    isLoading: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onSubmitProfile: (personalName: String, username: String, avatarId: String) -> Unit
) {
    var personalName by remember(initialPersonalName) { mutableStateOf(initialPersonalName) }
    var username by remember(googleEmail) {
        val suggested = googleEmail.substringBefore("@").replace(Regex("[^a-zA-Z0-9_]"), "_")
        mutableStateOf(suggested)
    }
    var selectedAvatarId by remember {
        mutableStateOf(SocialFirebaseRepository.APP_AVATARS.first().id)
    }

    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFF100C1D).copy(alpha = 0.97f),
        border = BorderStroke(
            width = 1.4.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    FoulLiveGreen.copy(alpha = 0.85f),
                    FoulPurpleBright.copy(alpha = 0.7f),
                    FoulTeamAwayCyan.copy(alpha = 0.6f)
                )
            )
        ),
        shadowElevation = 24.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("google_complete_profile_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.size(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GoogleGLogoCanvas(modifier = Modifier.size(30.dp))
                }
            }

            Text(
                text = "إكمال بيانات حسابك الشخصي",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 21.sp,
                textAlign = TextAlign.Center
            )

            // Selected Google Email Badge
            Surface(
                shape = RoundedCornerShape(50),
                color = FoulLiveGreen.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, FoulLiveGreen.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = FoulLiveGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = googleEmail.ifBlank { "تم اختيار حساب Google بنجاح" },
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Text(
                text = "خطوة أخيرة واحدة: أضف اسمك الشخصي واسم المستخدم الخاص بك ليظهر على حسابك وتتمكن من نشر الريلز ونسخ الروابط فوراً.",
                color = FoulTextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FoulLiveRed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, FoulLiveRed.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            OutlinedTextField(
                value = personalName,
                onValueChange = {
                    onClearError()
                    personalName = it
                },
                label = { Text("الاسم الشخصي الكامل") },
                placeholder = { Text("أدخل اسمك الشخصي") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = FoulPurpleBright
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = foulAuthTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("google_complete_personal_name_input")
            )

            OutlinedTextField(
                value = username,
                onValueChange = {
                    onClearError()
                    username = it
                },
                label = { Text("اسم المستخدم الخاص بك (Username)") },
                placeholder = { Text("مثال: foul_star") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AlternateEmail,
                        contentDescription = null,
                        tint = FoulTeamAwayCyan
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(15.dp),
                colors = foulAuthTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("google_complete_username_input")
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SocialFirebaseRepository.APP_AVATARS.take(8), key = { it.id }) { av ->
                    AvatarMiniChoiceChip(
                        avatar = av,
                        isSelected = av.id == selectedAvatarId,
                        onClick = { selectedAvatarId = av.id }
                    )
                }
            }

            Button(
                onClick = {
                    onSubmitProfile(personalName, username, selectedAvatarId)
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_complete_submit_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        text = "حفظ البيانات والمباشرة فوراً",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * Luxury Skip Restriction Confirmation Dialog
 * Shown whenever the user clicks "تخطي والبدء فوراً"
 */
@Composable
private fun LuxurySkipRestrictionDialog(
    onDismiss: () -> Unit,
    onCreateAccountNow: () -> Unit,
    onConfirmSkipAndStart: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF140E24),
            border = BorderStroke(
                width = 1.4.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        FoulGoldStar.copy(alpha = 0.9f),
                        FoulPurpleBright.copy(alpha = 0.7f)
                    )
                )
            ),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
                .testTag("welcome_skip_warning_box")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF28182E),
                                Color(0xFF140E24),
                                Color(0xFF0C0816)
                            )
                        )
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Glowing Golden Warning Emblem
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(FoulGoldStar.copy(alpha = 0.16f))
                        .border(1.5.dp, FoulGoldStar.copy(alpha = 0.75f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = FoulGoldStar,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "ملاحظة هامة قبل التخطي والبدء",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 19.sp,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "ملاحظة: إذا ضغطت تخطي فلا يمكنك نشر ريلزات وكذلك لا يمكنك نسخ أي رابط فيديو أو رابط قنوات أو من المكتبة إلا بعد إكمال المطلوب وإنشاء حساب.",
                    color = Color(0xFFE5DFF2),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )

                // Structured Locked Capabilities Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, FoulGoldStar.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    LockedFeatureBadgeRow("نشر مقاطع الريلز (Reels) مقيد حتى إنشاء حساب")
                    LockedFeatureBadgeRow("نسخ روابط الفيديوهات والقنوات المباشرة مقيد")
                    LockedFeatureBadgeRow("نسخ روابط الأفلام والمسلسلات من المكتبة مقيد")
                }

                // Button 1: أريد إنشاء حساب الآن
                Button(
                    onClick = onCreateAccountNow,
                    colors = ButtonDefaults.buttonColors(containerColor = FoulPurplePrimary),
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("warning_want_create_account_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "أريد إنشاء حساب الآن",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.5.sp
                    )
                }

                // Button 2: لا أريد ذلك، ابدأ الآن
                Surface(
                    onClick = onConfirmSkipAndStart,
                    shape = RoundedCornerShape(15.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, FoulGoldStar.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("warning_confirm_skip_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "لا أريد ذلك، ابدأ الآن",
                            color = FoulGoldStar,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedFeatureBadgeRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = FoulGoldStar,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AuthTabButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) FoulPurplePrimary else Color.Transparent,
        border = if (selected) {
            BorderStroke(1.dp, FoulPurpleGlow.copy(alpha = 0.7f))
        } else null,
        modifier = modifier.height(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title,
                color = if (selected) Color.White else FoulTextSecondary,
                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
        }
    }
}

@Composable
private fun AvatarMiniChoiceChip(
    avatar: AppAvatarSpec,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(13.dp),
        color = if (isSelected) Color(avatar.primaryHex) else FoulCardDark,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) Color(avatar.accentHex) else FoulDivider
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(avatar.accentHex)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatar.nameAr.take(1),
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp
                )
            }
            Text(
                text = avatar.nameAr,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GoogleGLogoCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        // Red top-left arc
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 195f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Yellow bottom-left arc
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 125f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Green bottom-right arc
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 35f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Blue right arc + horizontal bar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.50f, size.height * 0.50f),
            end = Offset(size.width - inset * 0.4f, size.height * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

@Composable
private fun foulAuthTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF0A0714),
    unfocusedContainerColor = Color(0xFF0D0A18),
    focusedBorderColor = FoulPurpleBright,
    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = FoulPurpleGlow,
    unfocusedLabelColor = FoulTextSecondary
)
