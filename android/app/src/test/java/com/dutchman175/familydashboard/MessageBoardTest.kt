package com.dutchman175.familydashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MessageTopicTest {
    private val placeholder = "PASTE-THE-TOPIC-NAME"

    @Test
    fun savesALongTopicAndANtfyUrl() {
        val saved = MessageTopic.interpret("  $placeholder  ")
        assertTrue(saved is MessageTopic.Action.Save)
        assertEquals(placeholder, (saved as MessageTopic.Action.Save).topic)

        val quoted = MessageTopic.interpret("'$placeholder'")
        assertEquals(placeholder, (quoted as MessageTopic.Action.Save).topic)

        val url = MessageTopic.interpret("https://ntfy.sh/$placeholder/json?since=24h")
        assertEquals(placeholder, (url as MessageTopic.Action.Save).topic)

        val upper = MessageTopic.interpret("https://NTFY.SH/$placeholder")
        assertEquals(placeholder, (upper as MessageTopic.Action.Save).topic)
    }

    @Test
    fun clearAndIgnore() {
        assertTrue(MessageTopic.interpret(null) is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("") is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("   ") is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("clear") is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("CLEAR") is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("-") is MessageTopic.Action.Clear)
        assertTrue(MessageTopic.interpret("short-topic") is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("a".repeat(65)) is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("has a space in the name") is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("http://ntfy.sh/$placeholder") is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("https://evil.example/$placeholder") is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("https://ntfy.sh/json") is MessageTopic.Action.Ignore)
        assertTrue(MessageTopic.interpret("javascript:alert(1)") is MessageTopic.Action.Ignore)
    }

    @Test
    fun exactLengthBounds() {
        assertTrue(MessageTopic.interpret("a".repeat(16)) is MessageTopic.Action.Save)
        assertTrue(MessageTopic.interpret("b".repeat(64)) is MessageTopic.Action.Save)
        assertTrue(MessageTopic.interpret("c".repeat(15)) is MessageTopic.Action.Ignore)
    }
}

class MessageBoardTest {
    private val secret = "this-is-a-secret-topic-name"
    private val baseSec = 1_700_000_000L

    @Test
    fun streamUrlUsesChicagoMidnightAndNoOtherHost() {
        val now = chicago(2026, 10, 26, 15, 0)
        val since = MessageBoard.midnightMillis(now) / 1000L
        val url = MessageBoard.endpoint("PASTE-THE-TOPIC-NAME", now)
        assertEquals("https://ntfy.sh/PASTE-THE-TOPIC-NAME/json?since=$since", url)
        assertFalse(url.contains("24h"))
        assertTrue(url.startsWith("https://ntfy.sh/"))
    }

    @Test
    fun snapshotOmitsTheTopicAndEscapesPlainText() {
        val line = """{"id":"abc","time":$baseSec,"event":"message","topic":"$secret","title":"Ann <script>","message":"he said \"hi\" & <b>"}"""
        val now = baseSec * 1000 + 5_000
        val result = MessageBoard.ingest(emptyList(), line, now)
        assertTrue(result.changed)
        assertTrue(result.pulse)
        assertEquals("Ann <script>", result.messages[0].sender)
        assertEquals("he said \"hi\" & <b>", result.messages[0].text)
        val json = MessageBoard.toJson(true, result.messages, pulse = true)
        assertFalse(json.contains(secret))
        assertFalse(json.contains("topic"))
        assertFalse(json.contains("<"))
        assertFalse(json.contains(">"))
        val parsed = JSONObject(json)
        val note = parsed.getJSONArray("messages").getJSONObject(0)
        assertEquals("Ann <script>", note.getString("sender"))
        assertEquals("he said \"hi\" & <b>", note.getString("text"))
        assertTrue(parsed.getBoolean("pulse"))
        assertTrue(parsed.getBoolean("configured"))
    }

    @Test
    fun clearWipesMessagesAndDoesNotShowTheWord() {
        val kept = MessageBoard.ingest(
            emptyList(),
            message("m1", baseSec, "Cassie", "Dinner at 6"),
            baseSec * 1000
        ).messages
        assertEquals(1, kept.size)
        val cleared = MessageBoard.ingest(
            kept,
            message("m2", baseSec + 10, "Cassie", "  clear\n"),
            (baseSec + 10) * 1000
        )
        assertTrue(cleared.cleared)
        assertTrue(cleared.messages.isEmpty())
        val json = MessageBoard.toJson(true, cleared.messages, false)
        assertFalse(json.contains("Dinner"))
        assertFalse(json.contains("clear"))

        val notClear = MessageBoard.ingest(
            emptyList(),
            message("m3", baseSec, "Andy", "Clear"),
            baseSec * 1000
        )
        assertEquals("Clear", notClear.messages[0].text)
        assertFalse(notClear.cleared)
    }

    @Test
    fun dropsMessagesFromBeforeChicagoMidnightAndKeepsTheNewestThree() {
        val evening = chicago(2026, 10, 25, 21, 0)
        val laterThatNight = chicago(2026, 10, 25, 23, 30)
        val afterMidnight = chicago(2026, 10, 26, 0, 5)
        val kept = MessageBoard.ingest(
            emptyList(),
            message("old", evening / 1000, "Andy", "stale"),
            evening + 1_000
        )
        assertEquals(1, kept.messages.size)
        assertEquals(1, MessageBoard.expire(kept.messages, laterThatNight).size)
        assertTrue(MessageBoard.expire(kept.messages, afterMidnight).isEmpty())

        val ignored = MessageBoard.ingest(
            emptyList(),
            message("old", evening / 1000, "Andy", "stale"),
            afterMidnight
        )
        assertTrue(ignored.messages.isEmpty())

        val midnight = MessageBoard.midnightMillis(afterMidnight)
        val atMidnight = MessageBoard.ingest(
            emptyList(),
            message("day", midnight / 1000, "Andy", "new day"),
            midnight + 1_000
        )
        assertEquals("new day", atMidnight.messages[0].text)

        var list = emptyList<MessageBoard.Note>()
        for (i in 1..5) {
            val at = evening + i * 1_000
            list = MessageBoard.ingest(
                list,
                message("m$i", at / 1000, "Andy", "Note $i"),
                at
            ).messages
        }
        val arr = JSONObject(MessageBoard.toJson(true, list, false)).getJSONArray("messages")
        assertEquals(3, arr.length())
        assertEquals("Note 5", arr.getJSONObject(0).getString("text"))
        assertEquals("Note 3", arr.getJSONObject(2).getString("text"))
        assertEquals(MessageBoard.MAX_SHOWN, 3)
        assertEquals(MessageBoard.MAX_STORED, 40)
    }

    @Test
    fun daylightSavingMidnightStillClearsThePreviousEvening() {
        val springEvening = chicago(2026, 3, 7, 23, 0)
        val springMorning = chicago(2026, 3, 8, 0, 10)
        val springNote = MessageBoard.ingest(
            emptyList(),
            message("spr", springEvening / 1000, "Cassie", "before spring"),
            springEvening + 1_000
        ).messages
        assertEquals(1, springNote.size)
        assertTrue(MessageBoard.expire(springNote, springMorning).isEmpty())
        val springNext = MessageBoard.nextMidnightMillis(springEvening)
        assertEquals(springMorning - 10 * 60 * 1000, springNext)
        assertTrue(springNext > springEvening)

        val fallEvening = chicago(2026, 10, 31, 23, 0)
        val fallMorning = chicago(2026, 11, 1, 0, 30)
        val fallNote = MessageBoard.ingest(
            emptyList(),
            message("fall", fallEvening / 1000, "Cassie", "before fall"),
            fallEvening + 1_000
        ).messages
        assertTrue(MessageBoard.expire(fallNote, fallMorning).isEmpty())
        val duringFold = chicago(2026, 11, 1, 1, 30)
        val sameDay = MessageBoard.ingest(
            emptyList(),
            message("fold", duringFold / 1000, "Cassie", "still today"),
            duringFold + 1_000
        ).messages
        assertEquals(1, MessageBoard.expire(sameDay, chicago(2026, 11, 1, 23, 0)).size)
        assertTrue(MessageBoard.expire(sameDay, chicago(2026, 11, 2, 0, 1)).isEmpty())
    }

    @Test
    fun clipsTextTo200CharactersWithoutSplittingAnEmoji() {
        val now = baseSec * 1000
        val long = MessageBoard.ingest(emptyList(), message("a", baseSec, "Cassie", "x".repeat(250)), now)
        assertEquals(200, long.messages[0].text.length)

        val kept = "x".repeat(199) + "\uD83D\uDE00"
        val withEmoji = MessageBoard.ingest(emptyList(), message("b", baseSec, "Cassie", kept), now)
        assertTrue(withEmoji.messages[0].text.endsWith("\uD83D\uDE00"))
        assertEquals(200, withEmoji.messages[0].text.codePointCount(0, withEmoji.messages[0].text.length))

        val dropped = MessageBoard.ingest(
            emptyList(),
            message("c", baseSec, "Cassie", "x".repeat(200) + "\uD83D\uDE00"),
            now
        )
        assertFalse(dropped.messages[0].text.contains("\uD83D\uDE00"))
        assertEquals(200, dropped.messages[0].text.length)
    }

    @Test
    fun ignoresKeepaliveAndDuplicateIds() {
        val first = MessageBoard.ingest(
            emptyList(),
            message("same", baseSec, "Andy", "Hello"),
            baseSec * 1000
        )
        val again = MessageBoard.ingest(
            first.messages,
            message("same", baseSec + 5, "Andy", "Hello again"),
            (baseSec + 5) * 1000
        )
        assertFalse(again.changed)
        assertEquals("Hello", again.messages[0].text)
        val beat = MessageBoard.ingest(
            first.messages,
            """{"id":"k","time":$baseSec,"event":"keepalive","topic":"$secret"}""",
            baseSec * 1000
        )
        assertFalse(beat.changed)
        assertEquals(1, beat.messages.size)
    }

    @Test
    fun roundTripStorageAndMissingTitle() {
        val now = baseSec * 1000 + 1_000
        val notes = MessageBoard.ingest(
            emptyList(),
            """{"id":"n1","time":$baseSec,"event":"message","message":"On my way"}""",
            now
        ).messages
        assertEquals("Family", notes[0].sender)
        val decoded = MessageBoard.decode(MessageBoard.encode(notes))
        assertEquals(notes, decoded)
        assertFalse(MessageBoard.encode(notes).contains(secret))
    }

    @Test
    fun unconfiguredSnapshotIsEmpty() {
        val json = MessageBoard.toJson(false, emptyList(), true)
        assertEquals("{\"configured\":false,\"pulse\":false,\"messages\":[]}", json)
    }

    private fun chicago(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("America/Chicago"))
            .toInstant()
            .toEpochMilli()
    }

    private fun message(id: String, time: Long, title: String, body: String): String {
        return """{"id":"$id","time":$time,"event":"message","topic":"$secret","title":${JSONObject.quote(title)},"message":${JSONObject.quote(body)}}"""
    }
}
