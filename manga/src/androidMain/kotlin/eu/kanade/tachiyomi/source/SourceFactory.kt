// Derived from Mihon d001e6f210e6a077a30dd9a1392573a3225bba2f; Apache-2.0.
// See manga/third-party/MIHON_SOURCE_API.json and LICENSE.mihon.
package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources at runtime.
 */
interface SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<Source>
}
