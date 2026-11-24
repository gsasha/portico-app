package com.meda.portico.core.ring

/**
 * PR-G (ship checklist: a refused CallStyle must be visible). The ring is posted as a CallStyle
 * incoming call; if Android refuses it (`notify()` throws, it never drops silently), a plain
 * notification with Open / Stop follows so the ring is not lost. Until PR-G that fallback was
 * only logged. Now its outcome is handed to [post]'s `record`, which the app persists so Setup
 * can say "a plain notification is used" ([SetupStatus.ringStyleLines]).
 *
 * Order: the ring first, the bookkeeping after. A record that fails is swallowed: it must never
 * cost the ring. Only the exception's class name is kept, never its message.
 */
object CallStyleFallback {

    /**
     * Runs [callStyle]; if it throws, runs [plain]. Then calls [record] with null (CallStyle was
     * accepted, which clears an older refusal) or the refusal's class name.
     *
     * @return null if CallStyle was accepted, else the refusal's class name.
     * @throws Exception whatever [plain] throws (after the refusal is recorded).
     */
    fun post(callStyle: () -> Unit, plain: () -> Unit, record: (String?) -> Unit): String? {
        val refusedBy = try {
            callStyle()
            null
        } catch (e: Exception) {
            e.javaClass.simpleName.ifBlank { "Exception" }
        }
        if (refusedBy == null) {
            safely { record(null) }
            return null
        }
        try {
            plain()
        } finally {
            safely { record(refusedBy) }
        }
        return refusedBy
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // the ring matters more than the bookkeeping
        }
    }
}
