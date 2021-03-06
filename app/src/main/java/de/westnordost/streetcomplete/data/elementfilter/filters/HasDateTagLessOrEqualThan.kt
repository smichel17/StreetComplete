package de.westnordost.streetcomplete.data.elementfilter.filters

import java.time.LocalDate

/** key <= date */
class HasDateTagLessOrEqualThan(key: String, dateFilter: DateFilter): CompareDateTagValue(key, dateFilter) {
    override val operator = "<="
    override fun compareTo(tagValue: LocalDate) = tagValue <= date
}
