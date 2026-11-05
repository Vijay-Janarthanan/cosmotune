// Derived from Mihon d001e6f210e6a077a30dd9a1392573a3225bba2f; Apache-2.0.
// See manga/third-party/MIHON_SOURCE_API.json and LICENSE.mihon.
@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var thumbnail_url: String? = null

    override var artist: String? = null

    override var author: String? = null

    override var status: Int = 0

    override var description: String? = null

    override var genre: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    override var memo: JsonObject = JsonObject(emptyMap())
}
