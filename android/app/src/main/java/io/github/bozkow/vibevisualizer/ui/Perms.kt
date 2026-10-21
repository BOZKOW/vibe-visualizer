package io.github.bozkow.vibevisualizer.ui

/** Permission prompts and system settings pages (wired up by MainActivity). */
object Perms {
    var mic: (done: (Boolean) -> Unit) -> Unit = { it(false) }
    var notificationAccess: () -> Unit = {}
    fun mic(done: (Boolean) -> Unit) = mic.invoke(done)
}
