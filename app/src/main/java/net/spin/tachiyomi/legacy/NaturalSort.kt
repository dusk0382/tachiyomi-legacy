package net.spin.tachiyomi.legacy

object NaturalSort {
    fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        val la = a.length
        val lb = b.length

        while (ia < la && ib < lb) {
            val ca = a[ia]
            val cb = b[ib]

            if (ca.isDigit() && cb.isDigit()) {
                var na = ia
                var nb = ib

                while (na < la && a[na].isDigit()) na++
                while (nb < lb && b[nb].isDigit()) nb++

                var sa = a.substring(ia, na)
                var sb = b.substring(ib, nb)

                sa = sa.trimStart('0')
                sb = sb.trimStart('0')

                if (sa.length != sb.length) {
                    return sa.length - sb.length
                }

                val cmp = sa.compareTo(sb)
                if (cmp != 0) return cmp

                ia = na
                ib = nb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }

        return la - lb
    }
}
