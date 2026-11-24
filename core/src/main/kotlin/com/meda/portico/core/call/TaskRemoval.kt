package com.meda.portico.core.call

/**
 * 0.6.82: which task removals mean "the user swiped Portico away" and which are our own doing.
 *
 * The ring window has its own task (taskAffinity) and closes with `finishAndRemoveTask`. Android delivers
 * `Service.onTaskRemoved` to every running service of the package with stopWithTask=false for ANY removed task of the
 * package, so closing the ring window at the press of Answer reached CallService while the freshly begun answer was
 * running and ended it (`app_stopped`). Only a removal of a task that holds a screen the user can swipe away releases the calls.
 */
object TaskRemoval {
    /** [rootClass]: the class name of the removed task's root intent component, or null when unknown (then release, the old behaviour). */
    fun releasesCalls(rootClass: String?, ownWindowClasses: Set<String>): Boolean =
        rootClass == null || rootClass !in ownWindowClasses
}
