package lineup.stream

import javax.script.{ SimpleBindings, Compilable, ScriptEngineManager }
import akka.util.ByteString
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import org.python.core.{ PyList, PyTuple }
import peds.commons.log.Trace


/**
  * Created by rolfsd on 12/30/15.
  */
object PythonPickleProtocolSpec {
  val engine = new ScriptEngineManager().getEngineByName( "python" )
  val compilable = engine.asInstanceOf[Compilable]
  val unpickleScript = compilable.compile(
    """
      |import cPickle
      |import struct
      |format = '!L'
      |headerLength = struct.calcsize(format)
      |payloadLength, = struct.unpack(format, payload[:headerLength])
      |batchLength = headerLength + payloadLength.intValue()
      |metrics = cPickle.loads(payload[headerLength:batchLength])
    """.stripMargin
  )
}

class PythonPickleProtocolSpec
extends fixture.WordSpec
with MustMatchers
with ParallelTestExecution
with MockitoSugar {
  import PythonPickleProtocolSpec._

  val trace = Trace[PythonPickleProtocolSpec]

  type Fixture = TestFixture
  override type FixtureParam = Fixture

  class TestFixture { outer =>
    val protocol: PythonPickleProtocol = new PythonPickleProtocol

    def unpickleOutput( pickle: ByteString ): String = {
      import scala.collection.mutable
      val results = mutable.StringBuilder.newBuilder
      // the charset is important. if the GraphitePickleReporter and this test
      // don't agree, the header is not always correctly unpacked.
      val payload = pickle.decodeString( "UTF-8" )
      val result = new PyList
      var nextIndex = 0
      while ( nextIndex < payload.length ) {
        val bindings = new SimpleBindings
        bindings.put( "payload", payload substring nextIndex )
        unpickleScript eval bindings
        result.addAll( result.size, bindings.get("metrics").asInstanceOf[java.util.Collection[_]] )
        nextIndex += bindings.get( "batchLength" ).asInstanceOf[Int]
      }

      import scala.collection.JavaConverters._

      result.iterator.asScala.foreach { case datapoint: PyTuple =>
        val name = datapoint.get( 0 ).toString
        val valueTuple = datapoint.get( 1 ).asInstanceOf[PyTuple]
        val timestamp = valueTuple get 0
        val value = valueTuple get 1
        results.append( name ).append( " " ).append( value ).append( " " ).append( timestamp ).append( "\n" )
      }

      results.toString()
    }
  }

  def createTestFixture(): Fixture = new Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = createTestFixture()
    try { test( fixture ) } finally { }
  }

  object WIP extends Tag( "wip" )


  "PythonPickleProtocol" should {
    "first replicate dropwizard test" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val pickler = new com.codahale.metrics.graphite.PicklerStub
      unpickleOutput( pickler.pickle( ("name", 100L, "value") ).withHeader ) mustBe "name value 100\n"
    }

    "write value" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      unpickleOutput( protocol.pickle( ("name", 100L, "value") )) mustBe "name value 100\n"
    }

    "write full batch" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val batch = Seq(
        ("foo", 100L, "value"),
        ("bar", 117L, "value2")
      )
      unpickleOutput( protocol.pickle( batch:_* ) ) mustBe "foo value 100\nbar value2 117\n"
    }

    "writes past full batch" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val batch = Seq(
        ("foo", 100L, "value"),
        ("bar", 117L, "value2"),
        ("zed", 9821L, "value3")
      )
      unpickleOutput( protocol.pickle( batch:_* ) ) mustBe "foo value 100\nbar value2 117\nzed value3 9821\n"
    }

    "writes past full batch as flattend time series" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      import org.joda.{ time => joda }

      val batch = Seq(
        ("foo", new joda.DateTime(100000L), 17D),
        ("bar", new joda.DateTime(117000L), 3.1415926D),
        ("zed", new joda.DateTime(9821000L), 983.120D)
      )

      unpickleOutput( protocol.pickleFlattenedTimeSeries( batch:_* ) ) mustBe {
        // timestamp long are be divided by 1000L to match graphite's epoch time
        "foo 17.0 100\nbar 3.1415926 117\nzed 983.12 9821\n"
      }
    }

    "writes past full batch as flattend outlier marked series" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      import org.joda.{ time => joda }

      val batch = Seq(
        ("foo", new joda.DateTime(100000L), 1D),
        ("bar", new joda.DateTime(117000L), 0D),
        ("zed", new joda.DateTime(9821000L), 0D)
      )
      unpickleOutput( protocol.pickleFlattenedTimeSeries( batch:_* ) ) mustBe {
        // timestamp long are be divided by 1000L to match graphite's epoch time
        "foo 1.0 100\nbar 0.0 117\nzed 0.0 9821\n"
      }
    }

    "write santized name" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val batch = ("name woo", 100L, "value")
      unpickleOutput( protocol.pickle( batch ) ) mustBe "name-woo value 100\n"
    }

    "write santized value" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val batch = ("name", 100L, "value woo")
      unpickleOutput( protocol.pickle( batch ) ) mustBe "name value-woo 100\n"
    }


    "match dropwizard pickle" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val tuple = ( "name", 100L, "value" )
      val pickler = new com.codahale.metrics.graphite.PicklerStub
      val expected = pickler.pickle( tuple ).withHeader
      val actual = protocol.pickle( tuple )
      actual mustBe expected
    }

    "match dropwizard pickled full batch" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._
      val tuples = Seq(
        ( "name", 100L, "value" ),
        ( "name", 100L, "value2" )
      )
      val pickler = new com.codahale.metrics.graphite.PicklerStub
      val expected = pickler.pickle( tuples:_* ).withHeader
      val actual = protocol.pickle( tuples:_* )
      actual mustBe expected
    }

    "match dropwizard pickled past full batch" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._

      val tuples = Seq(
        ( "name", 100L, "value" ),
        ( "name", 100L, "value2" ),
        ( "name", 100L, "value3" )
      )
      val pickler = new com.codahale.metrics.graphite.PicklerStub
      val expected = pickler.pickle( tuples:_* ).withHeader
      val actual = protocol.pickle( tuples:_* )
      actual mustBe expected
    }

    "match dropwizard pickle santized names" in { f: Fixture =>
      import f._
      import PythonPickleProtocol._

      val pickler = new com.codahale.metrics.graphite.PicklerStub
      val expected = pickler.pickle( ("name-woo", 100L, "value") ).withHeader
      val actual = protocol.pickle( (protocol.sanitize("name woo"), 100L, "value") )
      actual mustBe expected
    }
  }
}
