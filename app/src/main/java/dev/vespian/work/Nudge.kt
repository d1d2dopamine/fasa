package dev.vespian.work

import android.content.Context
import dev.vespian.Prefs
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.model.Drinks
import dev.vespian.tg.Secrets
import java.time.Instant
import java.time.ZoneId

/**
 * Decides when to ask something in the notification shade.
 *
 * The rules are deliberately the same ones the chat uses. The morning question
 * waits twenty minutes after the band says the night ended, so it does not
 * arrive while the person is still deciding whether they are awake, and gives
 * up after eight hours, because a wellbeing answer at nine at night is about a
 * different day. The evening question only fires on a day with nothing logged.
 *
 * Silent when Telegram is set up: the same question arriving twice, once as a
 * chat message and once as a notification, is how a person learns to dismiss
 * both without reading them.
 *
 * Every question is guarded by a date written to the database rather than by a
 * timer, so a phone that was off, killed or asleep asks once when it comes back
 * instead of not at all or five times.
 */
object Nudge {

    private const val K_MORNING = "nudge_morning"
    private const val K_LATE = "nudge_late"

    /** How long after waking a morning question is still about this morning. */
    private const val MORNING_WINDOW_MS = 8L * 60L * 60L * 1000L

    /** Twenty minutes of grace before anything is asked. */
    private const val MORNING_DELAY_MS = 20L * 60L * 1000L

    suspend fun tick(context: Context) {
        if (Secrets.configured(context)) return
        runCatching { maybeMorning(context) }
        runCatching { maybeLate(context) }
    }

    /** How did you sleep, once, on the morning after a night the band saw. */
    private suspend fun maybeMorning(context: Context) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val night = db.nights().lastEnded(now) ?: return
        val age = now - night.sleepEnd
        if (age < MORNING_DELAY_MS || age > MORNING_WINDOW_MS) return

        val date = Instant.ofEpochMilli(night.sleepEnd)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (db.meta().get(K_MORNING) == date) return
        // Already answered, in the app or anywhere else. Asking anyway is how an
        // app teaches people to ignore it.
        if (db.answers().byDate(date)?.mood != null) return

        Notify.askMood(context)
        db.meta().put(Meta(K_MORNING, date))
    }

    /**
     * Did you drink anything today, once, in the evening, only on a day that has
     * nothing on it. Same switch as every other drink question.
     */
    private suspend fun maybeLate(context: Context) {
        if (!Prefs.askDrinks(context)) return
        val db = Db.get(context)
        val today = Drinks.dayKey()
        if (db.meta().get(K_LATE) == today) return
        if (!Drinks.shouldAskLate(context)) return

        Notify.askDrink(context)
        db.meta().put(Meta(K_LATE, today))
    }
}
