package com.liuying.app.domain

import com.liuying.app.domain.model.Manifest
import com.liuying.app.domain.model.StremioJson
import com.liuying.app.domain.model.noBrowsableCatalogsNotice
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class NoCatalogsNoticeTest {
    private fun parse(s: String) = StremioJson.decodeFromString(Manifest.serializer(), s)

    @Test fun streamOnlyAddonExplainsItself() {
        val m = parse("""{"id":"a","name":"A","resources":["stream","meta"],"types":["movie"]}""")
        assertEquals("This add-on has no browsable catalogs; it provides: stream, meta", m.noBrowsableCatalogsNotice)
    }

    @Test fun onlyInputRequiredCatalogsCountAsNone() {
        val m = parse("""{"id":"a","name":"A","resources":["catalog"],"catalogs":[{"type":"movie","id":"s","extra":[{"name":"search","isRequired":true}]}]}""")
        assertNotNull(m.noBrowsableCatalogsNotice)
    }

    @Test fun browsableCatalogHasNoNotice() {
        val m = parse("""{"id":"a","name":"A","resources":["catalog"],"catalogs":[{"type":"movie","id":"top"}]}""")
        assertNull(m.noBrowsableCatalogsNotice)
    }
}
