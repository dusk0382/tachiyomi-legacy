package net.spin.tachiyomi.legacy.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formateo de fechas compartido entre las pantallas de la app. */
object TimeUtil {

    /** Fecha relativa para lo reciente ("hace 3 dias"), dd/MM/yyyy para lo antiguo. */
    fun formatRelative(dateMillis: Long): String {
        val diff = System.currentTimeMillis() - dateMillis
        val dayMillis = 24L * 60L * 60L * 1000L
        if (diff > 0L) {
            val days = diff / dayMillis
            if (days < 30L) {
                return when {
                    days < 1L && diff >= 60L * 60L * 1000L -> "hace ${diff / (60L * 60L * 1000L)} h"
                    days < 1L -> "hoy"
                    days == 1L -> "ayer"
                    else -> "hace $days días"
                }
            }
        }
        return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(dateMillis))
    }
}
