// Derived from Mihon d001e6f210e6a077a30dd9a1392573a3225bba2f; Apache-2.0.
// See manga/third-party/MIHON_SOURCE_API.json and LICENSE.mihon.
package eu.kanade.tachiyomi.source.model

import androidx.compose.runtime.Stable

@Stable
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    override fun equals(other: Any?): Boolean {
        return false
    }

    override fun hashCode(): Int {
        return list.hashCode()
    }
}
