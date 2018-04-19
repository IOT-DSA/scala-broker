package msgpack

import java.io.ByteArrayOutputStream

import org.msgpack.core.{MessagePack, MessagePacker, MessageUnpacker}
import org.scalatestplus.play.PlaySpec

object msgpackScalaExample {

  def intArray2bytes(array: Array[Int]): Array[Byte] = {
    val out = new ByteArrayOutputStream(array.size)
    val packer = MessagePack.newDefaultPacker(out)
    intArray(array, packer)
    packer.close()
    out.toByteArray
  }

  def intArray(array: Array[Int], packer: MessagePacker): packer.type = {
    packer.packArrayHeader(array.length)
    var i = 0
    while(i < array.length){
      packer.packInt(array(i))
      i += 1
    }
    packer
  }

  def longArray2bytes(array: Array[Long]): Array[Byte] = {
    val out = new ByteArrayOutputStream(array.size)
    val packer = MessagePack.newDefaultPacker(out)
    longArray(array, packer)
    packer.close()
    out.toByteArray
  }

  def longArray(array: Array[Long], packer: MessagePacker): packer.type = {
    packer.packArrayHeader(array.length)
    var i = 0
    while(i < array.length){
      packer.packLong(array(i))
      i += 1
    }
    packer
  }

  def unpackArray(unpacker : MessageUnpacker) = {
    val size = unpacker.unpackArrayHeader()
    val data = new Array[Int](size)
    var i: Int = 0
    while ( i < size ) {
      data(i) = unpacker.unpackInt()
      i += 1;i
    }
    data
  }

}

class MgspackTests extends PlaySpec {
  import org.msgpack.core.MessagePack

  "intArray2bytes" should {
    "convert array to bytes " in {
      val arr = Array(1, 2, 3, 4)
      val pkData = msgpackScalaExample.intArray2bytes(arr)
      val unpacker = MessagePack.newDefaultUnpacker(pkData)
      val actualDataSize = unpacker.unpackArrayHeader()
      val actualData = new Array[Int](actualDataSize)
      var i: Int = 0
      while ( i < actualDataSize ) {
        actualData(i) = unpacker.unpackInt()
          i += 1;i
      }
      unpacker.close()

      actualData mustBe arr
    }

    "Primitive types " should {
      "be transformed " in {
        import org.msgpack.core.MessageBufferPacker
        import org.msgpack.core.MessagePack
        import org.msgpack.core.buffer.ArrayBufferInput

        val packer = MessagePack.newDefaultBufferPacker

        // pack (encode) primitive values in message pack format
        packer.packBoolean(true)
        packer.packShort(34.toShort)
        packer.packInt(1)
        packer.packLong(33000000000L)

        packer.packFloat(0.1f)
        packer.packDouble(3.14159263)
        packer.packByte(0x80.toByte)

        packer.packNil
        packer.flush()

        val unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray)
        unpacker.unpackBoolean() mustBe true
        unpacker.unpackShort() mustBe 34.toShort
        unpacker.unpackInt() mustBe 1
        unpacker.unpackLong() mustBe 33000000000L

        unpacker.unpackFloat() mustEqual 0.1f
        unpacker.unpackDouble() mustBe 3.14159263
        unpacker.unpackByte() mustBe 0x80.toByte
        unpacker.unpackNil()

        // pack strings (in UTF-8)
        packer.clear()
        packer.packString("hello message pack!")
        unpacker.reset(new ArrayBufferInput(packer.toByteArray))
        unpacker.unpackString() mustBe "hello message pack!"

        // [Advanced] write a raw UTF-8 string
        val s = "utf-8 strings".getBytes(MessagePack.UTF8)
        packer.clear()
        packer.packRawStringHeader(s.length)
        packer.writePayload(s)
        unpacker.reset(new ArrayBufferInput(packer.toByteArray))
        unpacker.unpackString() mustBe "utf-8 strings"

        // pack arrays
        val arr = Array[Int](3, 5, 1, 0, -1, 255)
        packer.clear()
        packer.packArrayHeader(arr.length)
        for (v <- arr) {
          println(v)
          packer.packInt(v)
        }
        unpacker.reset(new ArrayBufferInput(packer.toByteArray))
        msgpackScalaExample.unpackArray(unpacker) mustBe arr

        // pack map (key -> value) elements
        packer.packMapHeader(2) // the number of (key, value) pairs
        // Put "apple" -> 1
        packer.packString("apple")
        packer.packInt(1)
        // Put "banana" -> 2
        packer.packString("banana")
        packer.packInt(2)
        packer.flush()

        // pack binary data
        val ba = Array[Byte](1, 2, 3, 4)
        packer.packBinaryHeader(ba.length)
        packer.writePayload(ba)

        // Write ext type data: https://github.com/msgpack/msgpack/blob/master/spec.md#ext-format-family
        val extData = "custom data type".getBytes(MessagePack.UTF8)
        packer.packExtensionTypeHeader(1.toByte, 10) // type number [0, 127], data byte length

        packer.writePayload(extData)

        // Succinct syntax for packing
        packer.packInt(1).packString("leo").packArrayHeader(2).packString("xxx-xxxx").packString("yyy-yyyy")
        packer.close()
        unpacker.close()
      }
    }
  }


}
