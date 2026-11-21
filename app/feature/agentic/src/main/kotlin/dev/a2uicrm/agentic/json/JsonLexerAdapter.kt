package dev.a2uicrm.agentic.json

import androidx.a2ui.model.processor.A2uiJsonReader
import androidx.a2ui.model.processor.A2uiJsonToken

/**
 * [A2uiJsonReader] adapter over a hand-written streaming JSON lexer.
 *
 * Why not kotlinx.serialization's JsonReader: `A2uiJsonReader` is an INTERFACE provided by
 * a2ui-model — the host must implement it (probe/Probe.java §0.4). A self-contained lexer
 * keeps this module testable on plain JVM (unit tests) and on Android without extra
 * dependencies. kotlinx-serialization-json 1.7.3 is already a transitive dependency of
 * a2ui-engine and remains an alternative if the lexer needs to be replaced.
 *
 * Grammar: standard JSON (RFC 8259); single-use (one message per instance).
 */
class JsonLexerAdapter(input: String) : A2uiJsonReader {

    private val src = input.toCharArray()
    private var pos = 0
    private var currentToken: Token? = null

    private enum class TokenKind { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private class Token(val kind: TokenKind, val text: String? = null)

    private fun peekChar(): Char? = if (pos < src.size) src[pos] else null

    private fun advance(): Char {
        val c = src[pos]
        pos++
        return c
    }

    private fun skipWhitespace() {
        while (pos < src.size && src[pos].isWhitespace()) pos++
    }

    private fun readString(): String {
        advance() // opening quote
        val sb = StringBuilder()
        while (pos < src.size) {
            val c = advance()
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    val esc = advance()
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = String(src, pos, 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw JsonLexerException("Invalid escape: \\$esc")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw JsonLexerException("Unterminated string")
    }

    private fun readLiteral(first: Char): Token {
        val sb = StringBuilder().append(first)
        while (pos < src.size && (src[pos].isLetterOrDigit() || src[pos] == '-' || src[pos] == '+' || src[pos] == '.')) {
            sb.append(advance())
        }
        return when (val text = sb.toString()) {
            "true", "false" -> Token(TokenKind.BOOLEAN, text)
            "null" -> Token(TokenKind.NULL)
            else -> Token(TokenKind.NUMBER, text)
        }
    }

    private fun nextToken(): Token {
        skipWhitespace()
        val c = peekChar() ?: return Token(TokenKind.END_DOCUMENT)
        return when {
            c == '{' -> { advance(); Token(TokenKind.BEGIN_OBJECT) }
            c == '}' -> { advance(); Token(TokenKind.END_OBJECT) }
            c == '[' -> { advance(); Token(TokenKind.BEGIN_ARRAY) }
            c == ']' -> { advance(); Token(TokenKind.END_ARRAY) }
            c == '"' -> Token(TokenKind.STRING, readString())
            c == ',' || c == ':' -> { advance(); nextToken() }
            c == '-' || c.isDigit() || c.isLetter() -> readLiteral(advance())
            else -> throw JsonLexerException("Unexpected character: '$c' at $pos")
        }
    }

    private fun ensureToken(): Token {
        if (currentToken == null) currentToken = nextToken()
        return currentToken!!
    }

    private fun consume(): Token {
        val t = ensureToken()
        currentToken = null
        return t
    }

    private fun map(kind: TokenKind): A2uiJsonToken = when (kind) {
        TokenKind.BEGIN_OBJECT -> A2uiJsonToken.BEGIN_OBJECT
        TokenKind.END_OBJECT -> A2uiJsonToken.END_OBJECT
        TokenKind.BEGIN_ARRAY -> A2uiJsonToken.BEGIN_ARRAY
        TokenKind.END_ARRAY -> A2uiJsonToken.END_ARRAY
        TokenKind.NAME -> A2uiJsonToken.NAME
        TokenKind.STRING -> A2uiJsonToken.STRING
        TokenKind.NUMBER -> A2uiJsonToken.NUMBER
        TokenKind.BOOLEAN -> A2uiJsonToken.BOOLEAN
        TokenKind.NULL -> A2uiJsonToken.NULL
        TokenKind.END_DOCUMENT -> A2uiJsonToken.END_DOCUMENT
    }

    private fun stringValue(): String {
        val t = consume()
        if (t.kind != TokenKind.STRING && t.kind != TokenKind.NAME && t.kind != TokenKind.NUMBER) {
            throw JsonLexerException("Expected string value but was ${t.kind}")
        }
        return t.text ?: ""
    }

    override fun peek(): A2uiJsonToken = map(ensureToken().kind)

    override fun beginObject() { consume() }
    override fun endObject() { consume() }
    override fun beginArray() { consume() }
    override fun endArray() { consume() }

    override fun hasNext(): Boolean {
        val kind = ensureToken().kind
        return kind != TokenKind.END_OBJECT && kind != TokenKind.END_ARRAY && kind != TokenKind.END_DOCUMENT
    }

    override fun nextName(): String = stringValue()

    override fun nextString(): String = stringValue()

    override fun nextBoolean(): Boolean {
        val t = consume()
        if (t.kind != TokenKind.BOOLEAN) throw JsonLexerException("Expected boolean but was ${t.kind}")
        return t.text == "true"
    }

    override fun nextDouble(): Double {
        val t = consume()
        if (t.kind != TokenKind.NUMBER) throw JsonLexerException("Expected number but was ${t.kind}")
        return t.text!!.toDouble()
    }

    override fun nextInt(): Int {
        val t = consume()
        if (t.kind != TokenKind.NUMBER) throw JsonLexerException("Expected number but was ${t.kind}")
        return t.text!!.toDouble().toInt()
    }

    override fun nextLong(): Long {
        val t = consume()
        if (t.kind != TokenKind.NUMBER) throw JsonLexerException("Expected number but was ${t.kind}")
        return t.text!!.toDouble().toLong()
    }

    override fun nextNull() { consume() }

    override fun skipValue() {
        val t = consume()
        var depth = 0
        when (t.kind) {
            TokenKind.BEGIN_OBJECT, TokenKind.BEGIN_ARRAY -> depth = 1
            else -> return
        }
        while (depth > 0) {
            val inner = nextToken()
            when (inner.kind) {
                TokenKind.BEGIN_OBJECT, TokenKind.BEGIN_ARRAY -> depth++
                TokenKind.END_OBJECT, TokenKind.END_ARRAY -> depth--
                TokenKind.END_DOCUMENT -> return
                else -> {}
            }
        }
    }

    override fun close() {
        currentToken = null
    }

    class JsonLexerException(message: String) : RuntimeException(message)
}

/** Factory matching the A2uiJsonMessageParser constructor: (String) -> A2uiJsonReader. */
fun jsonLexerReaderFactory(): (String) -> A2uiJsonReader = { input -> JsonLexerAdapter(input) }
