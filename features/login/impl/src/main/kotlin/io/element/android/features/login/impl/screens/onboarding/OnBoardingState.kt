/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.onboarding

import androidx.annotation.DrawableRes
import io.element.android.features.login.impl.login.LoginMode
import io.element.android.features.login.impl.screens.onboarding.classic.LoginWithClassicState
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.auth.OidcDetails
import io.element.android.libraries.matrix.api.core.SessionId

data class OnBoardingState(
    val isAddingAccount: Boolean,
    val productionApplicationName: String,
    val defaultAccountProvider: String?,
    val mustChooseAccountProvider: Boolean,
    val canLoginWithQrCode: Boolean,
    val canCreateAccount: Boolean,
    val canReportBug: Boolean,
    val version: String,
    @DrawableRes
    val onBoardingLogoResId: Int?,
    val loginMode: AsyncData<LoginMode>,
    val loginWithClassicState: LoginWithClassicState,
    /** The Matrix ID entered by the user (without the @ prefix). */
    val matrixId: String,
    /** The password entered by the user. */
    val password: String,
    /** Whether the password field should be shown (server supports password login). */
    val showPasswordField: Boolean,
    /** Whether a server discovery or login is in progress. */
    val isLoading: Boolean,
    /** Login action state for direct password login. */
    val loginAction: AsyncData<SessionId>,
    /** OIDC details ready for navigation (set after server discovery). */
    val pendingOidcDetails: OidcDetails?,
    val eventSink: (OnBoardingEvents) -> Unit,
) {
    val submitEnabled: Boolean
        get() = defaultAccountProvider != null && (loginMode is AsyncData.Uninitialized || loginMode is AsyncData.Loading)

    /** Whether the sign in button can be tapped for the Matrix ID flow. */
    val canSignIn: Boolean
        get() = if (showPasswordField) {
            matrixId.isNotEmpty() && password.isNotEmpty() && !isLoading
        } else {
            matrixId.isNotEmpty() && !isLoading
        }
}
