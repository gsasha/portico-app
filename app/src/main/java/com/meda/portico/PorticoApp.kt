package com.meda.portico

import android.app.Application
import android.content.Context

/**
 * Portico's Application (PR-E). Runs before any component of the process, including a
 * messaging service the OS starts for a push with no Activity: it boots Firebase from Settings
 * and starts the [AppGraph] (socket, registration, the shared call store).
 */
class PorticoApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.installCrashHandler() // DEV-762 A2, before anything else can throw
        graph.beginExitTracking()?.let { registerActivityLifecycleCallbacks(it) } // DEV-762 A3
        registerActivityLifecycleCallbacks(com.meda.portico.call.CallBarHost(graph)) // APP-02: the call bar under every non-call screen
        registerActivityLifecycleCallbacks(com.meda.portico.panel.PanelFrontHost(graph)) // DEV-807 slice 2: idle / return to Fully / Close / touch reset (Panel mode only)
        graph.start()
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as PorticoApp).graph
    }
}
