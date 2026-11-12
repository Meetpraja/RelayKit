package com.relaykit.demo.core.navigation

/** Top-level destinations in the developer workspace. */
enum class Destination(val title: String, val caption: String) {
    WORKSPACE("Workspace", "Start here"),
    REQUEST("Request Builder", "Compose an API request"),
    HISTORY("History", "Saved request activity"),
    LOGS("Logs", "Diagnostics"),
    GENERATOR("Code Generator", "Generate RelayKit source"),
    SETTINGS("Settings", "App preferences"),
}
