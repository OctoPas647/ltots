package com.ltots.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqState
import com.ltots.app.audio.EqTone
import com.ltots.app.profiles.ProfileTransfer
import com.ltots.app.profiles.UserSwitch
import com.ltots.app.ui.theme.ThemeState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** EQ profiles (up to 10, named), users (up to 3, each with their own everything), export and import. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UsersAndProfilesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Store.init(context)
        ThemeState.init(freshInstall = true)
        EqProfiles.load()
    }

    private fun eq(vararg gains: Float) = EqState(bands = EqDefaults.tenBand().mapIndexed { i, b -> b.copy(gainDb = gains.getOrElse(i) { 0f }) })

    @Test fun eqProfilesAreNamedAndUpToTen() {
        val rock = eq(4f, 3f, 1.5f)
        assertEquals(0, EqProfiles.addCurrent(rock))
        assertEquals("Custom 1", EqProfiles.items.value[0].name)
        assertEquals(0, EqProfiles.active.value)
        repeat(12) { EqProfiles.addCurrent(eq(it.toFloat())) }
        assertEquals(EqProfiles.MAX, EqProfiles.items.value.size)
        assertNull(EqProfiles.addCurrent(rock))
        EqProfiles.rename(0, "Rock")
        EqProfiles.delete(1)
        assertEquals(EqProfiles.MAX - 1, EqProfiles.items.value.size)
        assertEquals("Rock", EqProfiles.items.value[0].name)
        // Picking one marks it in use; deleting the one in use leaves none in use.
        assertTrue(EqTone.sameShape(rock, EqProfiles.pick(0)!!))
        EqProfiles.delete(0)
        assertNull(EqProfiles.active.value)
        Store.flush()
        EqProfiles.load()
        assertEquals(EqProfiles.MAX - 2, EqProfiles.items.value.size)
    }

    @Test fun olderFiveSlotEqSavesCarryOver() {
        val dir = Store.dirOf(Users.active.value)
        val slots = JSONArray().put(JSONObject.NULL).put(Json.eq(eq(6f))).put(JSONObject.NULL).put(Json.eq(eq(-3f))).put(JSONObject.NULL)
        Store.flush()
        File(dir, "eq_profiles.json").writeText(JSONObject().put("slots", slots).put("active", 3).toString())
        EqProfiles.load()
        assertEquals(listOf("Custom 2", "Custom 4"), EqProfiles.items.value.map { it.name })
        assertEquals(1, EqProfiles.active.value)
    }

    /** Data saved before users existed becomes the first user's, untouched. */
    @Test fun existingDataMovesToTheFirstUser() {
        val root = File(context.filesDir, "ltots")
        Store.flush()
        root.deleteRecursively()
        root.mkdirs()
        File(root, "theme.json").writeText(Json.theme(ThemeConfig(spectrumStyle = SpectrumStyle.MIRROR)).toString())
        File(root, "profiles").mkdirs()
        File(root, "profiles/abc.json").writeText("{\"pitch\":100}")
        Store.init(context)
        ThemeState.init(freshInstall = false)
        assertEquals(SpectrumStyle.MIRROR, ThemeState.config.value.spectrumStyle)
        assertEquals(listOf(User(Users.FIRST_ID, "User 1")), Users.users.value)
        assertTrue(File(root, "users/u1/profiles/abc.json").exists())
        assertFalse(File(root, "theme.json").exists())
    }

    @Test fun everyUserHasTheirOwnEverything() {
        ThemeState.update { it.copy(spectrumStyle = SpectrumStyle.BARS) }
        EqProfiles.addCurrent(eq(5f))
        Store.saveProfile("song-1", SongProfile(pitchCents = 200))
        val first = Users.active.value

        val second = Users.add("Maria")!!
        assertEquals("adding someone doesn't switch to them", first, Users.active.value)
        UserSwitch.switchTo(second.id)
        assertEquals(second.id, Users.active.value)
        assertEquals("a new user starts on the default look", ThemeState.defaultLook, ThemeState.config.value)
        assertTrue(EqProfiles.items.value.isEmpty())
        assertNull(Store.loadProfile("song-1"))
        ThemeState.update { it.copy(spectrumStyle = SpectrumStyle.MIRROR) }
        Store.saveProfile("song-1", SongProfile(pitchCents = -100))

        UserSwitch.switchTo(first)
        assertEquals(SpectrumStyle.BARS, ThemeState.config.value.spectrumStyle)
        assertEquals(1, EqProfiles.items.value.size)
        assertEquals(200, Store.loadProfile("song-1")?.pitchCents)
        UserSwitch.switchTo(second.id)
        assertEquals(SpectrumStyle.MIRROR, ThemeState.config.value.spectrumStyle)
        assertEquals(-100, Store.loadProfile("song-1")?.pitchCents)

        // Three at most; names stay unique; never the last one deleted.
        Users.add("maria")
        assertEquals(listOf("User 1", "Maria", "maria (2)"), Users.users.value.map { it.name })
        assertNull(Users.add())
        // Deleting the one in use switches to another first.
        assertTrue(UserSwitch.delete(second.id))
        assertEquals(first, Users.active.value)
        assertEquals(SpectrumStyle.BARS, ThemeState.config.value.spectrumStyle)
        assertFalse(Store.dirOf(second.id).exists())
        assertTrue(UserSwitch.delete(Users.users.value.last().id))
        assertFalse(UserSwitch.delete(first))
        assertEquals(1, Users.users.value.size)
    }

    @Test fun exportAndImportLooksEqsAndUsers() {
        ThemeState.update { it.copy(spectrumStyle = SpectrumStyle.MIRROR, freezeVisualizer = true, gradientFlow = true) }
        ThemeState.renameProfile(0, "Night")
        val lookFile = ProfileTransfer.lookJson(context, "Night", ThemeState.config.value)
        val eqFile = ProfileTransfer.eqJson("Rock", eq(4f, 3f))

        // A look comes back as a new profile with the same look and a free name.
        val r1 = ProfileTransfer.import(context, lookFile)
        assertEquals(ProfileTransfer.Imported.Look("Night (2)"), r1)
        val imported = ThemeState.profiles.value.last().look
        assertEquals(SpectrumStyle.MIRROR, imported.spectrumStyle)
        assertTrue(imported.freezeVisualizer && imported.gradientFlow)
        // An EQ too.
        assertEquals(ProfileTransfer.Imported.Eq("Rock"), ProfileTransfer.import(context, eqFile))
        assertTrue(EqTone.sameShape(eq(4f, 3f), EqProfiles.items.value.last().eq))
        // A whole user, with their looks, EQs and songs.
        Store.saveProfile("song-9", SongProfile(speed = 1.25f))
        Users.rename(Users.active.value, "Dad")
        val userFile = ProfileTransfer.userJson(context, Users.active.value)
        val r3 = ProfileTransfer.import(context, userFile)
        assertEquals(ProfileTransfer.Imported.User("Dad (2)"), r3)
        val copy = Users.users.value.last()
        UserSwitch.switchTo(copy.id)
        assertEquals(listOf("Night", "Night (2)"), ThemeState.profiles.value.map { it.name })
        assertEquals(listOf("Rock"), EqProfiles.items.value.map { it.name })
        assertEquals(1.25f, Store.loadProfile("song-9")?.speed)
        // Anything else is refused, with a reason.
        assertTrue(ProfileTransfer.import(context, "{\"hello\":1}") is ProfileTransfer.Imported.Refused)
        assertTrue(ProfileTransfer.import(context, "not json") is ProfileTransfer.Imported.Refused)
    }
}
