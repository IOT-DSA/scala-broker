package models.util

import org.scalatestplus.play.PlaySpec
import org.bouncycastle.util.encoders.{ UrlBase64 => Bouncy }
import java.nio.charset.Charset
import com.sun.jndi.ldap.Ber.DecodeException
import org.bouncycastle.util.encoders.DecoderException
import java.io._

/**
 * Test suite for UrlBase64 functions.
 */
class UrlBase64Spec extends PlaySpec {
  import UrlBase64._

  "bouncyEncode" should {
    "encode a byte array" in {
      val data = "hello".getBytes
      bouncyEncode(data) mustBe Bouncy.encode(data)
    }
  }

  "bouncyDecode" should {
    "decode a byte array" in {
      val data = "hello".getBytes
      val encoded = Bouncy.encode(data)
      bouncyDecode(encoded) mustBe data
      Bouncy.decode(encoded) mustBe data
    }
    "fail on invalid input" in {
      a[DecoderException] must be thrownBy bouncyDecode("123".getBytes)
    }
  }

  "addPadding" should {
    "add padding to the string to make its size a multiple of 4" in {
      addPadding("hello") mustBe "hello..."
      addPadding("broker") mustBe "broker.."
      addPadding("bye") mustBe "bye."
      addPadding("java") mustBe "java"
    }
  }

  "stripPadding" should {
    "remove string padding" in {
      stripPadding("hello...") mustBe "hello"
      stripPadding("broker..") mustBe "broker"
      stripPadding("bye.") mustBe "bye"
      stripPadding("java") mustBe "java"
    }
  }

  "encodeBytes" should {
    "encode a byte array with default UTF-8 encoding" in {
      encodeBytes("hello".getBytes) mustBe "aGVsbG8"
    }
    "encode a byte array with custom encoding" in {
      encodeBytes("привет".getBytes("KOI8-R"), "KOI8-R") mustBe "0NLJ18XU"
    }
    "fail on invalid charset name" in {
      a[UnsupportedEncodingException] must be thrownBy encodeBytes("hello".getBytes, "UNKNOWN")
    }
  }

  "encode" should {
    "encode a string with default UTF-8 encoding" in {
      encode("hello") mustBe "aGVsbG8"
    }
    "encode a string with custom encoding" in {
      encode("привет", "KOI8-R") mustBe "0NLJ18XU"
    }
    "fail on invalid charset name" in {
      a[UnsupportedEncodingException] must be thrownBy encode("hello", "UNKNOWN")
    }
  }

  "decodeToBytes" should {
    "decode a string with default UTF-8 encoding into a byte array" in {
      val data = "hello"
      new String(decodeToBytes(encode(data))) mustBe data
    }
    "decode a string with custom encoding into a byte array" in {
      val data = "привет"
      new String(decodeToBytes(encode(data, "KOI8-R"), "KOI8-R"), "KOI8-R") mustBe data
    }
    "fail on invalid charset name" in {
      a[UnsupportedEncodingException] must be thrownBy decodeToBytes(encode("hello"), "UNKNOWN")
    }
  }

  "decode" should {
    "decode a string with default UTF-8 encoding" in {
      val data = "hello"
      decode(encode(data)) mustBe data
    }
    "decode a string with custom encoding" in {
      val data = "привет"
      decode(encode(data, "KOI8-R"), "KOI8-R") mustBe data
    }
    "fail on invalid charset name" in {
      a[UnsupportedEncodingException] must be thrownBy decode(encode("hello"), "UNKNOWN")
    }
  }
}