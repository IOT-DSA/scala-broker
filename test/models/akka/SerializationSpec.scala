package models.akka

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

import scala.util.Random

import org.joda.time.Duration
import org.joda.time.format.PeriodFormat
import org.objenesis.strategy.StdInstantiatorStrategy
import org.scalatestplus.play.PlaySpec
import org.slf4j.LoggerFactory

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{ Input, Output, UnsafeInput, UnsafeOutput }

import models.{ RequestEnvelope, ResponseEnvelope }
import models.rpc.{ ColumnInfo, DSAResponse, InvokeRequest, StreamState }
import models.rpc.DSAValue._

/**
 * Message serialization test suite.
 */
class SerializationSpec extends PlaySpec {
  val log = LoggerFactory.getLogger(getClass)

  val reqEnv = createRequestEnvelope(1000)
  val rspEnv = createResponseEnvelope(1000)

  val kryoU = createKryo(false)
  val kryoR = createKryo(true)

  "Java Serialization" should {
    "handle request envelopes" in {
      val tgt = runTest("Java", reqEnv.requests.size, serializeWithJava, deserializeWithJava)(reqEnv)
      compareRequests(reqEnv, tgt.asInstanceOf[RequestEnvelope])
    }
    "handle response envelopes" in {
      val tgt = runTest("Java", rspEnv.responses.size, serializeWithJava, deserializeWithJava)(rspEnv)
      compareResponses(rspEnv, tgt.asInstanceOf[ResponseEnvelope])
    }
  }

  "Safe Kryo Serialization" should {
    "handle request envelopes without registration" in {
      val tgt = runTest("Kryo Safe NoReg", reqEnv.requests.size, serializeWithKryo(kryoU, false),
        deserializeWithKryo(kryoU, false))(reqEnv)
      compareRequests(reqEnv, tgt.asInstanceOf[RequestEnvelope])
    }
    "handle request envelopes with registration" in {
      val tgt = runTest("Kryo Safe Reg", reqEnv.requests.size, serializeWithKryo(kryoR, false),
        deserializeWithKryo(kryoR, false))(reqEnv)
      compareRequests(reqEnv, tgt.asInstanceOf[RequestEnvelope])
    }
    "handle response envelopes without registration" in {
      val tgt = runTest("Kryo Safe NoReg", rspEnv.responses.size, serializeWithKryo(kryoU, false),
        deserializeWithKryo(kryoU, false))(rspEnv)
      compareResponses(rspEnv, tgt.asInstanceOf[ResponseEnvelope])
    }
    "handle response envelopes with registration" in {
      val tgt = runTest("Kryo Safe Reg", rspEnv.responses.size, serializeWithKryo(kryoR, false),
        deserializeWithKryo(kryoR, false))(rspEnv)
      compareResponses(rspEnv, tgt.asInstanceOf[ResponseEnvelope])
    }
  }

  "Unsafe Kryo Serialization" should {
    "handle request envelopes without registration" in {
      val tgt = runTest("Kryo Unsafe NoReg", reqEnv.requests.size, serializeWithKryo(kryoU, true),
        deserializeWithKryo(kryoU, true))(reqEnv)
      compareRequests(reqEnv, tgt.asInstanceOf[RequestEnvelope])
    }
    "handle request envelopes with registration" in {
      val tgt = runTest("Kryo Unsafe Reg", reqEnv.requests.size, serializeWithKryo(kryoR, true),
        deserializeWithKryo(kryoR, true))(reqEnv)
      compareRequests(reqEnv, tgt.asInstanceOf[RequestEnvelope])
    }
    "handle response envelopes without registration" in {
      val tgt = runTest("Kryo Unsafe NoReg", rspEnv.responses.size, serializeWithKryo(kryoU, true),
        deserializeWithKryo(kryoU, true))(rspEnv)
      compareResponses(rspEnv, tgt.asInstanceOf[ResponseEnvelope])
    }
    "handle response envelopes Unsafe registration" in {
      val tgt = runTest("Kryo Unsafe Reg", rspEnv.responses.size, serializeWithKryo(kryoR, true),
        deserializeWithKryo(kryoR, true))(rspEnv)
      compareResponses(rspEnv, tgt.asInstanceOf[ResponseEnvelope])
    }
  }

  /**
   * Compares two request envelopes.
   */
  private def compareRequests(env1: RequestEnvelope, env2: RequestEnvelope) = {
    env1.requests.size mustBe env2.requests.size
    env1.requests zip env2.requests foreach {
      case (r1, r2) => r1.toString mustEqual r2.toString
    }
  }

  /**
   * Compares two response envelopes.
   */
  private def compareResponses(env1: ResponseEnvelope, env2: ResponseEnvelope) = {
    env1.responses.size mustBe env2.responses.size
    env1.responses zip env2.responses foreach {
      case (r1, r2) => r1.toString mustEqual r2.toString
    }
  }

  /**
   * Runs a test by serializing and then deserializing the supplied object.
   */
  def runTest(name: String, size: Int, serFunc: Object => Array[Byte], desFunc: Array[Byte] => Object)(src: Object) = {
    val (data, durationSer) = timer(serFunc(src))
    val (tgt, durationDes) = timer(desFunc(data))

    printReport(name, size, data, durationSer, durationDes)

    tgt
  }

  /**
   * Serializes the argument using Java serialization.
   */
  def serializeWithJava(obj: Object) = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    oos.close

    baos.toByteArray
  }

  /**
   * Deserializes the byte array using Java serialization.
   */
  def deserializeWithJava(data: Array[Byte]) = {
    val bais = new ByteArrayInputStream(data)
    val ois = new ObjectInputStream(bais)
    val obj = ois.readObject
    ois.close
    bais.close

    obj
  }

  /**
   * Serializes the argument using Kryo serialization.
   */
  def serializeWithKryo(kryo: Kryo, unsafe: Boolean)(obj: Object) = {
    val output = if (unsafe) new UnsafeOutput(1000, -1) else new Output(1000, -1)
    kryo.writeClassAndObject(output, obj)
    output.close

    output.toBytes
  }

  /**
   * Deserializes the byte array using Kryo serialization.
   */
  def deserializeWithKryo(kryo: Kryo, unsafe: Boolean)(data: Array[Byte]) = {
    val input = if (unsafe) new UnsafeInput(data) else new Input(data)
    val obj = kryo.readClassAndObject(input)
    input.close

    obj
  }

  /**
   * Creates a Kryo instance.
   */
  def createKryo(registered: Boolean) = {
    val kryo = new Kryo
    kryo.setInstantiatorStrategy(new StdInstantiatorStrategy)
    if (registered) {
      kryo.setRegistrationRequired(true)
      kryo.register(classOf[RequestEnvelope])
      kryo.register(classOf[ResponseEnvelope])
      kryo.register(classOf[ColumnInfo])
      kryo.register(classOf[DSAResponse])
      kryo.register(classOf[models.rpc.StreamState$])
      kryo.register(classOf[Some[_]])
      kryo.register(Class.forName("models.rpc.DSAValue$ArrayValue"))
      kryo.register(classOf[InvokeRequest])
      kryo.register(classOf[models.rpc.DSAMethod$])
      kryo.register(classOf[Enumeration$Val])
      kryo.register(classOf[scala.Enumeration$ValueSet$])
      kryo.register(classOf[scala.Enumeration$ValueSet])
      kryo.register(classOf[scala.collection.immutable.BitSet$BitSet1])
      kryo.register(Class.forName("scala.collection.immutable.$colon$colon"))
      kryo.register(classOf[scala.collection.immutable.Nil$])
      kryo.register(classOf[scala.collection.immutable.Vector[_]])
      kryo.register(classOf[scala.collection.mutable.HashMap[_, _]])
      kryo.register(classOf[Array[Object]])
      kryo.register(classOf[scala.collection.immutable.Map$Map3])
      kryo.register(classOf[models.rpc.DSAValue.NumericValue])
      kryo.register(classOf[models.rpc.DSAValue.BooleanValue])
      kryo.register(classOf[models.rpc.DSAValue.StringValue])
      kryo.register(classOf[scala.math.BigDecimal])
      kryo.register(classOf[java.math.BigDecimal])
      kryo.register(classOf[java.math.MathContext])
      kryo.register(classOf[java.math.RoundingMode])
      kryo.register(classOf[scala.None$])
    }
    kryo
  }

  /**
   * Creates a request envelope.
   */
  def createRequestEnvelope(count: Int) = {
    val requests = (1 to count) map { index =>
      val path = Random.alphanumeric.take(50).mkString("")
      val params: DSAMap = Map("a" -> Random.nextInt(100), "b" -> Random.nextBoolean,
        "c" -> Random.alphanumeric.take(20).mkString(""))
      InvokeRequest(index, path, params)
    }
    RequestEnvelope(requests)
  }

  /**
   * Creates a response envelope.
   */
  def createResponseEnvelope(count: Int) = {
    val responses = (1 to count) map { index =>
      val updates = rows("$is" -> "node", "a" -> Random.nextInt(100), "b" -> Random.nextBoolean,
        "c" -> Random.alphanumeric.take(20).mkString(""))
      val columns = List(ColumnInfo("$is", "string"), ColumnInfo("a", "number"), ColumnInfo("b", "boolean"))
      DSAResponse(index, Some(StreamState.Open), Some(updates), Some(columns), None)
    }
    ResponseEnvelope(responses)
  }
  /**
   * Runs a task and measures the time it takes.
   */
  def timer[T](body: => T): (T, Duration) = {
    val start = System.currentTimeMillis
    val result = body
    val end = System.currentTimeMillis

    (result, new Duration(start, end))
  }

  /**
   * Prints test report.
   */
  def printReport(prefix: String, size: Int, data: Array[Byte], serDuration: Duration, desDuration: Duration) = {
    val periodFormat = PeriodFormat.getDefault

    def str(duration: Duration) = periodFormat.print(duration.toPeriod)

    val total = serDuration.plus(desDuration)
    log.debug("%s: %d items. Serialized in %s. Deserialized in %s. Total: %s. Data size: %d bytes per item.".format(
      prefix, size, str(serDuration), str(desDuration), str(total), data.size / size))
  }
}