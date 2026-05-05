package io.github.mesmerprism.rustyxr.companion.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserRuntimeProfileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesAndSerializesUserRuntimeProfiles() {
        val serialized = UserRuntimeProfileStore.serializeProfiles(
            listOf(
                RuntimeProfile(
                    id = "user.dev-smoke",
                    label = "Development Smoke",
                    description = "Local launch extras",
                    packageIds = listOf("com.example.target"),
                    values = mapOf(
                        "rustyxr.example" to "quest-composite-layer-apk",
                        "rustyxr.camera" to "false"
                    )
                )
            )
        )

        val parsed = UserRuntimeProfileStore.parseProfiles(serialized)

        assertEquals(1, parsed.size)
        assertEquals("user.dev-smoke", parsed.first().id)
        assertEquals("Development Smoke", parsed.first().label)
        assertEquals(listOf("com.example.target"), parsed.first().packageIds)
        assertEquals("false", parsed.first().values["rustyxr.camera"])
    }

    @Test
    fun upsertsAndDeletesProfiles() {
        val file = temporaryFolder.newFolder("catalogs").resolve("user-runtime-profiles.json")
        val store = UserRuntimeProfileStore(file)

        store.upsert(
            RuntimeProfile(
                id = "user.one",
                label = "One",
                packageIds = listOf("*"),
                values = mapOf("mode" to "one")
            )
        )
        store.upsert(
            RuntimeProfile(
                id = "user.one",
                label = "One Updated",
                packageIds = listOf("com.example.target"),
                values = mapOf("mode" to "two")
            )
        )

        val profiles = store.loadProfiles()
        assertEquals(1, profiles.size)
        assertEquals("One Updated", profiles.first().label)
        assertEquals("two", profiles.first().values["mode"])

        assertTrue(store.delete("user.one"))
        assertTrue(store.loadProfiles().isEmpty())
        assertFalse(store.delete("user.missing"))
    }

    @Test
    fun createsUniqueUserProfileIds() {
        val file = temporaryFolder.newFolder("catalogs").resolve("user-runtime-profiles.json")
        val store = UserRuntimeProfileStore(file)

        val id = store.resolveUniqueProfileId(
            label = "Development Smoke",
            existingIds = setOf("user.development-smoke", "user.development-smoke-2")
        )

        assertEquals("user.development-smoke-3", id)
    }
}
