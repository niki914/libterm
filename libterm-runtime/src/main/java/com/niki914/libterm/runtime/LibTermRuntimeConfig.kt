package com.niki914.libterm.runtime

import com.niki914.libterm.AuthorizationMode

class LibTermRuntimeConfig {
    var defaultAuthorizationMode: AuthorizationMode = AuthorizationMode.REQUEST_IF_NEEDED
    var defaultCwd: String? = null
}
