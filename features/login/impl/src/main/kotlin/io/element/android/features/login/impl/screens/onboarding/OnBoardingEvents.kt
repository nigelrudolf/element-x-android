/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.login.impl.screens.onboarding

sealed interface OnBoardingEvents {
    data class OnSignIn(
        val defaultAccountProvider: String
    ) : OnBoardingEvents

    data object OnVersionClick : OnBoardingEvents
    data object ClearError : OnBoardingEvents

    /** Update the Matrix ID text (without @ prefix). */
    data class SetMatrixId(val matrixId: String) : OnBoardingEvents

    /** Update the password text. */
    data class SetPassword(val password: String) : OnBoardingEvents

    /** Discover the server from the entered Matrix ID and sign in. */
    data object DiscoverAndSignIn : OnBoardingEvents

    /** Submit password login after the password field has been revealed. */
    data object SubmitPassword : OnBoardingEvents

    /** Clear the direct login error. */
    data object ClearLoginError : OnBoardingEvents

    /** Clear pending OIDC details after navigation has been triggered. */
    data object ClearPendingOidcDetails : OnBoardingEvents
}
