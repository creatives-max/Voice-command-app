package com.voicecontrol.feature.assistant.scan

import android.content.Context
import android.widget.Toast
import com.voicecontrol.core.engine.DocumentFiller
import com.voicecontrol.core.engine.ProposedFill
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.model.ScreenSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The form a "fill from a photo" was started on, and filling it once the user is back on it.
 * Document values live only in memory, only until they are filled.
 */
@Singleton
class ScanSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screen: ScreenGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var target: ScreenSnapshot? = null
        private set

    fun begin(snapshot: ScreenSnapshot?) {
        target = snapshot
    }

    fun cancel() {
        target = null
    }

    /** Fills [fills] into the target form when it is in front again (the scan screen has closed). */
    fun fillWhenBack(fills: List<ProposedFill>) {
        val form = target ?: return
        target = null
        scope.launch {
            val report = DocumentFiller(screen).fill(form, fills)
            val text = when {
                !report.screenReturned -> "The form wasn't on screen, so nothing was filled."
                report.failed == 0 -> "Filled ${report.filled} field${if (report.filled == 1) "" else "s"} from your photo. Please check them."
                else -> "Filled ${report.filled} of ${fills.size} fields. Check the rest."
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
