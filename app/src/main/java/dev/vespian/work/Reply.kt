package dev.vespian.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dev.vespian.db.Answer
import dev.vespian.db.Db
import dev.vespian.model.Drinks
import dev.vespian.model.Engine
import dev.vespian.tg.Commands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Answers given straight from the notification shade.
 *
 * The whole point is that no app has to be opened and nothing has to be typed.
 * A tap here saves exactly what the same tap in the app would save, so the model
 * cannot tell the two apart, and the notification is dismissed first so the
 * button never sits there looking unpressed while the database is being written.
 *
 * The work runs on its own scope rather than the receiver's thread, because a
 * broadcast receiver is killed shortly after it returns and a database write is
 * not guaranteed to fit in that window.
 */
class Reply : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_MOOD -> {
                val mood = intent.getIntExtra(EXTRA_MOOD, -1)
                if (mood < 0) return
                dismiss(app, Notify.ID_ASK_MOOD)
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { saveMood(app, mood) }
                }
            }

            ACTION_DRINK -> {
                val yes = intent.getBooleanExtra(EXTRA_YES, false)
                dismiss(app, Notify.ID_ASK_DRINK)
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { saveDrink(app, yes) }
                }
            }
        }
    }

    private fun dismiss(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /**
     * Merge the mood into today's answer instead of replacing it, so answering
     * here does not wipe a drink count entered in the app earlier.
     */
    private suspend fun saveMood(context: Context, mood: Int) {
        val db = Db.get(context)
        val date = Commands.dayKey()
        val existing = db.answers().byDate(date)
        db.answers().put(
            Answer(
                dateKey = date,
                mood = mood,
                mugs = existing?.mugs,
                at = System.currentTimeMillis(),
                cans = existing?.cans,
                alcohol = existing?.alcohol,
            )
        )
        Engine.invalidate()
        Commands.refitSoon(context)
    }

    /**
     * Yes backdates one drink into the evening window with wide slack, no simply
     * closes the day so nothing asks again. Both are answers; only silence is not.
     */
    private suspend fun saveDrink(context: Context, yes: Boolean) {
        if (yes) Drinks.logLate(context) else Drinks.markSettled(context)
    }

    companion object {
        const val ACTION_MOOD = "dev.vespian.action.REPLY_MOOD"
        const val ACTION_DRINK = "dev.vespian.action.REPLY_DRINK"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_YES = "yes"
    }
}
