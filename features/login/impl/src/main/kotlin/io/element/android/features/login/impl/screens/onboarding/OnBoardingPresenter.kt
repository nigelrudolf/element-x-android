/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.appconfig.OnBoardingConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.enterprise.api.canConnectToAnyHomeserver
import io.element.android.features.login.impl.accesscontrol.DefaultAccountProviderAccessControl
import io.element.android.features.login.impl.accountprovider.AccountProviderDataSource
import io.element.android.features.login.impl.login.LoginHelper
import io.element.android.features.login.impl.screens.onboarding.classic.LoginWithClassicState
import io.element.android.features.rageshake.api.RageshakeFeatureAvailability
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.matrix.api.auth.MatrixAuthenticationService
import io.element.android.libraries.matrix.api.auth.OidcDetails
import io.element.android.libraries.matrix.api.auth.OidcPrompt
import io.element.android.libraries.matrix.api.core.MatrixPatterns
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.sessionstorage.api.SessionStore
import io.element.android.libraries.ui.utils.MultipleTapToUnlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

private val loggerTag = LoggerTag("OnBoardingPresenter")

@AssistedInject
class OnBoardingPresenter(
    @Assisted private val params: OnBoardingNode.Params,
    private val buildMeta: BuildMeta,
    private val enterpriseService: EnterpriseService,
    private val defaultAccountProviderAccessControl: DefaultAccountProviderAccessControl,
    private val rageshakeFeatureAvailability: RageshakeFeatureAvailability,
    private val loginHelper: LoginHelper,
    private val authenticationService: MatrixAuthenticationService,
    private val onBoardingLogoResIdProvider: OnBoardingLogoResIdProvider,
    private val sessionStore: SessionStore,
    private val accountProviderDataSource: AccountProviderDataSource,
    private val loginWithClassicPresenter: Presenter<LoginWithClassicState>,
) : Presenter<OnBoardingState> {
    @AssistedFactory
    interface Factory {
        fun create(
            params: OnBoardingNode.Params,
        ): OnBoardingPresenter
    }

    private val multipleTapToUnlock = MultipleTapToUnlock()

    @Composable
    override fun present(): OnBoardingState {
        val localCoroutineScope = rememberCoroutineScope()
        val forcedAccountProvider = remember {
            // If defaultHomeserverList() returns a singleton list, this is the default account provider.
            // In this case, the user can sign in using this homeserver, or use QrCode login
            enterpriseService.defaultHomeserverList().singleOrNull()
        }
        val canConnectToAnyHomeserver = remember {
            enterpriseService.canConnectToAnyHomeserver()
        }
        val mustChooseAccountProvider = remember {
            !canConnectToAnyHomeserver && enterpriseService.defaultHomeserverList().size > 1
        }
        val linkAccountProvider by produceState<String?>(initialValue = null) {
            // Account provider from the link, if allowed by the enterprise service
            value = params.accountProvider?.takeIf {
                try {
                    defaultAccountProviderAccessControl.assertIsAllowedToConnectToAccountProvider(it, it)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        val defaultAccountProvider = remember(linkAccountProvider) {
            // If there is a forced account provider, this is the default account provider
            // Else use the account provider passed in the params if any and if allowed
            forcedAccountProvider ?: linkAccountProvider
        }
        val canLoginWithQrCode by produceState(initialValue = false, linkAccountProvider) {
            value = linkAccountProvider == null
        }
        val canReportBug by remember { rageshakeFeatureAvailability.isAvailable() }.collectAsState(false)
        var showReportBug by rememberSaveable { mutableStateOf(false) }
        val onBoardingLogoResId = remember {
            onBoardingLogoResIdProvider.get()
        }
        val isAddingAccount by produceState(initialValue = false) {
            // We are adding an account if there is at least one session already stored
            value = sessionStore.numberOfSessions() > 0
        }

        val loginMode by loginHelper.collectLoginMode()

        val loginWithClassicState = loginWithClassicPresenter.present()

        // State for Matrix ID direct sign-in flow
        var matrixId by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var showPasswordField by rememberSaveable { mutableStateOf(false) }
        var isDiscovering by remember { mutableStateOf(false) }
        val loginAction: MutableState<AsyncData<SessionId>> = remember { mutableStateOf(AsyncData.Uninitialized) }
        var pendingOidcDetails by remember { mutableStateOf<OidcDetails?>(null) }

        fun handleEvent(event: OnBoardingEvents) {
            when (event) {
                is OnBoardingEvents.OnSignIn -> localCoroutineScope.launch {
                    // Ensure that the current account provider is set
                    accountProviderDataSource.setUrl(event.defaultAccountProvider)
                    loginHelper.submit(
                        isAccountCreation = false,
                        homeserverUrl = event.defaultAccountProvider,
                        loginHint = params.loginHint?.takeIf { forcedAccountProvider == null },
                    )
                }
                OnBoardingEvents.ClearError -> loginHelper.clearError()
                OnBoardingEvents.OnVersionClick -> {
                    if (canReportBug) {
                        if (multipleTapToUnlock.unlock(localCoroutineScope)) {
                            showReportBug = true
                        }
                    }
                }
                is OnBoardingEvents.SetMatrixId -> {
                    matrixId = event.matrixId
                    // Reset password field when user changes the Matrix ID after discovery
                    if (showPasswordField) {
                        showPasswordField = false
                        password = ""
                    }
                }
                is OnBoardingEvents.SetPassword -> {
                    password = event.password
                }
                OnBoardingEvents.DiscoverAndSignIn -> {
                    localCoroutineScope.discoverServerAndSignIn(
                        matrixId = matrixId,
                        onShowPasswordField = { showPasswordField = true },
                        onIsDiscovering = { isDiscovering = it },
                        onOidcDetails = { pendingOidcDetails = it },
                    )
                }
                OnBoardingEvents.SubmitPassword -> {
                    localCoroutineScope.loginWithPassword(
                        matrixId = matrixId,
                        password = password,
                        loginAction = loginAction,
                        onIsDiscovering = { isDiscovering = it },
                    )
                }
                OnBoardingEvents.ClearLoginError -> {
                    loginAction.value = AsyncData.Uninitialized
                }
                OnBoardingEvents.ClearPendingOidcDetails -> {
                    pendingOidcDetails = null
                }
            }
        }

        return OnBoardingState(
            isAddingAccount = isAddingAccount,
            productionApplicationName = buildMeta.productionApplicationName,
            defaultAccountProvider = defaultAccountProvider,
            mustChooseAccountProvider = mustChooseAccountProvider,
            canLoginWithQrCode = canLoginWithQrCode,
            canCreateAccount = defaultAccountProvider == null && canConnectToAnyHomeserver && OnBoardingConfig.CAN_CREATE_ACCOUNT,
            canReportBug = canReportBug && showReportBug,
            loginMode = loginMode,
            version = buildMeta.versionName,
            onBoardingLogoResId = onBoardingLogoResId,
            loginWithClassicState = loginWithClassicState,
            matrixId = matrixId,
            password = password,
            showPasswordField = showPasswordField,
            isLoading = isDiscovering,
            loginAction = loginAction.value,
            pendingOidcDetails = pendingOidcDetails,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.discoverServerAndSignIn(
        matrixId: String,
        onShowPasswordField: () -> Unit,
        onIsDiscovering: (Boolean) -> Unit,
        onOidcDetails: (OidcDetails) -> Unit,
    ) = launch {
        val fullMatrixId = "@$matrixId"

        if (!MatrixPatterns.isUserId(fullMatrixId)) {
            Timber.tag(loggerTag.value).w("Invalid Matrix ID: $fullMatrixId")
            loginHelper.clearError()
            return@launch
        }

        val homeserverDomain = fullMatrixId.split(":").drop(1).joinToString(":")

        onIsDiscovering(true)
        authenticationService.setHomeserver(homeserverDomain)
            .onSuccess { homeServerDetails ->
                if (homeServerDetails.supportsOidcLogin) {
                    // OIDC server — keep spinner going while we get the URL.
                    val localpart = fullMatrixId.removePrefix("@").split(":")[0]
                    authenticationService.getOidcUrl(prompt = OidcPrompt.Login, loginHint = localpart)
                        .onSuccess { oidcDetails ->
                            // Navigate directly to OIDC — spinner stays until the screen changes.
                            accountProviderDataSource.setUrl(homeserverDomain)
                            onOidcDetails(oidcDetails)
                        }
                        .onFailure {
                            onIsDiscovering(false)
                            Timber.tag(loggerTag.value).e(it, "Failed to get OIDC URL")
                        }
                } else if (homeServerDetails.supportsPasswordLogin) {
                    onIsDiscovering(false)
                    onShowPasswordField()
                } else {
                    onIsDiscovering(false)
                    Timber.tag(loggerTag.value).w("Server does not support OIDC or password login")
                }
            }
            .onFailure { error ->
                onIsDiscovering(false)
                Timber.tag(loggerTag.value).e(error, "Server discovery failed")
            }
    }

    private fun CoroutineScope.loginWithPassword(
        matrixId: String,
        password: String,
        loginAction: MutableState<AsyncData<SessionId>>,
        onIsDiscovering: (Boolean) -> Unit,
    ) = launch {
        val fullMatrixId = "@$matrixId"
        Timber.tag(loggerTag.value).i("Starting login with password from start screen")
        onIsDiscovering(true)
        loginAction.value = AsyncData.Loading()
        authenticationService.login(fullMatrixId, password)
            .onSuccess { sessionId ->
                onIsDiscovering(false)
                loginAction.value = AsyncData.Success(sessionId)
            }
            .onFailure { error ->
                onIsDiscovering(false)
                loginAction.value = AsyncData.Failure(error)
                Timber.tag(loggerTag.value).e(error, "Login with password failed")
            }
    }
}
