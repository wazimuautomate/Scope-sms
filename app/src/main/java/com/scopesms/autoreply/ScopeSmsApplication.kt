package com.scopesms.autoreply

import android.app.Application

/**
 * Process-level anchor for Scope SMS.
 *
 * Deliberately empty in Phase 0. It exists now, and is registered in the
 * manifest now, so that later phases have a home to attach to without each
 * one racing to edit `AndroidManifest.xml`:
 *
 *  - Phase 3/4 hang the in-memory rule + template cache here. CLAUDE.md
 *    constraint 5 requires the receiver's decide path to read rules from
 *    memory rather than issuing a Room query per incoming SMS, which means
 *    the cache has to outlive any single Activity and live at process scope.
 *  - Phase 5b initialises WorkManager for the outbound send queue.
 *  - The DI container (manual or Hilt — undecided, see memory.md) is
 *    constructed here.
 *
 * Keep `onCreate` cheap. This class is constructed on every process start,
 * including the headless starts caused by an incoming SMS waking the
 * manifest-registered receiver, so anything slow added here is paid on the
 * hot path that CLAUDE.md constraint 5 asks to keep fast.
 */
class ScopeSmsApplication : Application()
