package space.megaworld.streetpass.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.megaworld.streetpass.MainActivity
import space.megaworld.streetpass.R
import space.megaworld.streetpass.StreetPassApp

/**
 * Виджет «встреч сегодня». Обновляется сервисом при каждом изменении счётчика и раз в
 * полчаса системой (`updatePeriodMillis`) — чтобы после полуночи не висело вчерашнее
 * число, даже если обнаружение выключено.
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    companion object {
        private const val TAG = "TodayWidget"

        /** Перечитывает счётчики из базы и перерисовывает все экземпляры виджета. */
        fun refresh(context: Context) {
            val app = context.applicationContext as? StreetPassApp ?: return
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, TodayWidgetProvider::class.java)
            // Без виджетов на экране в базу не ходим — сервис зовёт это на каждую встречу.
            if (manager.getAppWidgetIds(component).isEmpty()) return

            val container = app.container
            container.applicationScope.launch {
                val encounters = container.encounterRepository.todayEncounters.first()
                val people = container.encounterRepository.todayPeers.first()
                val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                    setTextViewText(R.id.widget_count, encounters.toString())
                    setTextViewText(R.id.widget_label, context.getString(R.string.widget_label))
                    setTextViewText(
                        R.id.widget_people,
                        context.resources.getQuantityString(R.plurals.people_count, people, people),
                    )
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
                try {
                    manager.updateAppWidget(component, views)
                } catch (e: IllegalArgumentException) {
                    // Лаунчер отверг RemoteViews (слишком большие или битые) — не роняем процесс.
                    Log.w(TAG, "widget update rejected", e)
                }
            }
        }
    }
}
