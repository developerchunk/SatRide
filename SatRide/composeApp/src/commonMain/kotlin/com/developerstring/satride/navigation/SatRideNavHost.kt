package com.developerstring.satride.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.developerstring.satride.core.analytics.Analytics
import com.developerstring.satride.core.analytics.CrashReporter
import com.developerstring.satride.core.common.SkyGuideRequests
import com.developerstring.satride.core.datastore.SettingsRepository
import com.developerstring.satride.core.designsystem.component.PhasePlaceholder
import com.developerstring.satride.core.designsystem.theme.MotionSpec
import com.developerstring.satride.core.web.LegalPage
import com.developerstring.satride.core.web.WebPageScreen
import com.developerstring.satride.core.web.rememberUriOpener
import com.developerstring.satride.di.APP_BUILD_DATE
import com.developerstring.satride.di.APP_VERSION
import com.developerstring.satride.feature.arsky.SkyScreen
import com.developerstring.satride.feature.auth.CreateProfileScreen
import com.developerstring.satride.feature.auth.SignInScreen
import com.developerstring.satride.feature.auth.TesterSignInScreen
import com.developerstring.satride.feature.decode.DecodeScreen
import com.developerstring.satride.feature.onboarding.OnboardingScreen
import com.developerstring.satride.feature.onboarding.PermissionsScreen
import com.developerstring.satride.feature.passes.PassesScreen
import com.developerstring.satride.feature.passes.detail.PassDetailScreen
import com.developerstring.satride.feature.play.PlayScreen
import com.developerstring.satride.feature.play.multiplay.ui.MultiPlayScreen
import com.developerstring.satride.feature.profile.ProfileScreen
import com.developerstring.satride.feature.radio.RadioScreen
import com.developerstring.satride.feature.satellites.SatellitesScreen
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailScreen
import com.developerstring.satride.feature.satellites.detail.SatelliteDetailSheet
import com.developerstring.satride.feature.satellites.import.TleImportScreen
import com.developerstring.satride.feature.satellites.tracking.TrackingScreen
import com.developerstring.satride.feature.settings.AboutScreen
import com.developerstring.satride.feature.settings.SettingsScreen
import com.developerstring.satride.feature.startup.SplashScreen
import com.developerstring.satride.ui.NativeTabBridge
import com.developerstring.satride.ui.RootScaffold
import com.developerstring.satride.ui.walkthrough.WalkthroughHost
import com.developerstring.satride.ui.walkthrough.rememberWalkthroughState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SatRideNavHost(modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // A notification tap, a `satride://` link or [Open in Sky] all arrive the same way: as a request
    // on the guide bus. This watches it only to *be on the sky tab*; the sky view does the guiding.
    // Nothing clears the request — both readers act on it independently and track what they have
    // already handled, because whichever one cleared it first left the other with nothing.
    // Navigating to a tab that is already showing is a no-op, so re-running this is harmless.
    val guideRequests: SkyGuideRequests = koinInject()
    val pendingGuide by guideRequests.pending.collectAsStateWithLifecycle()

    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // Where the first run goes once the account step is behind it: the permission screen if it has
    // never been shown, otherwise straight to the catalog. Routing is the shell's job, so the two
    // screens that can end the first run — sign-in and profile creation — both ask here rather
    // than each deciding for itself and drifting apart.
    fun leaveFirstRun(from: SatRideRoute) {
        scope.launch {
            val destination: SatRideRoute = if (settings.current().permissionsPrimed) {
                SatRideRoute.Satellites
            } else {
                SatRideRoute.Permissions
            }
            navController.navigate(destination) { popUpTo(from) { inclusive = true } }
        }
    }

    LaunchedEffect(pendingGuide?.sequence) {
        if (pendingGuide == null) return@LaunchedEffect
        // Anything on top of the tabs — a pass detail, the tracking screen — is dismissed: the user asked to
        // look at the sky, and leaving a sheet over it would hide the thing they asked for.
        navController.popBackStack<SatRideRoute.Sky>(inclusive = false, saveState = false)
        navController.navigateToTab(TopLevelTab.Sky)
    }

    // The bottom bar belongs to the four top-level tabs only; detail and modal surfaces get
    // the full screen.
    val activeTab =
        TopLevelTab.visible.firstOrNull { tab ->
            currentDestination?.hasRoute(tab.route::class) == true
        }

    // Bridge the active tab and tab taps to the native iOS bar. A no-op where there is no native
    // bar: Android/desktop never register an observer and never call `select`. `navigateToTab` is
    // the same path the Compose `FloatingTabBar` uses, so semantics are identical across platforms.
    DisposableEffect(navController) {
        NativeTabBridge.bindSelect { index ->
            TopLevelTab.visible.getOrNull(index)?.let { navController.navigateToTab(it) }
        }
        onDispose { NativeTabBridge.bindSelect { } }
    }
    LaunchedEffect(activeTab) {
        NativeTabBridge.publishActive(activeTab?.let { TopLevelTab.visible.indexOf(it) } ?: -1)
    }

    // Screen tracking, done once here rather than per screen.
    //
    // The route's own name is reported, not a hand-written label: a screen added later is measured
    // automatically, and there is no second list of names to keep level with the graph. The class
    // name survives obfuscation because every route type is kept whole for its serializer — see
    // `proguard-rules.pro`.
    val analytics: Analytics = koinInject()
    val crashReporter: CrashReporter = koinInject()

    LaunchedEffect(currentDestination?.route) {
        val route = currentDestination?.route?.substringAfterLast('.')?.substringBefore('/') ?: return@LaunchedEffect
        analytics.screen(route)
        // The same move as a Crashlytics breadcrumb: if the next frame crashes, the report says
        // which screen the user was on when it did.
        crashReporter.log("screen: $route")
    }

    // The splash and onboarding own the whole screen: they are not tabs, so without this they
    // would inherit the "not a tab" chrome — a top bar with a back button pointing at nothing.
    val immersive = currentDestination?.hasRoute(SatRideRoute.Splash::class) == true ||
        currentDestination?.hasRoute(SatRideRoute.Onboarding::class) == true ||
        // The permission screen is the last beat of the first run and is staged like the rest of
        // it: full-bleed, with its own Skip. A top bar here would offer a back button to a
        // sign-in screen that has already been popped.
        currentDestination?.hasRoute(SatRideRoute.Permissions::class) == true ||
        // The login moment owns the whole screen like the splash does; a top bar over the orb
        // constellation would break the scene, and the system back gesture still works.
        currentDestination?.hasRoute(SatRideRoute.SignIn::class) == true ||
        // The arena is landscape and full-bleed with its own exit; a top bar would sit over the
        // HUD and the floating tab pill over the controls.
        currentDestination?.hasRoute(SatRideRoute.MultiPlay::class) == true

    // The tour can only run where there is a tab bar to point at, which is exactly the condition
    // for `activeTab` being non-null. It therefore never opens over the splash, the onboarding
    // pager, the sign-in screen or a detail surface.
    val walkthrough = rememberWalkthroughState(settings = settings, canStart = activeTab != null)

    // Each step is *about* a tab, so opening the step navigates there: the user reads the
    // description while looking at the screen it describes, with the spotlight on the button that
    // would have got them there.
    LaunchedEffect(walkthrough.step) {
        walkthrough.step?.tab?.let { navController.navigateToTab(it) }
    }

    WalkthroughHost(state = walkthrough, modifier = modifier) {
        RootScaffold(
            showChrome = !immersive,
            activeTab = activeTab,
            onTabSelected = { tab -> navController.navigateToTab(tab) },
            onSettingsClick = { navController.navigate(SatRideRoute.Settings) },
            onBack = { navController.popBackStack() },
        ) { contentPadding ->
            NavHost(
                navController = navController,
                startDestination = SatRideRoute.Splash,
                modifier = Modifier.padding(contentPadding),
                // Tabs swap instantly; pushes and pops move.
                //
                // A cross-fade between two *tabs* was the "box cut": a fade means alpha < 1, which
                // forces Compose to promote BOTH destinations into their own offscreen compositing
                // layers and hold them there for the whole animation. For two full-screen surfaces —
                // a several-hundred-row list against a live globe — that is two full-screen buffers
                // being allocated, drawn and blended on the very frame that is already paying for the
                // incoming screen's first composition. The result reads as the screen being cut and
                // re-assembled rather than as a fade.
                //
                // Tabs are siblings, not a hierarchy, so they get no transition at all: the swap is a
                // single cheap frame, and each screen's own content reveal (see `ScreenContent`)
                // supplies the motion. Pushes and pops keep a short slide+fade, because there the
                // motion carries meaning — it says which way you moved — and only one of the two
                // surfaces is a heavyweight tab.
                enterTransition = {
                    if (isTabSwap()) {
                        EnterTransition.None
                    } else {
                        slideInHorizontally(MotionSpec.standard()) { it / 8 } +
                            fadeIn(tween(MotionSpec.STANDARD_MILLIS, easing = MotionSpec.DecelerateEasing))
                    }
                },
                exitTransition = {
                    if (isTabSwap()) {
                        ExitTransition.None
                    } else {
                        slideOutHorizontally(MotionSpec.standard()) { -it / 8 } +
                            fadeOut(tween(MotionSpec.STANDARD_MILLIS, easing = MotionSpec.DecelerateEasing))
                    }
                },
                popEnterTransition = {
                    if (isTabSwap()) {
                        EnterTransition.None
                    } else {
                        slideInHorizontally(MotionSpec.standard()) { -it / 8 } +
                            fadeIn(tween(MotionSpec.STANDARD_MILLIS, easing = MotionSpec.DecelerateEasing))
                    }
                },
                popExitTransition = {
                    if (isTabSwap()) {
                        ExitTransition.None
                    } else {
                        slideOutHorizontally(MotionSpec.standard()) { it / 8 } +
                            fadeOut(tween(MotionSpec.STANDARD_MILLIS, easing = MotionSpec.DecelerateEasing))
                    }
                },
            ) {
                composable<SatRideRoute.Splash> {
                    // First launch goes to the onboarding tour; every later launch lands on the
                    // catalog — the calmest arrival, and the one carrying the first-sync loading
                    // state. Decided here, not in the splash, because routing is the shell's job.
                    SplashScreen(
                        onReady = {
                            scope.launch {
                                val current = settings.current()
                                val destination: SatRideRoute = when {
                                    !current.onboardingComplete -> SatRideRoute.Onboarding

                                    // Someone who installed before the permission screen existed, or
                                    // who was interrupted between sign-in and it, still gets shown it
                                    // once — otherwise it would only ever reach brand-new installs.
                                    !current.permissionsPrimed -> SatRideRoute.Permissions

                                    else -> SatRideRoute.Satellites
                                }
                                // `inclusive = true`: the splash has nothing left to do, so leaving it
                                // on the back stack would make the system back button from the home
                                // screen return to a finished loading screen instead of exiting.
                                navController.navigate(destination) {
                                    popUpTo(SatRideRoute.Splash) { inclusive = true }
                                }
                            }
                        },
                    )
                }

                composable<SatRideRoute.Onboarding> {
                    OnboardingScreen(
                        onDone = {
                            // The tour flows into the login moment; keep the tour off the stack.
                            navController.navigate(SatRideRoute.SignIn) {
                                popUpTo(SatRideRoute.Onboarding) { inclusive = true }
                            }
                        },
                    )
                }

                composable<SatRideRoute.SignIn> {
                    // Two ways to arrive: pushed from the Profile tab (finish = pop back, labelled
                    // "Back"), or as the stack root right after onboarding (finish = hand over to
                    // the catalog, labelled "Skip").
                    val isStackRoot = navController.previousBackStackEntry == null
                    val finish: () -> Unit = {
                        if (isStackRoot) {
                            leaveFirstRun(SatRideRoute.SignIn)
                        } else {
                            navController.popBackStack()
                        }
                    }
                    SignInScreen(
                        onSignedIn = finish,
                        onNeedsProfile = {
                            navController.navigate(SatRideRoute.CreateProfile) {
                                popUpTo(SatRideRoute.SignIn) { inclusive = true }
                            }
                        },
                        onSkip = finish,
                        onOpenLegal = { page ->
                            navController.navigate(SatRideRoute.WebPage(page.url, page.title))
                        },
                        skipLabel = if (isStackRoot) "Skip" else "Back",
                    )
                }

                composable<SatRideRoute.CreateProfile> {
                    CreateProfileScreen(
                        onDone = {
                            // Same duality as SignIn: after onboarding this is the stack root.
                            if (navController.previousBackStackEntry == null) {
                                leaveFirstRun(SatRideRoute.CreateProfile)
                            } else {
                                navController.popBackStack()
                            }
                        },
                    )
                }

                composable<SatRideRoute.Permissions> {
                    PermissionsScreen(
                        onDone = {
                            navController.navigate(SatRideRoute.Satellites) {
                                popUpTo(SatRideRoute.Permissions) { inclusive = true }
                            }
                        },
                    )
                }

                composable<SatRideRoute.Profile> {
                    ProfileScreen(
                        onOpenSignIn = { navController.navigate(SatRideRoute.SignIn) },
                        onOpenEdit = { navController.navigate(SatRideRoute.CreateProfile) },
                        onOpenPage = { page ->
                            navController.navigate(SatRideRoute.WebPage(page.url, page.title))
                        },
                    )
                }

                composable<SatRideRoute.Sky> {
                    SkyScreen(
                        onOpenSearch = { navController.navigateToTab(TopLevelTab.Satellites) },
                        // The app shell supplies the detail sheet, because features must not depend on
                        // features. This is the seam that rule exists to create.
                        satelliteDetail = { noradId, onGuide, onDismiss ->
                            SatelliteDetailSheet(noradId = noradId, onGuide = onGuide, onDismiss = onDismiss)
                        },
                    )
                }
                composable<SatRideRoute.Satellites> {
                    SatellitesScreen(
                        onOpenDetail = { navController.navigate(SatRideRoute.SatelliteDetail(it)) },
                        onOpenImport = { navController.navigate(SatRideRoute.TleImport) },
                        onOpenManageTracking = { navController.navigate(SatRideRoute.Tracking) },
                    )
                }
                composable<SatRideRoute.Play> {
                    PlayScreen(
                        onOpenDetail = { navController.navigate(SatRideRoute.SatelliteDetail(it)) },
                        onOpenMultiPlay = { navController.navigate(SatRideRoute.MultiPlay()) },
                        onOpenSolarSystem = { navController.navigate(SatRideRoute.MultiPlay(mode = "solar")) },
                    )
                }
                composable<SatRideRoute.MultiPlay> { entry ->
                    val route = entry.toRoute<SatRideRoute.MultiPlay>()
                    MultiPlayScreen(onExit = { navController.popBackStack() }, initialMode = route.mode)
                }
                composable<SatRideRoute.Passes> {
                    PassesScreen(
                        onOpenPass = { noradId, aos ->
                            navController.navigate(SatRideRoute.PassDetail(noradId, aos))
                        },
                    )
                }
                composable<SatRideRoute.Radio> { RadioScreen() }

                composable<SatRideRoute.SatelliteDetail> { entry ->
                    val route: SatRideRoute.SatelliteDetail = entry.toRoute()
                    SatelliteDetailScreen(
                        noradId = route.noradId,
                        onOpenPass = { targetNoradId, aos ->
                            navController.navigate(SatRideRoute.PassDetail(targetNoradId, aos))
                        },
                    )
                }
                composable<SatRideRoute.PassDetail> { entry ->
                    val route: SatRideRoute.PassDetail = entry.toRoute()
                    PassDetailScreen(
                        noradId = route.noradId,
                        aosEpochMillis = route.aosEpochMillis,
                        // The screen has already put the request on the guide bus; all that is left is
                        // to be on the sky tab when it is picked up.
                        onOpenSky = { navController.navigateToTab(TopLevelTab.Sky) },
                    )
                }
                composable<SatRideRoute.Decode> { DecodeScreen() }
                composable<SatRideRoute.Gallery> {
                    PhasePlaceholder(
                        title = "Gallery",
                        phase = "P10",
                        description = "Everything you have received, tagged by satellite and pass.",
                    )
                }
                composable<SatRideRoute.Tracking> { TrackingScreen() }
                composable<SatRideRoute.Settings> {
                    SettingsScreen(
                        onOpenTleImport = { navController.navigate(SatRideRoute.TleImport) },
                        onOpenTracking = { navController.navigate(SatRideRoute.Tracking) },
                        onOpenAbout = { navController.navigate(SatRideRoute.About) },
                        onOpenTesterSignIn = { navController.navigate(SatRideRoute.TesterSignIn) },
                    )
                }
                composable<SatRideRoute.TesterSignIn> {
                    TesterSignInScreen(onSignedIn = { navController.popBackStack() })
                }
                composable<SatRideRoute.About> {
                    val openUri = rememberUriOpener()
                    AboutScreen(
                        version = APP_VERSION,
                        buildDate = APP_BUILD_DATE,
                        onOpenPage = { page ->
                            navController.navigate(SatRideRoute.WebPage(page.url, page.title))
                        },
                        onContact = { openUri("mailto:${LegalPage.CONTACT_EMAIL}") },
                    )
                }
                composable<SatRideRoute.WebPage> { entry ->
                    val route: SatRideRoute.WebPage = entry.toRoute()
                    WebPageScreen(url = route.url)
                }
                composable<SatRideRoute.TleImport> {
                    TleImportScreen(
                        onImported = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Tab switching keeps one back-stack entry per tab and restores where the user left off,
 * rather than stacking duplicate copies of a tab.
 *
 * ## Why not `graph.findStartDestination()`
 *
 * The graph's start destination is [SatRideRoute.Splash], and the splash pops *itself* off the back
 * stack with `inclusive = true` the moment it hands over. So by the time any tab is reachable,
 * `findStartDestination()` names a destination that is no longer on the stack — and `popUpTo` a
 * destination that is not on the back stack matches nothing and does nothing.
 *
 * The consequences were the whole of the "tabs feel laggy" report. Nothing was popped, so the back
 * stack grew by one entry per tab tap, forever. `saveState` never ran, so nothing was ever saved;
 * `restoreState` therefore found nothing to restore, so **every tab visit built a fresh
 * `NavBackStackEntry` and with it a fresh `ViewModel`** — re-running the Room queries, reloading
 * the SGP4 field, re-decoding the Earth textures, and starting every `stateIn` chain back at its
 * empty initial value. Meanwhile the abandoned entries kept their view models alive, so N copies
 * of Play's propagation ticker went on burning CPU behind whatever tab was actually on screen.
 *
 * [TabStackRoot] is the destination the tabs actually sit on: every path out of the splash
 * (straight through, or via onboarding and sign-in) lands on Satellites with everything before it
 * popped inclusively, so it is the real root of the tab stack.
 */
private fun NavHostController.navigateToTab(tab: TopLevelTab) {
    navigate(tab.route) {
        popUpTo(TabStackRoot) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The destination every tab is stacked on; see [navigateToTab]. */
private val TabStackRoot: SatRideRoute.Tab = TopLevelTab.Satellites.route

/** True when this destination is one of the five top-level tabs. */
private fun NavDestination.isTopLevelTab(): Boolean = TopLevelTab.visible.any { hasRoute(it.route::class) }

/**
 * True when this transition is one tab replacing another — the case that gets no animation.
 *
 * Both ends are checked, not just the target: arriving on a tab *from* the splash or from a detail
 * screen is a real transition and should move.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwap(): Boolean =
    initialState.destination.isTopLevelTab() && targetState.destination.isTopLevelTab()
