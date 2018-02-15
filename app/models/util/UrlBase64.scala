package models.util

import org.apache.commons.lang3.StringUtils

/**
 * Handles base64 encodings. All encodings are UTF-8.
 */
object UrlBase64 {

  /**
   * The default encoding.
   */
  val UTF8 = "UTF-8"

  /**
   * Encodes a string into Base64 form. The padding is explicitly removed.
   */
  def encode(data: String, charset: String = UTF8) = encodeBytes(data.getBytes(charset), charset)

  /**
   * Encodes a byte array into Base64 format. The padding is explicitly removed.
   */
  def encodeBytes(data: Array[Byte], charset: String = UTF8) =
    stripPadding(new String(bouncyEncode(data), charset))

  /**
   * Decodes a Base64 encoded string, padding it if necessary.
   */
  def decode(data: String, charset: String = UTF8) = new String(decodeToBytes(data, charset), charset)

  /**
   * Decodes a Base64 encoded string into a byte array, padding it if necessary.
   */
  def decodeToBytes(data: String, charset: String = UTF8) =
    bouncyDecode(addPadding(data).getBytes(charset))

  /**
   * Removes padding from encoded data.
   */
  def stripPadding(encoded: String) = StringUtils.stripEnd(encoded, ".")

  /**
   * Adds padding to the encoding to the neares size that is a multiple of 4. Must be UTF-8 compatible.
   */
  def addPadding(encoded: String) = encoded + "." * ((4 - (encoded.length % 4)) % 4)

  /**
   * Shortcut reference to the bouncy castle Base64 encoder.
   */
  def bouncyEncode(data: Array[Byte]) = org.bouncycastle.util.encoders.UrlBase64.encode(data)

  /**
   * Shortcut reference to the bouncy castle Base64 decoder.
   */
  def bouncyDecode(data: Array[Byte]) = org.bouncycastle.util.encoders.UrlBase64.decode(data)
}