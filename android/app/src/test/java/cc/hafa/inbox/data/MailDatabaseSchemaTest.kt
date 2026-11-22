package cc.hafa.inbox.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MailDatabaseSchemaTest {
    private val schemas = File("schemas/${MailDatabase::class.java.name}")

    @Test
    fun `every schema version up to the current one is exported`() {
        for (version in 1..MailDatabase.VERSION) {
            val file = File(schemas, "$version.json")
            assertTrue("missing ${file.path}: build once to export it, and add a migration", file.isFile)
            assertTrue(file.readText().contains("\"version\": $version,"))
        }
    }

    @Test
    fun `the exported schema has offline search text`() {
        val schema = File(schemas, "${MailDatabase.VERSION}.json").readText()
        assertTrue("rebuild once to export the schema", schema.contains("\"columnName\": \"searchText\""))
    }
}
