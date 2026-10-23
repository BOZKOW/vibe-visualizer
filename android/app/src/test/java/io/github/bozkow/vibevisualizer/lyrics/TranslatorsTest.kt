package io.github.bozkow.vibevisualizer.lyrics

import io.github.bozkow.vibevisualizer.core.NetError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading an AI's reply: the JSON object wherever it sits (code fences, a sentence, a model thinking out loud first). */
class TranslatorsTest {
    @Test fun plainObject() {
        val o = Translators.parseAi("""{"language":"ja","lines":["Hello", null, "Goodbye"]}""", 3)
        assertEquals(listOf("Hello", null, "Goodbye"), o.tr); assertEquals("ja", o.lang)
    }

    @Test fun fencedAndThinking() {
        val o = Translators.parseAi("<think>the user wants {json}</think>\n```json\n{\"language\": \"es\", \"lines\": [\"One\", \"Two\"]}\n```", 2)
        assertEquals(listOf("One", "Two"), o.tr); assertEquals("es", o.lang)
    }

    @Test fun bareArray() {
        val o = Translators.parseAi("Here you go: [\"A\", \"B\"]", 2)
        assertEquals(listOf("A", "B"), o.tr); assertNull(o.lang)
    }

    @Test(expected = NetError::class) fun wrongCount() { Translators.parseAi("""{"lines":["only one"]}""", 2) }

    @Test(expected = NetError::class) fun noTranslations() { Translators.parseAi("Sorry, I can't help with that.", 1) }
}
