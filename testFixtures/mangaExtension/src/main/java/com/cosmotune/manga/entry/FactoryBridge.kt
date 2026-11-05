package com.cosmotune.manga.entry

import com.cosmotune.manga.fixture.FixtureFactory
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

/** The entry point need not share the Android application ID (for example after R8). */
class FactoryBridge : SourceFactory {
    override fun createSources(): List<Source> = FixtureFactory().createSources()
}
